package nodes

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/CesarPetrescu/pocket-exit/backend/internal/model"
)

func TestChooseCellularNode(t *testing.T) {
	registry := NewRegistry(time.Minute, 10)
	_, err := registry.Heartbeat(model.HeartbeatRequest{
		NodeID:        "phone-a",
		ControlPolicy: model.PolicyAuto,
		ExitPolicy:    model.PolicyCellularPreferred,
		WiFi:          model.NetworkState{Available: true, Validated: true},
		Cellular:      model.NetworkState{Available: true, Validated: true},
	})
	if err != nil {
		t.Fatal(err)
	}

	node, err := registry.Choose("", model.PolicyCellularOnly)
	if err != nil {
		t.Fatal(err)
	}
	if node.NodeID != "phone-a" {
		t.Fatalf("expected phone-a, got %s", node.NodeID)
	}
}

func TestUpdateRollsBackWhenCommandQueueIsFull(t *testing.T) {
	registry := NewRegistry(time.Minute, 10)
	_, err := registry.Heartbeat(model.HeartbeatRequest{
		NodeID:        "phone-a",
		ControlPolicy: model.PolicyAuto,
		ExitPolicy:    model.PolicyCellularPreferred,
	})
	if err != nil {
		t.Fatal(err)
	}
	for index := 0; index < 256; index++ {
		if err := registry.QueueCommand(context.Background(), "phone-a", model.Command{
			Type:      model.CommandClose,
			CircuitID: strings.Repeat("x", index%3+1),
		}); err != nil {
			t.Fatalf("fill command queue at %d: %v", index, err)
		}
	}

	policy := model.PolicyWiFiOnly
	node, err := registry.Update("phone-a", nil, nil, &policy)
	if err == nil {
		t.Fatal("expected a full command queue error")
	}
	if node.ExitPolicy != model.PolicyCellularPreferred {
		t.Fatalf("returned state was not rolled back: %s", node.ExitPolicy)
	}
	stored, ok := registry.Get("phone-a")
	if !ok || stored.ExitPolicy != model.PolicyCellularPreferred {
		t.Fatalf("stored state was not rolled back: %+v", stored)
	}
}

func TestCommandRoundTrip(t *testing.T) {
	registry := NewRegistry(time.Minute, 10)
	_, _ = registry.Heartbeat(model.HeartbeatRequest{NodeID: "phone-a"})
	command := model.Command{Type: model.CommandOpenTCP, CircuitID: "abc"}
	if err := registry.QueueCommand(context.Background(), "phone-a", command); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	got, ok, err := registry.NextCommand(ctx, "phone-a")
	if err != nil || !ok {
		t.Fatalf("next command: ok=%v err=%v", ok, err)
	}
	if got.CircuitID != command.CircuitID {
		t.Fatalf("expected %s, got %s", command.CircuitID, got.CircuitID)
	}
}

func TestRemoveDropsTheRecordSoARepairStartsClean(t *testing.T) {
	registry := NewRegistry(time.Minute, 8)
	if _, err := registry.Heartbeat(model.HeartbeatRequest{NodeID: "pixel-8", DeviceName: "Pixel 8"}); err != nil {
		t.Fatal(err)
	}
	if _, ok := registry.Get("pixel-8"); !ok {
		t.Fatal("the node should be registered after a heartbeat")
	}

	if err := registry.Remove("pixel-8"); err != nil {
		t.Fatal(err)
	}
	if _, ok := registry.Get("pixel-8"); ok {
		t.Fatal("the record survived Remove")
	}
	if len(registry.List()) != 0 {
		t.Fatalf("List still reports %d nodes", len(registry.List()))
	}
	if err := registry.Remove("pixel-8"); !errors.Is(err, ErrNodeNotFound) {
		t.Fatalf("removing twice returned %v, want ErrNodeNotFound", err)
	}

	// Re-pairing the same id must land on a usable node. Disabling instead of
	// removing left Enabled=false behind, so the phone paired and then silently
	// refused every circuit.
	node, err := registry.Heartbeat(model.HeartbeatRequest{NodeID: "pixel-8", DeviceName: "Pixel 8"})
	if err != nil {
		t.Fatal(err)
	}
	if !node.Enabled {
		t.Fatal("a re-paired node came back disabled")
	}
}

func TestRemoveDiscardsQueuedCommands(t *testing.T) {
	registry := NewRegistry(time.Minute, 8)
	if _, err := registry.Heartbeat(model.HeartbeatRequest{NodeID: "pixel-8"}); err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	if err := registry.QueueCommand(ctx, "pixel-8", model.Command{Type: model.CommandClose}); err != nil {
		t.Fatal(err)
	}
	if err := registry.Remove("pixel-8"); err != nil {
		t.Fatal(err)
	}
	if err := registry.QueueCommand(ctx, "pixel-8", model.Command{Type: model.CommandClose}); !errors.Is(err, ErrNodeNotFound) {
		t.Fatalf("queueing to a removed node returned %v, want ErrNodeNotFound", err)
	}
}
