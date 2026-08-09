package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
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
