package circuit

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/CesarPetrescu/pocket-exit/backend/internal/model"
)

var ErrNotFound = errors.New("circuit not found")
var ErrQuotaExceeded = errors.New("circuit byte quota exceeded")

type Circuit struct {
	id         string
	nodeID     string
	protocol   model.CircuitProtocol
	targetHost string
	targetPort int
	exitPolicy model.Policy
	createdAt  time.Time

	mu        sync.RWMutex
	status    model.CircuitStatus
	errorText string
	updatedAt time.Time

	downReader *io.PipeReader
	downWriter *io.PipeWriter
	upReader   *io.PipeReader
	upWriter   *io.PipeWriter
	downMu     sync.Mutex
	upMu       sync.Mutex

	ready     chan error
	readyOnce sync.Once
	done      chan struct{}
	closeOnce sync.Once

	bytesUp   atomic.Uint64
	bytesDown atomic.Uint64
	quotaUsed atomic.Uint64
	maxBytes  uint64
}

func newCircuit(nodeID string, protocol model.CircuitProtocol, host string, port int, policy model.Policy, maxBytes int64) (*Circuit, error) {
	id, err := randomID()
	if err != nil {
		return nil, err
	}
	downReader, downWriter := io.Pipe()
	upReader, upWriter := io.Pipe()
	now := time.Now().UTC()
	return &Circuit{
		id:         id,
		nodeID:     nodeID,
		protocol:   protocol,
		targetHost: host,
		targetPort: port,
		exitPolicy: policy,
		createdAt:  now,
		updatedAt:  now,
		status:     model.CircuitPending,
		downReader: downReader,
		downWriter: downWriter,
		upReader:   upReader,
		upWriter:   upWriter,
		ready:      make(chan error, 1),
		done:       make(chan struct{}),
		maxBytes:   uint64(max(0, maxBytes)),
	}, nil
}

func (c *Circuit) ID() string                      { return c.id }
func (c *Circuit) NodeID() string                  { return c.nodeID }
func (c *Circuit) Protocol() model.CircuitProtocol { return c.protocol }
func (c *Circuit) Done() <-chan struct{}           { return c.done }

func (c *Circuit) WaitReady(ctx context.Context) error {
	select {
	case err := <-c.ready:
		return err
	case <-c.done:
		return fmt.Errorf("circuit closed before becoming ready")
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (c *Circuit) MarkOpen() {
	c.mu.Lock()
	if c.status == model.CircuitPending {
		c.status = model.CircuitOpen
		c.updatedAt = time.Now().UTC()
	}
	c.mu.Unlock()
	c.readyOnce.Do(func() { c.ready <- nil })
}

func (c *Circuit) MarkFailed(err error) {
	if err == nil {
		err = errors.New("agent failed to open circuit")
	}
	c.mu.Lock()
	if c.status != model.CircuitClosed {
		c.status = model.CircuitFailed
		c.errorText = err.Error()
		c.updatedAt = time.Now().UTC()
	}
	c.mu.Unlock()
	c.readyOnce.Do(func() { c.ready <- err })
}

func (c *Circuit) WriteDown(payload []byte) (int, error) {
	c.downMu.Lock()
	defer c.downMu.Unlock()
	claimed := c.claimQuota(len(payload))
	if claimed == 0 && len(payload) > 0 {
		return 0, ErrQuotaExceeded
	}
	n, err := c.downWriter.Write(payload[:claimed])
	c.refundQuota(claimed - n)
	c.bytesDown.Add(uint64(n))
	c.touch()
	if err == nil && claimed < len(payload) {
		err = ErrQuotaExceeded
	}
	return n, err
}

func (c *Circuit) ReadDown(payload []byte) (int, error) {
	return c.downReader.Read(payload)
}

func (c *Circuit) CloseDown() error {
	return c.downWriter.Close()
}

func (c *Circuit) WriteUp(payload []byte) (int, error) {
	c.upMu.Lock()
	defer c.upMu.Unlock()
	claimed := c.claimQuota(len(payload))
	if claimed == 0 && len(payload) > 0 {
		return 0, ErrQuotaExceeded
	}
	n, err := c.upWriter.Write(payload[:claimed])
	c.refundQuota(claimed - n)
	c.bytesUp.Add(uint64(n))
	c.touch()
	if err == nil && claimed < len(payload) {
		err = ErrQuotaExceeded
	}
	return n, err
}

func (c *Circuit) claimQuota(requested int) int {
	if requested <= 0 || c.maxBytes == 0 {
		return requested
	}
	for {
		used := c.quotaUsed.Load()
		if used >= c.maxBytes {
			return 0
		}
		claimed := min(uint64(requested), c.maxBytes-used)
		if c.quotaUsed.CompareAndSwap(used, used+claimed) {
			return int(claimed)
		}
	}
}

func (c *Circuit) refundQuota(count int) {
	if count > 0 && c.maxBytes > 0 {
		c.quotaUsed.Add(^uint64(count - 1))
	}
}

func (c *Circuit) ReadUp(payload []byte) (int, error) {
	return c.upReader.Read(payload)
}

func (c *Circuit) CloseUp() error {
	return c.upWriter.Close()
}

func (c *Circuit) View() model.CircuitView {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return model.CircuitView{
		ID:         c.id,
		NodeID:     c.nodeID,
		Protocol:   c.protocol,
		TargetHost: c.targetHost,
		TargetPort: c.targetPort,
		ExitPolicy: c.exitPolicy,
		Status:     c.status,
		Error:      c.errorText,
		BytesUp:    c.bytesUp.Load(),
		BytesDown:  c.bytesDown.Load(),
		CreatedAt:  c.createdAt,
		UpdatedAt:  c.updatedAt,
	}
}

func (c *Circuit) Close(reason error) {
	c.closeOnce.Do(func() {
		if reason != nil {
			c.MarkFailed(reason)
		} else {
			c.readyOnce.Do(func() { c.ready <- errors.New("circuit closed") })
		}
		c.mu.Lock()
		c.status = model.CircuitClosed
		if reason != nil && c.errorText == "" {
			c.errorText = reason.Error()
		}
		c.updatedAt = time.Now().UTC()
		c.mu.Unlock()
		_ = c.downWriter.CloseWithError(reason)
		_ = c.downReader.CloseWithError(reason)
		_ = c.upWriter.CloseWithError(reason)
		_ = c.upReader.CloseWithError(reason)
		close(c.done)
	})
}

func (c *Circuit) touch() {
	c.mu.Lock()
	c.updatedAt = time.Now().UTC()
	c.mu.Unlock()
}

type Manager struct {
	mu       sync.RWMutex
	circuits map[string]*Circuit
}

func NewManager() *Manager {
	return &Manager{circuits: make(map[string]*Circuit)}
}

func (m *Manager) Create(nodeID string, protocol model.CircuitProtocol, host string, port int, policy model.Policy) (*Circuit, error) {
	return m.CreateLimited(nodeID, protocol, host, port, policy, 0)
}

// CreateLimited atomically enforces a server-side per-node active-circuit
// ceiling. A non-positive limit disables the ceiling for tests/internal use.
func (m *Manager) CreateLimited(nodeID string, protocol model.CircuitProtocol, host string, port int, policy model.Policy, limit int) (*Circuit, error) {
	return m.CreateLimitedWithQuota(nodeID, protocol, host, port, policy, limit, 0)
}

// CreateLimitedWithQuota atomically enforces concurrency and byte ceilings.
func (m *Manager) CreateLimitedWithQuota(nodeID string, protocol model.CircuitProtocol, host string, port int, policy model.Policy, limit int, maxBytes int64) (*Circuit, error) {
	circuit, err := newCircuit(nodeID, protocol, host, port, policy, maxBytes)
	if err != nil {
		return nil, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if limit > 0 {
		active := 0
		for _, existing := range m.circuits {
			view := existing.View()
			if view.NodeID == nodeID && (view.Status == model.CircuitPending || view.Status == model.CircuitOpen) {
				active++
			}
		}
		if active >= limit {
			circuit.Close(fmt.Errorf("node %s reached its circuit limit", nodeID))
			return nil, fmt.Errorf("node %s reached its circuit limit", nodeID)
		}
	}
	m.circuits[circuit.id] = circuit
	return circuit, nil
}

func (m *Manager) Get(id string) (*Circuit, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	circuit, ok := m.circuits[id]
	return circuit, ok
}

func (m *Manager) List() []model.CircuitView {
	m.mu.RLock()
	result := make([]model.CircuitView, 0, len(m.circuits))
	for _, circuit := range m.circuits {
		result = append(result, circuit.View())
	}
	m.mu.RUnlock()
	sort.Slice(result, func(i, j int) bool { return result[i].CreatedAt.After(result[j].CreatedAt) })
	return result
}

func (m *Manager) Close(id string, reason error) error {
	m.mu.RLock()
	circuit, ok := m.circuits[id]
	m.mu.RUnlock()
	if !ok {
		return ErrNotFound
	}
	circuit.Close(reason)
	return nil
}

func (m *Manager) Delete(id string) {
	m.mu.Lock()
	delete(m.circuits, id)
	m.mu.Unlock()
}

func (m *Manager) Prune(closedOlderThan time.Duration) int {
	cutoff := time.Now().Add(-closedOlderThan)
	removed := 0
	m.mu.Lock()
	for id, circuit := range m.circuits {
		view := circuit.View()
		if view.Status == model.CircuitClosed && view.UpdatedAt.Before(cutoff) {
			delete(m.circuits, id)
			removed++
		}
	}
	m.mu.Unlock()
	return removed
}

func randomID() (string, error) {
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		return "", fmt.Errorf("generate circuit ID: %w", err)
	}
	return hex.EncodeToString(value[:]), nil
}
