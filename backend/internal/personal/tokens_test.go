package personal

import (
	"errors"
	"testing"
)

func TestStaticTokenStoreServesConfiguredNodes(t *testing.T) {
	store := NewStaticTokenStore(map[string]string{
		"phone-b": "token-b",
		"phone-a": "token-a",
		"phone-c": "",
	})

	tests := []struct {
		name      string
		nodeID    string
		wantToken string
		wantOK    bool
	}{
		{name: "configured node", nodeID: "phone-a", wantToken: "token-a", wantOK: true},
		{name: "unknown node", nodeID: "phone-z"},
		{name: "empty token", nodeID: "phone-c"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			token, ok := store.Lookup(test.nodeID)
			if token != test.wantToken || ok != test.wantOK {
				t.Fatalf("Lookup(%q) = %q, %v, expected %q, %v", test.nodeID, token, ok, test.wantToken, test.wantOK)
			}
		})
	}

	listed := store.List()
	if len(listed) != 3 || listed[0].NodeID != "phone-a" || listed[1].NodeID != "phone-b" || listed[2].NodeID != "phone-c" {
		t.Fatalf("unexpected listing: %+v", listed)
	}
	if listed[0].Token != "token-a" {
		t.Fatalf("listing dropped the token: %+v", listed[0])
	}
}

func TestStaticTokenStoreRefusesRuntimeChanges(t *testing.T) {
	store := NewStaticTokenStore(map[string]string{"phone-a": "token-a"})
	if _, err := store.Mint("phone-b", "Pixel 8"); !errors.Is(err, ErrTokensImmutable) {
		t.Fatalf("expected ErrTokensImmutable from Mint, got %v", err)
	}
	if err := store.Revoke("phone-a"); !errors.Is(err, ErrTokensImmutable) {
		t.Fatalf("expected ErrTokensImmutable from Revoke, got %v", err)
	}
	if token, ok := store.Lookup("phone-a"); !ok || token != "token-a" {
		t.Fatal("a refused mutation changed the store")
	}
}

func TestStaticTokenStoreCopiesItsInput(t *testing.T) {
	tokens := map[string]string{"phone-a": "token-a"}
	store := NewStaticTokenStore(tokens)
	delete(tokens, "phone-a")
	if _, ok := store.Lookup("phone-a"); !ok {
		t.Fatal("the store aliased the caller's map")
	}
}

func TestStateTokenStoreMintsAndRevokes(t *testing.T) {
	store := newTestStore(t)
	if _, ok := store.Lookup("pixel-8"); ok {
		t.Fatal("an unpaired node has a token")
	}

	token, err := store.Mint("pixel-8", "Pixel 8")
	if err != nil {
		t.Fatal(err)
	}
	if len(token) == 0 {
		t.Fatal("Mint returned an empty token")
	}
	looked, ok := store.Lookup("pixel-8")
	if !ok || looked != token {
		t.Fatalf("Lookup returned %q, %v, expected %q", looked, ok, token)
	}
	listed := store.List()
	if len(listed) != 1 || listed[0].NodeID != "pixel-8" || listed[0].DeviceName != "Pixel 8" || listed[0].Token != token {
		t.Fatalf("unexpected listing: %+v", listed)
	}

	// A personal-mode store survives a restart, unlike the static one.
	reopened, err := Open(store.Directory())
	if err != nil {
		t.Fatal(err)
	}
	if persisted, ok := reopened.Lookup("pixel-8"); !ok || persisted != token {
		t.Fatalf("the token did not persist: %q, %v", persisted, ok)
	}

	if err := store.Revoke("unknown"); !errors.Is(err, ErrNodeNotFound) {
		t.Fatalf("expected ErrNodeNotFound, got %v", err)
	}
	if err := store.Revoke("pixel-8"); err != nil {
		t.Fatal(err)
	}
	if _, ok := store.Lookup("pixel-8"); ok {
		t.Fatal("a revoked node still authenticates")
	}
	if listed := store.List(); len(listed) != 0 {
		t.Fatalf("a revoked node is still listed: %+v", listed)
	}
}

func TestStateTokenStoreRefusesADuplicateMint(t *testing.T) {
	store := newTestStore(t)
	first, err := store.Mint("pixel-8", "Pixel 8")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Mint("pixel-8", "Pixel 8"); !errors.Is(err, ErrNodeExists) {
		t.Fatalf("expected ErrNodeExists, got %v", err)
	}
	if token, _ := store.Lookup("pixel-8"); token != first {
		t.Fatal("a refused mint rotated the existing token")
	}
}

func TestTokenStoreImplementationsAreInterchangeable(t *testing.T) {
	stores := []struct {
		name     string
		store    TokenStore
		mintable bool
	}{
		{name: "server mode", store: NewStaticTokenStore(map[string]string{"phone-a": "token-a"})},
		{name: "personal mode", store: newTestStore(t), mintable: true},
	}
	for _, test := range stores {
		t.Run(test.name, func(t *testing.T) {
			if _, ok := test.store.Lookup("phone-z"); ok {
				t.Fatal("an unknown node has a token")
			}
			token, err := test.store.Mint("phone-z", "Pixel 8")
			switch {
			case test.mintable && err != nil:
				t.Fatalf("mint: %v", err)
			case !test.mintable && !errors.Is(err, ErrTokensImmutable):
				t.Fatalf("expected ErrTokensImmutable, got %v", err)
			}
			if minted, ok := test.store.Lookup("phone-z"); ok != test.mintable || (test.mintable && minted != token) {
				t.Fatalf("Lookup after Mint returned %q, %v", minted, ok)
			}
		})
	}
}
