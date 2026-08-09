package proxy

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"io"
	"log/slog"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/CesarPetrescu/pocket-exit/backend/internal/circuit"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/config"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/model"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/nodes"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/protocol"
)

func TestParseSelector(t *testing.T) {
	selected, base, err := parseSelector("proxy@s24u!cellular")
	if err != nil {
		t.Fatal(err)
	}
	if base != "proxy" || selected.nodeID != "s24u" || selected.policy != model.PolicyCellularOnly {
		t.Fatalf("unexpected selector: %#v base=%s", selected, base)
	}
}

func TestSOCKSTCPRoundTrip(t *testing.T) {
	cfg := config.Config{
		SOCKSUsername:            "proxy",
		SOCKSPassword:            "secret",
		OpenTimeout:              time.Second,
		IdleTimeout:              time.Minute,
		AllowPrivateDestinations: false,
		UDPPortStart:             22000,
		UDPPortEnd:               22001,
	}
	registry := nodes.NewRegistry(time.Minute, 10)
	_, _ = registry.Heartbeat(model.HeartbeatRequest{
		NodeID:        "phone",
		ControlPolicy: model.PolicyAuto,
		ExitPolicy:    model.PolicyCellularPreferred,
		Cellular:      model.NetworkState{Available: true, Validated: true},
	})
	manager := circuit.NewManager()
	server := New(cfg, registry, manager, slog.New(slog.NewTextHandler(io.Discard, nil)))

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = server.Serve(ctx, listener) }()

	go func() {
		command, ok, err := registry.NextCommand(context.Background(), "phone")
		if err != nil || !ok {
			return
		}
		c, exists := manager.Get(command.CircuitID)
		if !exists {
			return
		}
		c.MarkOpen()
		buffer := make([]byte, 4)
		if _, err := io.ReadFull(readerFunc(c.ReadDown), buffer); err != nil {
			return
		}
		_, _ = c.WriteUp([]byte(strings.ToUpper(string(buffer))))
	}()

	connection, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	reader := bufio.NewReader(connection)

	authenticateSOCKSClient(t, connection, reader)

	host := "example.com"
	request := []byte{0x05, 0x01, 0x00, 0x03, byte(len(host))}
	request = append(request, []byte(host)...)
	port := make([]byte, 2)
	binary.BigEndian.PutUint16(port, 443)
	request = append(request, port...)
	_, _ = connection.Write(request)

	replyHeader := make([]byte, 4)
	if _, err := io.ReadFull(reader, replyHeader); err != nil {
		t.Fatal(err)
	}
	if replyHeader[1] != 0x00 {
		t.Fatalf("SOCKS reply failed: %v", replyHeader)
	}
	if _, err := readAddress(io.MultiReader(bytes.NewReader([]byte{replyHeader[3]}), reader)); err != nil {
		t.Fatal(err)
	}

	_, _ = connection.Write([]byte("ping"))
	assertBytes(t, reader, []byte("PING"))
}

func TestSOCKSUDPRoundTrip(t *testing.T) {
	udpProbe, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	udpPort := udpProbe.LocalAddr().(*net.UDPAddr).Port
	_ = udpProbe.Close()

	cfg := config.Config{
		SOCKSUsername:            "proxy",
		SOCKSPassword:            "secret",
		PublicProxyHost:          "127.0.0.1",
		UDPBindHost:              "127.0.0.1",
		OpenTimeout:              time.Second,
		IdleTimeout:              time.Minute,
		AllowPrivateDestinations: false,
		UDPPortStart:             udpPort,
		UDPPortEnd:               udpPort,
	}
	registry := nodes.NewRegistry(time.Minute, 10)
	_, _ = registry.Heartbeat(model.HeartbeatRequest{
		NodeID:        "phone",
		ControlPolicy: model.PolicyAuto,
		ExitPolicy:    model.PolicyCellularPreferred,
		Cellular:      model.NetworkState{Available: true, Validated: true},
	})
	manager := circuit.NewManager()
	server := New(cfg, registry, manager, slog.New(slog.NewTextHandler(io.Discard, nil)))

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = server.Serve(ctx, listener) }()

	agentDone := make(chan struct{})
	go func() {
		defer close(agentDone)
		command, ok, err := registry.NextCommand(context.Background(), "phone")
		if err != nil || !ok || command.Type != model.CommandOpenUDP {
			return
		}
		c, exists := manager.Get(command.CircuitID)
		if !exists {
			return
		}
		c.MarkOpen()
		payload, err := protocol.ReadDatagram(readerFunc(c.ReadDown))
		if err != nil {
			return
		}
		_ = protocol.WriteDatagram(writerFunc(c.WriteUp), []byte(strings.ToUpper(string(payload))))
	}()

	control, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer control.Close()
	if err := control.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(control)
	authenticateSOCKSClient(t, control, reader)

	// RFC 1928 clients commonly send 0.0.0.0:0 for UDP ASSOCIATE.
	_, _ = control.Write([]byte{0x05, commandUDPAssociate, 0x00, addressIPv4, 0, 0, 0, 0, 0, 0})
	replyHeader := make([]byte, 4)
	if _, err := io.ReadFull(reader, replyHeader); err != nil {
		t.Fatal(err)
	}
	if replyHeader[1] != replySuccess {
		t.Fatalf("UDP ASSOCIATE failed: %v", replyHeader)
	}
	relay, err := readAddress(io.MultiReader(bytes.NewReader([]byte{replyHeader[3]}), reader))
	if err != nil {
		t.Fatal(err)
	}
	if relay.port != udpPort {
		t.Fatalf("expected relay port %d, got %d", udpPort, relay.port)
	}

	udpClient, err := net.DialUDP("udp", nil, &net.UDPAddr{IP: net.ParseIP(relay.host), Port: relay.port})
	if err != nil {
		t.Fatal(err)
	}
	defer udpClient.Close()
	_ = udpClient.SetDeadline(time.Now().Add(3 * time.Second))

	target := socksAddress{typeCode: addressDomain, host: "example.com", port: 53}
	encodedTarget, err := encodeAddress(target)
	if err != nil {
		t.Fatal(err)
	}
	packet := append([]byte{0x00, 0x00, 0x00}, encodedTarget...)
	packet = append(packet, []byte("ping")...)
	if _, err := udpClient.Write(packet); err != nil {
		t.Fatal(err)
	}

	response := make([]byte, 2048)
	n, err := udpClient.Read(response)
	if err != nil {
		t.Fatal(err)
	}
	responseTarget, payload, err := parseUDPRequest(response[:n])
	if err != nil {
		t.Fatal(err)
	}
	if responseTarget.host != target.host || responseTarget.port != target.port {
		t.Fatalf("unexpected response target: %#v", responseTarget)
	}
	if string(payload) != "PING" {
		t.Fatalf("expected PING, got %q", payload)
	}

	select {
	case <-agentDone:
	case <-time.After(2 * time.Second):
		t.Fatal("simulated agent did not complete")
	}
}

func authenticateSOCKSClient(t *testing.T, connection net.Conn, reader *bufio.Reader) {
	t.Helper()
	_, _ = connection.Write([]byte{0x05, 0x01, 0x02})
	assertBytes(t, reader, []byte{0x05, 0x02})
	_, _ = connection.Write(append(append([]byte{0x01, 0x05}, []byte("proxy")...), append([]byte{0x06}, []byte("secret")...)...))
	assertBytes(t, reader, []byte{0x01, 0x00})
}

func assertBytes(t *testing.T, reader io.Reader, expected []byte) {
	t.Helper()
	actual := make([]byte, len(expected))
	if _, err := io.ReadFull(reader, actual); err != nil {
		t.Fatal(err)
	}
	if string(actual) != string(expected) {
		t.Fatalf("expected %v, got %v", expected, actual)
	}
}
