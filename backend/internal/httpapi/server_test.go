package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/CesarPetrescu/pocket-exit/backend/internal/circuit"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/config"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/model"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/nodes"
)

func TestHeartbeatAndControl(t *testing.T) {
	cfg := config.Config{
		AdminToken:       "admin",
		AgentTokens:      map[string]string{"phone": "agent"},
		CommandWait:      100 * time.Millisecond,
		NodeOfflineAfter: time.Minute,
	}
	registry := nodes.NewRegistry(time.Minute, 10)
	manager := circuit.NewManager()
	server := httptest.NewServer(New(cfg, registry, manager, slog.New(slog.NewTextHandler(io.Discard, nil))).Handler())
	defer server.Close()

	heartbeat := model.HeartbeatRequest{
		NodeID:        "phone",
		ControlPolicy: model.PolicyAuto,
		ExitPolicy:    model.PolicyCellularPreferred,
		Cellular:      model.NetworkState{Available: true, Validated: true},
	}
	body, _ := json.Marshal(heartbeat)
	request, _ := http.NewRequest(http.MethodPost, server.URL+"/agent/v1/heartbeat", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer agent")
	request.Header.Set("Content-Type", "application/json")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("heartbeat status %d", response.StatusCode)
	}

	if err := registry.QueueCommand(context.Background(), "phone", model.Command{Type: model.CommandOpenTCP, CircuitID: "c1"}); err != nil {
		t.Fatal(err)
	}
	request, _ = http.NewRequest(http.MethodGet, server.URL+"/agent/v1/control?node_id=phone", nil)
	request.Header.Set("Authorization", "Bearer agent")
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("control status %d", response.StatusCode)
	}
	var command model.Command
	if err := json.NewDecoder(response.Body).Decode(&command); err != nil {
		t.Fatal(err)
	}
	if command.CircuitID != "c1" {
		t.Fatalf("expected c1, got %s", command.CircuitID)
	}
}

func TestOnboardingQRCode(t *testing.T) {
	cfg := config.Config{
		AdminToken:      "admin-test-token-2026",
		AgentTokens:     map[string]string{"phone": "agent-test-token-2026"},
		PublicProxyHost: "proxy.example.com",
	}
	server := httptest.NewServer(New(
		cfg,
		nodes.NewRegistry(time.Minute, 10),
		circuit.NewManager(),
		slog.New(slog.NewTextHandler(io.Discard, nil)),
	).Handler())
	defer server.Close()
	request, _ := http.NewRequest(http.MethodGet, server.URL+"/api/v1/nodes/phone/onboarding", nil)
	request.Header.Set("Authorization", "Bearer admin-test-token-2026")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var payload map[string]string
	if err := json.NewDecoder(response.Body).Decode(&payload); err != nil {
		t.Fatal(err)
	}
	parsed, err := url.Parse(payload["onboarding_uri"])
	if err != nil {
		t.Fatal(err)
	}
	if parsed.Scheme != "pocketexit" || parsed.Host != "configure" ||
		parsed.Query().Get("server") != "https://proxy.example.com" ||
		parsed.Query().Get("node") != "phone" ||
		parsed.Query().Get("token") != "agent-test-token-2026" {
		t.Fatalf("unexpected onboarding URI %q", parsed)
	}
	if !strings.HasPrefix(payload["qr_svg"], "<svg") || !strings.Contains(payload["qr_svg"], "<path") {
		t.Fatal("response did not contain an SVG QR code")
	}
}
