package httpapi

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/CesarPetrescu/pocket-exit/backend/internal/circuit"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/config"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/model"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/nodes"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/personal"
)

const testCertPin = "0uZ9nBRTn0hM1s5Vd4xJ1cV0v2Q3s4T5u6W7x8Y9z0A"

// newPersonalTestServer starts a personal-mode API backed by a real state
// directory, so the tests exercise the same token store the CLI uses.
func newPersonalTestServer(t *testing.T, customise func(*config.Config)) (*httptest.Server, *personal.Store) {
	t.Helper()
	directory := t.TempDir()
	// The state directory holds every secret, so the store refuses to open one
	// that is group or other readable.
	if err := os.Chmod(directory, 0o700); err != nil {
		t.Fatal(err)
	}
	store, err := personal.Open(directory)
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.Config{
		Mode:             config.ModePersonal,
		AdminToken:       store.AdminToken(),
		SOCKSAddr:        "127.0.0.1:1080",
		SOCKSUsername:    store.SOCKSUsername(),
		SOCKSPassword:    store.SOCKSPassword(),
		AgentTokens:      map[string]string{},
		CommandWait:      100 * time.Millisecond,
		NodeOfflineAfter: time.Minute,
		ServerURL:        "https://192.168.1.50:8443",
		ServerName:       "test-laptop",
		CertPin:          testCertPin,
	}
	if customise != nil {
		customise(&cfg)
	}
	server := httptest.NewServer(NewPersonal(
		cfg,
		nodes.NewRegistry(time.Minute, 10),
		circuit.NewManager(),
		slog.New(slog.NewTextHandler(io.Discard, nil)),
		store,
		personal.NewPairing(),
	).Handler())
	t.Cleanup(server.Close)
	return server, store
}

func personalRequest(t *testing.T, method, target, token string, body any) *http.Response {
	t.Helper()
	var payload io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		payload = bytes.NewReader(encoded)
	}
	request, err := http.NewRequest(method, target, payload)
	if err != nil {
		t.Fatal(err)
	}
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	return response
}

func decodeBody(t *testing.T, response *http.Response, destination any) {
	t.Helper()
	defer response.Body.Close()
	if err := json.NewDecoder(response.Body).Decode(destination); err != nil {
		t.Fatal(err)
	}
}

func mintPairingCodeForTest(t *testing.T, server *httptest.Server, adminToken string) pairingResponse {
	t.Helper()
	response := personalRequest(t, http.MethodPost, server.URL+"/api/v1/pairing", adminToken, nil)
	if response.StatusCode != http.StatusOK {
		response.Body.Close()
		t.Fatalf("mint pairing status %d", response.StatusCode)
	}
	var payload pairingResponse
	decodeBody(t, response, &payload)
	return payload
}

func heartbeatStatus(t *testing.T, server *httptest.Server, nodeID, token string) int {
	t.Helper()
	response := personalRequest(t, http.MethodPost, server.URL+"/agent/v1/heartbeat", token, model.HeartbeatRequest{
		NodeID:        nodeID,
		ControlPolicy: model.PolicyAuto,
		ExitPolicy:    model.PolicyCellularPreferred,
		Cellular:      model.NetworkState{Available: true, Validated: true},
	})
	response.Body.Close()
	return response.StatusCode
}

func TestPersonalClaimMintsAWorkingToken(t *testing.T) {
	server, store := newPersonalTestServer(t, nil)
	pairing := mintPairingCodeForTest(t, server, store.AdminToken())
	if !pairing.Active || len(pairing.Code) != 9 || pairing.Code[4] != '-' {
		t.Fatalf("unexpected pairing payload %+v", pairing)
	}
	if pairing.Fingerprint != testCertPin || pairing.ServerURL != "https://192.168.1.50:8443" {
		t.Fatalf("unexpected pairing payload %+v", pairing)
	}
	if !strings.HasPrefix(pairing.QRSVG, "<svg") || !strings.Contains(pairing.QRSVG, "<path") {
		t.Fatal("pairing response did not contain an SVG QR code")
	}
	parsed, err := url.Parse(pairing.URI)
	if err != nil {
		t.Fatal(err)
	}
	query := parsed.Query()
	if parsed.Scheme != "pocketexit" || parsed.Host != "configure" || query.Get("v") != "2" ||
		query.Get("server") != "https://192.168.1.50:8443" || query.Get("fp") != testCertPin ||
		query.Get("name") != "test-laptop" || query.Get("pair") != strings.ReplaceAll(pairing.Code, "-", "") ||
		query.Has("node") || query.Has("token") {
		t.Fatalf("unexpected onboarding URI %q", pairing.URI)
	}

	response := personalRequest(t, http.MethodPost, server.URL+"/pair/v1/claim", "", claimRequest{
		Code:       pairing.Code,
		DeviceName: "Pixel 8",
	})
	if response.StatusCode != http.StatusOK {
		response.Body.Close()
		t.Fatalf("claim status %d", response.StatusCode)
	}
	var claimed claimResponse
	decodeBody(t, response, &claimed)
	if !strings.HasPrefix(claimed.NodeID, "pixel-8-") || len(claimed.AgentToken) < 16 {
		t.Fatalf("unexpected claim response %+v", claimed)
	}
	if claimed.ServerURL != "https://192.168.1.50:8443" ||
		claimed.SOCKS != (socksHint{Host: "127.0.0.1", Port: 1080, Username: "proxy"}) {
		t.Fatalf("unexpected claim response %+v", claimed)
	}
	if credential, ok := store.GetNode(claimed.NodeID); !ok || credential.DeviceName != "Pixel 8" {
		t.Fatalf("node %q was not stored", claimed.NodeID)
	}
	if status := heartbeatStatus(t, server, claimed.NodeID, claimed.AgentToken); status != http.StatusOK {
		t.Fatalf("heartbeat with the minted token returned %d", status)
	}
	if status := heartbeatStatus(t, server, claimed.NodeID, "not-the-minted-token"); status != http.StatusUnauthorized {
		t.Fatalf("heartbeat with a wrong token returned %d", status)
	}

	// The code is single use, so replaying it must not mint a second node.
	replay := personalRequest(t, http.MethodPost, server.URL+"/pair/v1/claim", "", claimRequest{
		Code:       pairing.Code,
		DeviceName: "Pixel 8",
	})
	replay.Body.Close()
	if replay.StatusCode != http.StatusUnauthorized {
		t.Fatalf("replayed claim status %d", replay.StatusCode)
	}
	if nodes := store.ListNodes(); len(nodes) != 1 {
		t.Fatalf("expected one paired node, got %d", len(nodes))
	}
}

func TestPersonalClaimHonoursARequestedNodeID(t *testing.T) {
	server, store := newPersonalTestServer(t, nil)
	pairing := mintPairingCodeForTest(t, server, store.AdminToken())
	response := personalRequest(t, http.MethodPost, server.URL+"/pair/v1/claim", "", claimRequest{
		Code:       strings.ToLower(pairing.Code),
		DeviceName: "Pixel 8",
		// The sanitiser keeps [A-Za-z0-9._-] and drops the rest.
		NodeID: "pixel/8@desk",
	})
	if response.StatusCode != http.StatusOK {
		response.Body.Close()
		t.Fatalf("claim status %d", response.StatusCode)
	}
	var claimed claimResponse
	decodeBody(t, response, &claimed)
	if claimed.NodeID != "pixel8desk" {
		t.Fatalf("unexpected node ID %q", claimed.NodeID)
	}
}

func TestPersonalClaimRejectsBadRequests(t *testing.T) {
	tests := []struct {
		name     string
		mint     bool
		body     claimRequest
		expected int
	}{
		{name: "no active code", body: claimRequest{Code: "A1B2-C3D4", DeviceName: "Pixel 8"}, expected: http.StatusUnauthorized},
		{name: "wrong code", mint: true, body: claimRequest{Code: "A1B2-C3D4", DeviceName: "Pixel 8"}, expected: http.StatusUnauthorized},
		{name: "missing device name", mint: true, body: claimRequest{Code: "A1B2-C3D4"}, expected: http.StatusBadRequest},
		{
			name:     "oversized device name",
			mint:     true,
			body:     claimRequest{Code: "A1B2-C3D4", DeviceName: strings.Repeat("n", 65)},
			expected: http.StatusBadRequest,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			server, store := newPersonalTestServer(t, nil)
			body := test.body
			if test.mint {
				minted := mintPairingCodeForTest(t, server, store.AdminToken())
				// Keep the submitted code wrong on purpose: flip the first
				// character of the live code onto a different symbol.
				if body.Code != "" && strings.EqualFold(body.Code, minted.Code) {
					t.Fatalf("test would submit the live code %q", minted.Code)
				}
			}
			response := personalRequest(t, http.MethodPost, server.URL+"/pair/v1/claim", "", body)
			response.Body.Close()
			if response.StatusCode != test.expected {
				t.Fatalf("claim status %d, expected %d", response.StatusCode, test.expected)
			}
			if len(store.ListNodes()) != 0 {
				t.Fatal("a rejected claim registered a node")
			}
		})
	}
}

func TestPersonalClaimRejectsACancelledCode(t *testing.T) {
	server, store := newPersonalTestServer(t, nil)
	pairing := mintPairingCodeForTest(t, server, store.AdminToken())
	cancelled := personalRequest(t, http.MethodDelete, server.URL+"/api/v1/pairing", store.AdminToken(), nil)
	cancelled.Body.Close()
	if cancelled.StatusCode != http.StatusNoContent {
		t.Fatalf("cancel status %d", cancelled.StatusCode)
	}
	status := personalRequest(t, http.MethodGet, server.URL+"/api/v1/pairing", store.AdminToken(), nil)
	var payload pairingResponse
	decodeBody(t, status, &payload)
	if payload.Active || payload.Code != "" || payload.URI != "" || payload.QRSVG != "" {
		t.Fatalf("expected an inactive pairing payload, got %+v", payload)
	}
	response := personalRequest(t, http.MethodPost, server.URL+"/pair/v1/claim", "", claimRequest{
		Code:       pairing.Code,
		DeviceName: "Pixel 8",
	})
	response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("claim after cancel status %d", response.StatusCode)
	}
}

func TestClaimStatusMapsPairingFailures(t *testing.T) {
	for _, err := range []error{personal.ErrNoPairingCode, personal.ErrPairingCodeExpired, personal.ErrPairingCodeInvalid} {
		if status := claimStatus(err); status != http.StatusUnauthorized {
			t.Fatalf("%v mapped to %d, expected 401", err, status)
		}
	}
	if status := claimStatus(personal.ErrPairingRateLimited); status != http.StatusTooManyRequests {
		t.Fatalf("rate limit mapped to %d, expected 429", status)
	}
}

func TestPersonalClaimIsRateLimited(t *testing.T) {
	server, store := newPersonalTestServer(t, nil)
	mintPairingCodeForTest(t, server, store.AdminToken())
	for attempt := 1; attempt <= 10; attempt++ {
		response := personalRequest(t, http.MethodPost, server.URL+"/pair/v1/claim", "", claimRequest{
			Code:       "Z9Y8-X7W6",
			DeviceName: "Pixel 8",
		})
		response.Body.Close()
		if response.StatusCode != http.StatusUnauthorized {
			t.Fatalf("attempt %d status %d, expected 401", attempt, response.StatusCode)
		}
	}
	response := personalRequest(t, http.MethodPost, server.URL+"/pair/v1/claim", "", claimRequest{
		Code:       "Z9Y8-X7W6",
		DeviceName: "Pixel 8",
	})
	response.Body.Close()
	if response.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("eleventh attempt status %d, expected 429", response.StatusCode)
	}
}

func TestPersonalPairingEndpointsRequireAdmin(t *testing.T) {
	server, store := newPersonalTestServer(t, nil)
	for _, method := range []string{http.MethodGet, http.MethodPost, http.MethodDelete} {
		for _, token := range []string{"", "not-the-admin-token"} {
			response := personalRequest(t, method, server.URL+"/api/v1/pairing", token, nil)
			response.Body.Close()
			if response.StatusCode != http.StatusUnauthorized {
				t.Fatalf("%s /api/v1/pairing with token %q returned %d", method, token, response.StatusCode)
			}
		}
	}
	status := personalRequest(t, http.MethodGet, server.URL+"/api/v1/pairing", store.AdminToken(), nil)
	var payload pairingResponse
	decodeBody(t, status, &payload)
	if payload.Active {
		t.Fatal("a rejected mint attempt created a code")
	}
}

func TestPersonalDeleteNodeRevokesTheToken(t *testing.T) {
	server, store := newPersonalTestServer(t, nil)
	pairing := mintPairingCodeForTest(t, server, store.AdminToken())
	response := personalRequest(t, http.MethodPost, server.URL+"/pair/v1/claim", "", claimRequest{
		Code:       pairing.Code,
		DeviceName: "Pixel 8",
	})
	var claimed claimResponse
	decodeBody(t, response, &claimed)
	if status := heartbeatStatus(t, server, claimed.NodeID, claimed.AgentToken); status != http.StatusOK {
		t.Fatalf("heartbeat before unpairing returned %d", status)
	}

	deleted := personalRequest(t, http.MethodDelete, server.URL+"/api/v1/nodes/"+claimed.NodeID, store.AdminToken(), nil)
	deleted.Body.Close()
	if deleted.StatusCode != http.StatusNoContent {
		t.Fatalf("delete status %d", deleted.StatusCode)
	}
	if status := heartbeatStatus(t, server, claimed.NodeID, claimed.AgentToken); status != http.StatusUnauthorized {
		t.Fatalf("heartbeat after unpairing returned %d", status)
	}
	if _, ok := store.GetNode(claimed.NodeID); ok {
		t.Fatal("the unpaired node is still in the state directory")
	}

	missing := personalRequest(t, http.MethodDelete, server.URL+"/api/v1/nodes/"+claimed.NodeID, store.AdminToken(), nil)
	missing.Body.Close()
	if missing.StatusCode != http.StatusNotFound {
		t.Fatalf("delete of an unknown node returned %d", missing.StatusCode)
	}
	unauthorised := personalRequest(t, http.MethodDelete, server.URL+"/api/v1/nodes/phone", "", nil)
	unauthorised.Body.Close()
	if unauthorised.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthenticated delete returned %d", unauthorised.StatusCode)
	}
}

func TestServerModeRejectsNodeDeletionAndPairing(t *testing.T) {
	cfg := config.Config{
		Mode:             config.ModeServer,
		AdminToken:       "admin-test-token-2026",
		AgentTokens:      map[string]string{"phone": "agent-test-token-2026"},
		PublicProxyHost:  "proxy.example.com",
		CommandWait:      100 * time.Millisecond,
		NodeOfflineAfter: time.Minute,
	}
	server := httptest.NewServer(New(
		cfg,
		nodes.NewRegistry(time.Minute, 10),
		circuit.NewManager(),
		slog.New(slog.NewTextHandler(io.Discard, nil)),
	).Handler())
	defer server.Close()

	deleted := personalRequest(t, http.MethodDelete, server.URL+"/api/v1/nodes/phone", "admin-test-token-2026", nil)
	deleted.Body.Close()
	if deleted.StatusCode != http.StatusConflict {
		t.Fatalf("delete in server mode returned %d, expected 409", deleted.StatusCode)
	}
	// The pairing surface does not exist in server mode: nginx serves the
	// dashboard and AGENT_TOKENS_JSON holds the tokens.
	for _, target := range []string{"/api/v1/pairing", "/pair/v1/claim", "/index.html"} {
		response := personalRequest(t, http.MethodGet, server.URL+target, "admin-test-token-2026", nil)
		response.Body.Close()
		if response.StatusCode != http.StatusNotFound && response.StatusCode != http.StatusMethodNotAllowed {
			t.Fatalf("GET %s in server mode returned %d", target, response.StatusCode)
		}
	}
	if status := heartbeatStatus(t, server, "phone", "agent-test-token-2026"); status != http.StatusOK {
		t.Fatalf("server-mode heartbeat returned %d", status)
	}
}

func TestPersonalDashboardServesFilesSafely(t *testing.T) {
	root := t.TempDir()
	frontend := filepath.Join(root, "frontend")
	if err := os.MkdirAll(filepath.Join(frontend, "assets"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(frontend, "index.html"), []byte("<h1>PocketExit</h1>"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "secret.txt"), []byte("admin-token-leak"), 0o600); err != nil {
		t.Fatal(err)
	}
	server, _ := newPersonalTestServer(t, func(cfg *config.Config) { cfg.FrontendDir = frontend })

	index := personalRequest(t, http.MethodGet, server.URL+"/", "", nil)
	body, err := io.ReadAll(index.Body)
	index.Body.Close()
	if err != nil {
		t.Fatal(err)
	}
	if index.StatusCode != http.StatusOK || !strings.Contains(string(body), "PocketExit") {
		t.Fatalf("index status %d body %q", index.StatusCode, body)
	}
	if policy := index.Header.Get("Content-Security-Policy"); !strings.Contains(policy, "default-src 'self'") {
		t.Fatalf("missing content security policy: %q", policy)
	}
	if index.Header.Get("X-Content-Type-Options") != "nosniff" {
		t.Fatal("missing nosniff header")
	}

	for _, target := range []string{"/../secret.txt", "/%2e%2e/secret.txt", "/assets/../../secret.txt", "/assets", "/missing.js"} {
		response := personalRequest(t, http.MethodGet, server.URL+target, "", nil)
		payload, err := io.ReadAll(response.Body)
		response.Body.Close()
		if err != nil {
			t.Fatal(err)
		}
		if response.StatusCode == http.StatusOK {
			t.Fatalf("GET %s returned 200", target)
		}
		if strings.Contains(string(payload), "admin-token-leak") {
			t.Fatalf("GET %s served a file outside the dashboard directory", target)
		}
	}

	// The API keeps precedence over the catch-all static route.
	health := personalRequest(t, http.MethodGet, server.URL+"/api/v1/health", "", nil)
	health.Body.Close()
	if health.StatusCode != http.StatusOK {
		t.Fatalf("health status %d", health.StatusCode)
	}
}

func TestPersonalOnboardingEmitsVersionTwo(t *testing.T) {
	server, store := newPersonalTestServer(t, nil)
	pairing := mintPairingCodeForTest(t, server, store.AdminToken())
	claim := personalRequest(t, http.MethodPost, server.URL+"/pair/v1/claim", "", claimRequest{
		Code:       pairing.Code,
		DeviceName: "Pixel 8",
	})
	var claimed claimResponse
	decodeBody(t, claim, &claimed)

	// Claiming consumed the code, so the node-scoped onboarding URI cannot be
	// built until a fresh one is minted.
	conflict := personalRequest(t, http.MethodGet, server.URL+"/api/v1/nodes/"+claimed.NodeID+"/onboarding", store.AdminToken(), nil)
	conflict.Body.Close()
	if conflict.StatusCode != http.StatusConflict {
		t.Fatalf("onboarding without a code returned %d", conflict.StatusCode)
	}

	refreshed := mintPairingCodeForTest(t, server, store.AdminToken())
	response := personalRequest(t, http.MethodGet, server.URL+"/api/v1/nodes/"+claimed.NodeID+"/onboarding", store.AdminToken(), nil)
	var payload map[string]string
	decodeBody(t, response, &payload)
	parsed, err := url.Parse(payload["onboarding_uri"])
	if err != nil {
		t.Fatal(err)
	}
	query := parsed.Query()
	if query.Get("v") != "2" || query.Get("pair") != strings.ReplaceAll(refreshed.Code, "-", "") ||
		query.Get("fp") != testCertPin || query.Has("token") {
		t.Fatalf("unexpected onboarding URI %q", payload["onboarding_uri"])
	}
	if !strings.HasPrefix(payload["qr_svg"], "<svg") {
		t.Fatal("onboarding response did not contain an SVG QR code")
	}
	unknown := personalRequest(t, http.MethodGet, server.URL+"/api/v1/nodes/nobody/onboarding", store.AdminToken(), nil)
	unknown.Body.Close()
	if unknown.StatusCode != http.StatusNotFound {
		t.Fatalf("onboarding for an unknown node returned %d", unknown.StatusCode)
	}
}

func TestSanitizeNodeIDAndSlug(t *testing.T) {
	tests := []struct{ input, sanitized, slug string }{
		{input: "Pixel 8", sanitized: "Pixel8", slug: "pixel-8"},
		{input: "  ../etc/passwd ", sanitized: "..etcpasswd", slug: "etc-passwd"},
		{input: "!!!", sanitized: "", slug: "node"},
		{input: strings.Repeat("x", 200), sanitized: strings.Repeat("x", 64), slug: strings.Repeat("x", 55)},
	}
	for _, test := range tests {
		if got := sanitizeNodeID(test.input); got != test.sanitized {
			t.Fatalf("sanitizeNodeID(%q) = %q, expected %q", test.input, got, test.sanitized)
		}
		if got := slugDeviceName(test.input); got != test.slug {
			t.Fatalf("slugDeviceName(%q) = %q, expected %q", test.input, got, test.slug)
		}
	}
}
