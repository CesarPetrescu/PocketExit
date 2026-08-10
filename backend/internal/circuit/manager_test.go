package circuit

import (
	"context"
	"errors"
	"io"
	"testing"
	"time"

	"github.com/CesarPetrescu/pocket-exit/backend/internal/model"
)

func TestCircuitBidirectionalData(t *testing.T) {
	manager := NewManager()
	c, err := manager.Create("phone", model.ProtocolTCP, "example.com", 443, model.PolicyCellularOnly)
	if err != nil {
		t.Fatal(err)
	}
	c.MarkOpen()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := c.WaitReady(ctx); err != nil {
		t.Fatal(err)
	}

	down := []byte("to-phone")
	go func() {
		_, _ = c.WriteDown(down)
	}()
	gotDown := make([]byte, len(down))
	if _, err := io.ReadFull(readerFunc(c.ReadDown), gotDown); err != nil {
		t.Fatal(err)
	}
	if string(gotDown) != string(down) {
		t.Fatalf("down mismatch: %q", gotDown)
	}

	up := []byte("to-client")
	go func() {
		_, _ = c.WriteUp(up)
	}()
	gotUp := make([]byte, len(up))
	if _, err := io.ReadFull(readerFunc(c.ReadUp), gotUp); err != nil {
		t.Fatal(err)
	}
	if string(gotUp) != string(up) {
		t.Fatalf("up mismatch: %q", gotUp)
	}
}

func TestCircuitByteQuota(t *testing.T) {
	manager := NewManager()
	c, err := manager.CreateLimitedWithQuota(
		"phone",
		model.ProtocolTCP,
		"example.com",
		443,
		model.PolicyCellularOnly,
		1,
		5,
	)
	if err != nil {
		t.Fatal(err)
	}
	type writeResult struct {
		count int
		err   error
	}
	result := make(chan writeResult, 1)
	go func() {
		count, writeErr := c.WriteDown([]byte("123456"))
		result <- writeResult{count: count, err: writeErr}
	}()
	payload := make([]byte, 5)
	if _, err := io.ReadFull(readerFunc(c.ReadDown), payload); err != nil {
		t.Fatal(err)
	}
	written := <-result
	if written.count != 5 || !errors.Is(written.err, ErrQuotaExceeded) {
		t.Fatalf("expected five bytes and quota error, got %d, %v", written.count, written.err)
	}
	if count, err := c.WriteUp([]byte("x")); count != 0 || !errors.Is(err, ErrQuotaExceeded) {
		t.Fatalf("expected exhausted shared quota, got %d, %v", count, err)
	}
}

type readerFunc func([]byte) (int, error)

func (f readerFunc) Read(payload []byte) (int, error) { return f(payload) }

func TestCreateLimitedEnforcesActivePerNodeLimit(t *testing.T) {
	manager := NewManager()
	first, err := manager.CreateLimited("phone", model.ProtocolTCP, "example.com", 443, model.PolicyCellularOnly, 1)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := manager.CreateLimited("phone", model.ProtocolTCP, "example.org", 443, model.PolicyCellularOnly, 1); err == nil {
		t.Fatal("expected active circuit limit to be enforced")
	}
	first.Close(nil)
	if _, err := manager.CreateLimited("phone", model.ProtocolTCP, "example.net", 443, model.PolicyCellularOnly, 1); err != nil {
		t.Fatalf("closed circuit should release active capacity: %v", err)
	}
	if _, err := manager.CreateLimited("other-phone", model.ProtocolTCP, "example.com", 443, model.PolicyCellularOnly, 1); err != nil {
		t.Fatalf("limit must be per node: %v", err)
	}
}
