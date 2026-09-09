package personal

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

func newTestStore(t *testing.T) *Store {
	t.Helper()
	store, err := Open(filepath.Join(t.TempDir(), "pocketexit"))
	if err != nil {
		t.Fatal(err)
	}
	return store
}

func TestOpenGeneratesSecretsOnFirstRun(t *testing.T) {
	store := newTestStore(t)

	for _, secret := range []struct {
		name  string
		value string
	}{
		{name: "admin token", value: store.AdminToken()},
		{name: "SOCKS password", value: store.SOCKSPassword()},
	} {
		decoded, err := base64.RawURLEncoding.DecodeString(secret.value)
		if err != nil {
			t.Fatalf("%s is not base64url: %v", secret.name, err)
		}
		if len(decoded) != secretBytes {
			t.Fatalf("%s carries %d bytes, expected %d", secret.name, len(decoded), secretBytes)
		}
	}
	if store.AdminToken() == store.SOCKSPassword() {
		t.Fatal("admin token and SOCKS password are identical")
	}
	if store.SOCKSUsername() != defaultSOCKSUsername {
		t.Fatalf("unexpected SOCKS username %q", store.SOCKSUsername())
	}

	directoryInfo, err := os.Stat(store.Directory())
	if err != nil {
		t.Fatal(err)
	}
	if mode := directoryInfo.Mode().Perm(); mode != 0o700 {
		t.Fatalf("state directory mode is %#o, expected 0700", mode)
	}
	stateInfo, err := os.Stat(filepath.Join(store.Directory(), stateFileName))
	if err != nil {
		t.Fatal(err)
	}
	if mode := stateInfo.Mode().Perm(); mode != 0o600 {
		t.Fatalf("state file mode is %#o, expected 0600", mode)
	}
}

func TestOpenRefusesLooseDirectoryPermissions(t *testing.T) {
	tests := []struct {
		name    string
		mode    os.FileMode
		wantErr bool
	}{
		{name: "owner only", mode: 0o700, wantErr: false},
		{name: "group readable", mode: 0o750, wantErr: true},
		{name: "other readable", mode: 0o705, wantErr: true},
		{name: "world writable", mode: 0o777, wantErr: true},
		{name: "group execute only", mode: 0o710, wantErr: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			directory := filepath.Join(t.TempDir(), "pocketexit")
			if err := os.MkdirAll(directory, 0o700); err != nil {
				t.Fatal(err)
			}
			if err := os.Chmod(directory, test.mode); err != nil {
				t.Fatal(err)
			}
			_, err := Open(directory)
			if !test.wantErr {
				if err != nil {
					t.Fatalf("expected mode %#o to be accepted: %v", test.mode, err)
				}
				return
			}
			if err == nil {
				t.Fatalf("expected mode %#o to be refused", test.mode)
			}
			if !strings.Contains(err.Error(), directory) {
				t.Fatalf("error does not name the directory: %v", err)
			}
			if !strings.Contains(err.Error(), fmt.Sprintf("%#o", test.mode.Perm())) {
				t.Fatalf("error does not name the mode: %v", err)
			}
		})
	}
}

func TestStateRoundTripsThroughDisk(t *testing.T) {
	store := newTestStore(t)
	first, err := store.AddNode("pixel-8-a1b2c3d4", "Pixel 8")
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.AddNode("moto-g", "Moto G")
	if err != nil {
		t.Fatal(err)
	}

	reopened, err := Open(store.Directory())
	if err != nil {
		t.Fatal(err)
	}
	if reopened.AdminToken() != store.AdminToken() || reopened.SOCKSPassword() != store.SOCKSPassword() {
		t.Fatal("secrets changed across a reopen")
	}
	nodes := reopened.ListNodes()
	if len(nodes) != 2 || nodes[0].NodeID != "moto-g" || nodes[1].NodeID != "pixel-8-a1b2c3d4" {
		t.Fatalf("unexpected node list: %+v", nodes)
	}
	restored, ok := reopened.GetNode(first.NodeID)
	if !ok {
		t.Fatal("first node is missing after a reopen")
	}
	if restored.Token != first.Token || restored.DeviceName != first.DeviceName || !restored.PairedAt.Equal(first.PairedAt) {
		t.Fatalf("node was not restored faithfully: %+v vs %+v", restored, first)
	}
	if second.Token == first.Token {
		t.Fatal("two nodes were minted the same token")
	}

	if err := reopened.RemoveNode("moto-g"); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(store.Directory()); err != nil {
		t.Fatal(err)
	}
	if _, ok := reopened.GetNode("moto-g"); ok {
		t.Fatal("removed node is still present")
	}
}

func TestStateFileMatchesContract(t *testing.T) {
	store := newTestStore(t)
	if _, err := store.AddNode("pixel-8-a1b2c3d4", "Pixel 8"); err != nil {
		t.Fatal(err)
	}

	payload, err := os.ReadFile(filepath.Join(store.Directory(), stateFileName))
	if err != nil {
		t.Fatal(err)
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(payload, &raw); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"version", "admin_token", "socks_username", "socks_password", "nodes"} {
		if _, ok := raw[key]; !ok {
			t.Fatalf("state.json is missing %q: %s", key, payload)
		}
	}
	if len(raw) != 5 {
		t.Fatalf("state.json carries unexpected keys: %s", payload)
	}

	var nodes map[string]map[string]any
	if err := json.Unmarshal(raw["nodes"], &nodes); err != nil {
		t.Fatal(err)
	}
	node, ok := nodes["pixel-8-a1b2c3d4"]
	if !ok {
		t.Fatalf("node is not keyed by its ID: %s", payload)
	}
	if len(node) != 3 || node["device_name"] != "Pixel 8" {
		t.Fatalf("unexpected node object: %+v", node)
	}
	pairedAt, ok := node["paired_at"].(string)
	if !ok {
		t.Fatalf("paired_at is not a string: %+v", node)
	}
	if _, err := time.Parse(time.RFC3339, pairedAt); err != nil || !strings.HasSuffix(pairedAt, "Z") {
		t.Fatalf("paired_at %q is not RFC3339 UTC: %v", pairedAt, err)
	}
}

func TestSaveNeverLeavesAPartialFile(t *testing.T) {
	store := newTestStore(t)
	path := filepath.Join(store.Directory(), stateFileName)

	done := make(chan struct{})
	var writers sync.WaitGroup
	for worker := 0; worker < 4; worker++ {
		writers.Add(1)
		go func(worker int) {
			defer writers.Done()
			for index := 0; index < 40; index++ {
				nodeID := "phone-" + string(rune('a'+worker)) + "-" + strconv.Itoa(index)
				if _, err := store.AddNode(nodeID, "Phone"); err != nil {
					t.Errorf("add %s: %v", nodeID, err)
					return
				}
				if err := store.RemoveNode(nodeID); err != nil {
					t.Errorf("remove %s: %v", nodeID, err)
					return
				}
			}
		}(worker)
	}
	go func() {
		writers.Wait()
		close(done)
	}()

	for reads := 0; ; reads++ {
		select {
		case <-done:
			if reads == 0 {
				t.Fatal("the reader never observed the state file")
			}
			entries, err := os.ReadDir(store.Directory())
			if err != nil {
				t.Fatal(err)
			}
			if len(entries) != 1 || entries[0].Name() != stateFileName {
				t.Fatalf("temporary files were left behind: %+v", entries)
			}
			return
		default:
		}
		payload, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("read state: %v", err)
		}
		var observed State
		if err := json.Unmarshal(payload, &observed); err != nil {
			t.Fatalf("observed a partial state file: %v: %s", err, payload)
		}
		if observed.AdminToken != store.AdminToken() {
			t.Fatalf("observed a truncated admin token %q", observed.AdminToken)
		}
	}
}

func TestNodeMapRejectsInvalidInput(t *testing.T) {
	tests := []struct {
		name       string
		nodeID     string
		deviceName string
	}{
		{name: "empty node ID", nodeID: "", deviceName: "Pixel 8"},
		{name: "node ID with a slash", nodeID: "pixel/8", deviceName: "Pixel 8"},
		{name: "node ID with an at sign", nodeID: "pixel@8", deviceName: "Pixel 8"},
		{name: "oversized node ID", nodeID: strings.Repeat("p", 65), deviceName: "Pixel 8"},
		{name: "empty device name", nodeID: "pixel-8", deviceName: "   "},
		{name: "oversized device name", nodeID: "pixel-8", deviceName: strings.Repeat("n", maxDeviceNameLength+1)},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			store := newTestStore(t)
			if _, err := store.AddNode(test.nodeID, test.deviceName); err == nil {
				t.Fatalf("expected node %q device %q to be refused", test.nodeID, test.deviceName)
			}
			if nodes := store.ListNodes(); len(nodes) != 0 {
				t.Fatalf("refused node was stored: %+v", nodes)
			}
		})
	}
}

func TestNodeMapReportsExistenceErrors(t *testing.T) {
	store := newTestStore(t)
	if _, err := store.AddNode("pixel-8", "Pixel 8"); err != nil {
		t.Fatal(err)
	}
	if _, err := store.AddNode("pixel-8", "Pixel 8"); !errors.Is(err, ErrNodeExists) {
		t.Fatalf("expected ErrNodeExists, got %v", err)
	}
	if err := store.RemoveNode("unknown"); !errors.Is(err, ErrNodeNotFound) {
		t.Fatalf("expected ErrNodeNotFound, got %v", err)
	}
}

func TestOpenRejectsUnusableStateFiles(t *testing.T) {
	tests := []struct {
		name    string
		payload string
	}{
		{name: "malformed JSON", payload: "{"},
		{name: "unsupported version", payload: `{"version":2,"nodes":{}}`},
		{name: "invalid node ID", payload: `{"version":1,"admin_token":"a","socks_username":"proxy","socks_password":"p","nodes":{"pixel 8":{"token":"t"}}}`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			directory := filepath.Join(t.TempDir(), "pocketexit")
			if err := os.MkdirAll(directory, 0o700); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(directory, stateFileName), []byte(test.payload), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := Open(directory); err == nil {
				t.Fatalf("expected %s to be refused", test.name)
			}
		})
	}
}

func TestNodeMapIsSafeUnderConcurrentUse(t *testing.T) {
	store := newTestStore(t)
	var workers sync.WaitGroup
	for worker := 0; worker < 8; worker++ {
		workers.Add(1)
		go func(worker int) {
			defer workers.Done()
			nodeID := "phone-" + string(rune('a'+worker))
			for index := 0; index < 20; index++ {
				if _, err := store.AddNode(nodeID, "Phone"); err != nil {
					t.Errorf("add %s: %v", nodeID, err)
					return
				}
				store.GetNode(nodeID)
				store.ListNodes()
				store.Lookup(nodeID)
				if err := store.RemoveNode(nodeID); err != nil {
					t.Errorf("remove %s: %v", nodeID, err)
					return
				}
			}
		}(worker)
	}
	workers.Wait()
	if nodes := store.ListNodes(); len(nodes) != 0 {
		t.Fatalf("expected an empty node map, got %+v", nodes)
	}
}
