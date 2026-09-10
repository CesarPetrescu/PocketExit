package personal

import (
	"errors"
	"sort"
)

// ErrTokensImmutable is returned by the server-mode token store, whose nodes
// come from AGENT_TOKENS_JSON and cannot change at runtime. Callers map it to
// HTTP 409.
var ErrTokensImmutable = errors.New("agent tokens are configured statically and cannot be changed at runtime")

// TokenStore answers which token authenticates a node, so the HTTP API can hold
// one value for both modes.
type TokenStore interface {
	Lookup(nodeID string) (string, bool)
	Mint(nodeID, deviceName string) (string, error)
	Revoke(nodeID string) error
	List() []NodeCredential
}

var (
	_ TokenStore = (*StaticTokenStore)(nil)
	_ TokenStore = (*Store)(nil)
)

type StaticTokenStore struct {
	tokens map[string]string
}

func NewStaticTokenStore(tokens map[string]string) *StaticTokenStore {
	copied := make(map[string]string, len(tokens))
	for nodeID, token := range tokens {
		copied[nodeID] = token
	}
	return &StaticTokenStore{tokens: copied}
}

func (s *StaticTokenStore) Lookup(nodeID string) (string, bool) {
	token, ok := s.tokens[nodeID]
	if !ok || token == "" {
		return "", false
	}
	return token, true
}

func (s *StaticTokenStore) Mint(string, string) (string, error) {
	return "", ErrTokensImmutable
}

func (s *StaticTokenStore) Revoke(string) error {
	return ErrTokensImmutable
}

func (s *StaticTokenStore) List() []NodeCredential {
	result := make([]NodeCredential, 0, len(s.tokens))
	for nodeID, token := range s.tokens {
		result = append(result, NodeCredential{NodeID: nodeID, Token: token})
	}
	sort.Slice(result, func(i, j int) bool { return result[i].NodeID < result[j].NodeID })
	return result
}

func (s *Store) Lookup(nodeID string) (string, bool) {
	credential, ok := s.GetNode(nodeID)
	if !ok || credential.Token == "" {
		return "", false
	}
	return credential.Token, true
}

func (s *Store) Mint(nodeID, deviceName string) (string, error) {
	credential, err := s.AddNode(nodeID, deviceName)
	if err != nil {
		return "", err
	}
	return credential.Token, nil
}

func (s *Store) Revoke(nodeID string) error {
	return s.RemoveNode(nodeID)
}

func (s *Store) List() []NodeCredential {
	return s.ListNodes()
}
