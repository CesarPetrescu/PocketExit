package protocol

import (
	"bytes"
	"testing"
)

func TestDatagramRoundTrip(t *testing.T) {
	var buffer bytes.Buffer
	payload := []byte("hello over udp")
	if err := WriteDatagram(&buffer, payload); err != nil {
		t.Fatal(err)
	}
	got, err := ReadDatagram(&buffer)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("expected %q, got %q", payload, got)
	}
}
