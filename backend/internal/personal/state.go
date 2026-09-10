package personal

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	stateVersion         = 1
	stateFileName        = "state.json"
	defaultSOCKSUsername = "proxy"
	secretBytes          = 32
	maxDeviceNameLength  = 64
)

var (
	ErrNodeNotFound = errors.New("node not found")
	ErrNodeExists   = errors.New("node is already paired")
)

type NodeCredential struct {
	// The node ID is the key of the nodes object in state.json, so it is
	// restored from the map on load rather than stored twice.
	NodeID     string    `json:"-"`
	Token      string    `json:"token"`
	DeviceName string    `json:"device_name"`
	PairedAt   time.Time `json:"paired_at"`
}

type State struct {
	Version       int                       `json:"version"`
	AdminToken    string                    `json:"admin_token"`
	SOCKSUsername string                    `json:"socks_username"`
	SOCKSPassword string                    `json:"socks_password"`
	Nodes         map[string]NodeCredential `json:"nodes"`
}

type Store struct {
	mu        sync.RWMutex
	directory string
	state     State
}

func Home() (string, error) {
	if value := strings.TrimSpace(os.Getenv("POCKETEXIT_HOME")); value != "" {
		absolute, err := filepath.Abs(value)
		if err != nil {
			return "", fmt.Errorf("resolve POCKETEXIT_HOME %q: %w", value, err)
		}
		return absolute, nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("resolve home directory: %w", err)
	}
	return filepath.Join(home, ".pocketexit"), nil
}

func Open(directory string) (*Store, error) {
	if strings.TrimSpace(directory) == "" {
		return nil, fmt.Errorf("state directory path is empty")
	}
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return nil, fmt.Errorf("create state directory %s: %w", directory, err)
	}
	info, err := os.Stat(directory)
	if err != nil {
		return nil, fmt.Errorf("inspect state directory %s: %w", directory, err)
	}
	if !info.IsDir() {
		return nil, fmt.Errorf("state directory %s is not a directory", directory)
	}
	// The directory holds the admin token, the SOCKS password, every agent
	// token and the TLS private key: refuse to use it if anybody else on the
	// machine can read it.
	if mode := info.Mode().Perm(); mode&0o077 != 0 {
		return nil, fmt.Errorf(
			"state directory %s has mode %#o and must not be accessible to group or other: run chmod 700 %s",
			directory, mode, directory)
	}

	// The directory mode says nothing about the files inside it: state.json and
	// the TLS private key can arrive group- or world-readable from a backup
	// restore, a copy from another machine or an extracted tarball. Both are
	// written 0600 and are refused on the same terms as the directory.
	for _, name := range []string{stateFileName, keyFileName} {
		if err := requireOwnerOnlyFile(filepath.Join(directory, name)); err != nil {
			return nil, err
		}
	}

	store := &Store{
		directory: directory,
		state: State{
			Version:       stateVersion,
			SOCKSUsername: defaultSOCKSUsername,
			Nodes:         map[string]NodeCredential{},
		},
	}
	if err := store.load(); err != nil {
		return nil, err
	}
	return store, nil
}

// requireOwnerOnlyFile refuses a secret file that anybody but its owner can
// read. A file that does not exist yet is the first run and is fine: the
// writer creates it 0600.
func requireOwnerOnlyFile(path string) error {
	info, err := os.Stat(path)
	switch {
	case errors.Is(err, fs.ErrNotExist):
		return nil
	case err != nil:
		return fmt.Errorf("inspect %s: %w", path, err)
	}
	if mode := info.Mode().Perm(); mode&0o077 != 0 {
		return fmt.Errorf(
			"%s has mode %#o and must not be accessible to group or other: run chmod 600 %s",
			path, mode, path)
	}
	return nil
}

func (s *Store) load() error {
	path := s.statePath()
	payload, err := os.ReadFile(path)
	switch {
	case errors.Is(err, fs.ErrNotExist):
		// First run: fall through with the zero state and let the secret
		// generation below write the file.
	case err != nil:
		return fmt.Errorf("read %s: %w", path, err)
	default:
		var loaded State
		if err := json.Unmarshal(payload, &loaded); err != nil {
			return fmt.Errorf("parse %s: %w", path, err)
		}
		if loaded.Version != stateVersion {
			return fmt.Errorf("unsupported state version %d in %s, expected %d", loaded.Version, path, stateVersion)
		}
		if loaded.Nodes == nil {
			loaded.Nodes = map[string]NodeCredential{}
		}
		for nodeID, credential := range loaded.Nodes {
			if !validNodeID(nodeID) {
				return fmt.Errorf("%s contains invalid node ID %q", path, nodeID)
			}
			credential.NodeID = nodeID
			loaded.Nodes[nodeID] = credential
		}
		s.state = loaded
	}

	changed := false
	if s.state.AdminToken == "" {
		token, err := randomSecret()
		if err != nil {
			return err
		}
		s.state.AdminToken = token
		changed = true
	}
	if s.state.SOCKSPassword == "" {
		password, err := randomSecret()
		if err != nil {
			return err
		}
		s.state.SOCKSPassword = password
		changed = true
	}
	if s.state.SOCKSUsername == "" {
		s.state.SOCKSUsername = defaultSOCKSUsername
		changed = true
	}
	if changed {
		return s.save()
	}
	return nil
}

// save serialises the state to disk. Callers must hold the write lock, except
// during Open where the store is not yet shared.
func (s *Store) save() error {
	payload, err := json.MarshalIndent(s.state, "", "  ")
	if err != nil {
		return fmt.Errorf("encode state: %w", err)
	}
	payload = append(payload, '\n')
	return writeFileAtomic(s.statePath(), payload, 0o600)
}

func (s *Store) statePath() string {
	return filepath.Join(s.directory, stateFileName)
}

func (s *Store) Directory() string {
	return s.directory
}

func (s *Store) AdminToken() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.state.AdminToken
}

func (s *Store) SOCKSUsername() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.state.SOCKSUsername
}

func (s *Store) SOCKSPassword() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.state.SOCKSPassword
}

func (s *Store) AddNode(nodeID, deviceName string) (NodeCredential, error) {
	nodeID = strings.TrimSpace(nodeID)
	if !validNodeID(nodeID) {
		return NodeCredential{}, fmt.Errorf("invalid node ID %q", nodeID)
	}
	deviceName = strings.TrimSpace(deviceName)
	if len(deviceName) < 1 || len(deviceName) > maxDeviceNameLength {
		return NodeCredential{}, fmt.Errorf("device name must contain 1-%d bytes", maxDeviceNameLength)
	}
	token, err := randomSecret()
	if err != nil {
		return NodeCredential{}, err
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	if _, exists := s.state.Nodes[nodeID]; exists {
		return NodeCredential{}, ErrNodeExists
	}
	credential := NodeCredential{
		NodeID:     nodeID,
		Token:      token,
		DeviceName: deviceName,
		PairedAt:   time.Now().UTC().Truncate(time.Second),
	}
	s.state.Nodes[nodeID] = credential
	if err := s.save(); err != nil {
		delete(s.state.Nodes, nodeID)
		return NodeCredential{}, err
	}
	return credential, nil
}

func (s *Store) RemoveNode(nodeID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	credential, exists := s.state.Nodes[nodeID]
	if !exists {
		return ErrNodeNotFound
	}
	delete(s.state.Nodes, nodeID)
	if err := s.save(); err != nil {
		s.state.Nodes[nodeID] = credential
		return err
	}
	return nil
}

func (s *Store) GetNode(nodeID string) (NodeCredential, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	credential, ok := s.state.Nodes[nodeID]
	return credential, ok
}

func (s *Store) ListNodes() []NodeCredential {
	s.mu.RLock()
	result := make([]NodeCredential, 0, len(s.state.Nodes))
	for _, credential := range s.state.Nodes {
		result = append(result, credential)
	}
	s.mu.RUnlock()

	sort.Slice(result, func(i, j int) bool { return result[i].NodeID < result[j].NodeID })
	return result
}

func randomSecret() (string, error) {
	buffer := make([]byte, secretBytes)
	if _, err := rand.Read(buffer); err != nil {
		return "", fmt.Errorf("read random bytes: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(buffer), nil
}

func validNodeID(value string) bool {
	if len(value) < 1 || len(value) > 64 {
		return false
	}
	for _, character := range value {
		if (character < 'a' || character > 'z') && (character < 'A' || character > 'Z') &&
			(character < '0' || character > '9') && character != '.' && character != '_' && character != '-' {
			return false
		}
	}
	return true
}

// writeFileAtomic writes payload to a temporary file in the destination
// directory and renames it into place, so a reader never observes a partially
// written secret and a failed write never truncates the previous contents.
func writeFileAtomic(path string, payload []byte, perm os.FileMode) error {
	directory := filepath.Dir(path)
	temporary, err := os.CreateTemp(directory, "."+filepath.Base(path)+".tmp*")
	if err != nil {
		return fmt.Errorf("create temporary file in %s: %w", directory, err)
	}
	name := temporary.Name()
	defer os.Remove(name)

	if err := temporary.Chmod(perm); err != nil {
		temporary.Close()
		return fmt.Errorf("set mode on %s: %w", name, err)
	}
	if _, err := temporary.Write(payload); err != nil {
		temporary.Close()
		return fmt.Errorf("write %s: %w", name, err)
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return fmt.Errorf("sync %s: %w", name, err)
	}
	if err := temporary.Close(); err != nil {
		return fmt.Errorf("close %s: %w", name, err)
	}
	if err := os.Rename(name, path); err != nil {
		return fmt.Errorf("rename %s to %s: %w", name, path, err)
	}
	// Syncing the temporary file only makes its contents durable. The rename
	// itself lives in the directory, so without this a crash straight after a
	// claim can lose the agent token the phone has already been told it is
	// paired with.
	return syncDirectory(directory)
}

// syncDirectory flushes a directory entry to disk. Not every filesystem
// implements fsync on a directory: those report ENOTSUP or EINVAL, and since
// the file itself is already written that is not a failure worth refusing a
// claim over.
func syncDirectory(directory string) error {
	handle, err := os.Open(directory)
	if err != nil {
		return fmt.Errorf("open %s: %w", directory, err)
	}
	if err := handle.Sync(); err != nil &&
		!errors.Is(err, errors.ErrUnsupported) && !errors.Is(err, syscall.EINVAL) {
		handle.Close()
		return fmt.Errorf("sync %s: %w", directory, err)
	}
	if err := handle.Close(); err != nil {
		return fmt.Errorf("close %s: %w", directory, err)
	}
	return nil
}
