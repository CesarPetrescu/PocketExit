package nodes

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/CesarPetrescu/pocket-exit/backend/internal/model"
)

var ErrNodeNotFound = errors.New("node not found")

type record struct {
	node     model.Node
	commands chan model.Command
}

type Registry struct {
	mu           sync.RWMutex
	records      map[string]*record
	offlineAfter time.Duration
	maxCircuits  int
}

func NewRegistry(offlineAfter time.Duration, maxCircuits int) *Registry {
	return &Registry{
		records:      make(map[string]*record),
		offlineAfter: offlineAfter,
		maxCircuits:  maxCircuits,
	}
}

func (r *Registry) Heartbeat(request model.HeartbeatRequest) (model.Node, error) {
	request.NodeID = strings.TrimSpace(request.NodeID)
	if request.NodeID == "" {
		return model.Node{}, fmt.Errorf("node_id is required")
	}
	if !request.ControlPolicy.Valid() {
		request.ControlPolicy = model.PolicyAuto
	}
	if !request.ExitPolicy.Valid() {
		request.ExitPolicy = model.PolicyCellularPreferred
	}

	now := time.Now().UTC()
	r.mu.Lock()
	defer r.mu.Unlock()

	rec, exists := r.records[request.NodeID]
	if !exists {
		rec = &record{
			node: model.Node{
				NodeID:  request.NodeID,
				Enabled: true,
			},
			commands: make(chan model.Command, 256),
		}
		r.records[request.NodeID] = rec
	}

	rec.node.DeviceName = request.DeviceName
	rec.node.AppVersion = request.AppVersion
	rec.node.Online = true
	rec.node.ControlPolicy = request.ControlPolicy
	rec.node.ExitPolicy = request.ExitPolicy
	rec.node.ActiveControlNetwork = request.ActiveControlNetwork
	rec.node.TransportProtocol = request.TransportProtocol
	rec.node.WiFi = request.WiFi
	rec.node.Cellular = request.Cellular
	rec.node.BatteryPercent = request.BatteryPercent
	rec.node.Charging = request.Charging
	rec.node.ActiveCircuits = request.ActiveCircuits
	rec.node.BytesUp = request.BytesUp
	rec.node.BytesDown = request.BytesDown
	rec.node.LastSeen = now

	return rec.node, nil
}

func (r *Registry) Authenticate(nodeID, token string, tokens map[string]string) bool {
	expected, ok := tokens[nodeID]
	return ok && expected != "" && constantTimeEqual(expected, token)
}

func (r *Registry) QueueCommand(ctx context.Context, nodeID string, command model.Command) error {
	r.mu.RLock()
	rec, ok := r.records[nodeID]
	r.mu.RUnlock()
	if !ok {
		return ErrNodeNotFound
	}

	select {
	case rec.commands <- command:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (r *Registry) NextCommand(ctx context.Context, nodeID string) (model.Command, bool, error) {
	r.mu.RLock()
	rec, ok := r.records[nodeID]
	r.mu.RUnlock()
	if !ok {
		return model.Command{}, false, ErrNodeNotFound
	}

	select {
	case command := <-rec.commands:
		return command, true, nil
	case <-ctx.Done():
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return model.Command{}, false, nil
		}
		return model.Command{}, false, ctx.Err()
	}
}

func (r *Registry) Get(nodeID string) (model.Node, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	rec, ok := r.records[nodeID]
	if !ok {
		return model.Node{}, false
	}
	node := rec.node
	node.Online = node.Enabled && time.Since(node.LastSeen) <= r.offlineAfter
	return node, true
}

func (r *Registry) List() []model.Node {
	now := time.Now()
	r.mu.RLock()
	result := make([]model.Node, 0, len(r.records))
	for _, rec := range r.records {
		node := rec.node
		node.Online = node.Enabled && now.Sub(node.LastSeen) <= r.offlineAfter
		result = append(result, node)
	}
	r.mu.RUnlock()

	sort.Slice(result, func(i, j int) bool { return result[i].NodeID < result[j].NodeID })
	return result
}

func (r *Registry) Update(nodeID string, enabled *bool, controlPolicy, exitPolicy *model.Policy) (model.Node, error) {
	if controlPolicy != nil && !controlPolicy.Valid() {
		return model.Node{}, fmt.Errorf("invalid control policy")
	}
	if exitPolicy != nil && !exitPolicy.Valid() {
		return model.Node{}, fmt.Errorf("invalid exit policy")
	}

	r.mu.Lock()
	defer r.mu.Unlock()

	rec, ok := r.records[nodeID]
	if !ok {
		return model.Node{}, ErrNodeNotFound
	}

	oldNode := rec.node
	if enabled != nil {
		rec.node.Enabled = *enabled
	}
	if controlPolicy != nil {
		rec.node.ControlPolicy = *controlPolicy
	}
	if exitPolicy != nil {
		rec.node.ExitPolicy = *exitPolicy
	}

	if controlPolicy != nil || exitPolicy != nil {
		command := model.Command{Type: model.CommandPolicyUpdate}
		if controlPolicy != nil {
			command.ControlPolicy = *controlPolicy
		}
		if exitPolicy != nil {
			command.ExitPolicy = *exitPolicy
		}
		select {
		case rec.commands <- command:
		default:
			// Keep the dashboard state and the remote-agent state transactional:
			// if the command cannot be delivered, restore the prior values.
			rec.node = oldNode
			return oldNode, fmt.Errorf("node command queue is full")
		}
	}
	return rec.node, nil
}

func (r *Registry) Choose(nodeID string, requestedPolicy model.Policy) (model.Node, error) {
	if nodeID != "" {
		node, ok := r.Get(nodeID)
		if !ok {
			return model.Node{}, ErrNodeNotFound
		}
		if err := r.usable(node, requestedPolicy); err != nil {
			return model.Node{}, err
		}
		return node, nil
	}

	candidates := r.List()
	var best *model.Node
	for index := range candidates {
		node := candidates[index]
		if r.usable(node, requestedPolicy) != nil {
			continue
		}
		if best == nil || node.ActiveCircuits < best.ActiveCircuits ||
			(node.ActiveCircuits == best.ActiveCircuits && node.LastSeen.After(best.LastSeen)) {
			copy := node
			best = &copy
		}
	}
	if best == nil {
		return model.Node{}, fmt.Errorf("no online node satisfies the requested exit policy")
	}
	return *best, nil
}

func (r *Registry) usable(node model.Node, policy model.Policy) error {
	if !node.Enabled || !node.Online {
		return fmt.Errorf("node %s is offline or disabled", node.NodeID)
	}
	if node.ActiveCircuits >= r.maxCircuits {
		return fmt.Errorf("node %s reached its circuit limit", node.NodeID)
	}
	if !policy.Valid() {
		policy = node.ExitPolicy
	}
	switch policy {
	case model.PolicyWiFiOnly:
		if !node.WiFi.Available || !node.WiFi.Validated {
			return fmt.Errorf("node %s has no validated Wi-Fi network", node.NodeID)
		}
	case model.PolicyCellularOnly:
		if !node.Cellular.Available || !node.Cellular.Validated {
			return fmt.Errorf("node %s has no validated cellular network", node.NodeID)
		}
	case model.PolicyWiFiPreferred, model.PolicyCellularPreferred, model.PolicyAuto:
		if !(node.WiFi.Available && node.WiFi.Validated) && !(node.Cellular.Available && node.Cellular.Validated) {
			return fmt.Errorf("node %s has no validated exit network", node.NodeID)
		}
	}
	return nil
}

func constantTimeEqual(left, right string) bool {
	if len(left) != len(right) {
		return false
	}
	var diff byte
	for index := 0; index < len(left); index++ {
		diff |= left[index] ^ right[index]
	}
	return diff == 0
}
