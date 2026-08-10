package httpapi

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/CesarPetrescu/pocket-exit/backend/internal/circuit"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/config"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/model"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/nodes"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/proxy"
	"github.com/coder/websocket"
)

func TestSimulatedPhoneWebSocketLargeTransferAndReconnect(t *testing.T) {
	echoListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echoListener.Close()
	go serveEcho(echoListener)

	cfg := config.Config{
		AdminToken:               "admin-test-token-2026",
		AgentTokens:              map[string]string{"sim-phone": "agent-test-token-2026"},
		SOCKSUsername:            "proxy",
		SOCKSPassword:            "proxy-test-password-2026",
		CommandWait:              time.Second,
		NodeOfflineAfter:         time.Minute,
		OpenTimeout:              5 * time.Second,
		IdleTimeout:              time.Minute,
		MaxCircuitsPerNode:       8,
		AllowPrivateDestinations: true,
		UDPPortStart:             23000,
		UDPPortEnd:               23001,
	}
	registry := nodes.NewRegistry(time.Minute, 8)
	circuits := circuit.NewManager()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	api := httptest.NewServer(New(cfg, registry, circuits, logger).Handler())
	defer api.Close()
	postSimulatedHeartbeat(t, api.URL)

	socksListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer socksListener.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = proxy.New(cfg, registry, circuits, logger).Serve(ctx, socksListener) }()

	agentDone := make(chan error, 1)
	go func() {
		err := runSimulatedPhone(api.URL, echoListener.Addr().String(), 2, t.Logf)
		if err != nil {
			t.Logf("simulated phone: %v", err)
		}
		agentDone <- err
	}()

	payload := bytes.Repeat([]byte("PocketExit-WebSocket-"), 400_000)
	for attempt := 0; attempt < 2; attempt++ {
		actual := exchangeThroughSOCKS(t, socksListener.Addr().String(), echoListener.Addr().(*net.TCPAddr).Port, payload)
		if !bytes.Equal(actual, payload) {
			t.Fatalf("attempt %d returned %d incorrect bytes", attempt+1, len(actual))
		}
	}

	select {
	case err := <-agentDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("simulated phone did not finish")
	}
}

func postSimulatedHeartbeat(t *testing.T, baseURL string) {
	t.Helper()
	body, _ := json.Marshal(model.HeartbeatRequest{
		NodeID:        "sim-phone",
		DeviceName:    "Simulated Phone",
		ControlPolicy: model.PolicyAuto,
		ExitPolicy:    model.PolicyCellularPreferred,
		Cellular:      model.NetworkState{Available: true, Validated: true},
	})
	request, _ := http.NewRequest(http.MethodPost, baseURL+"/agent/v1/heartbeat", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer agent-test-token-2026")
	request.Header.Set("Content-Type", "application/json")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("heartbeat returned %s", response.Status)
	}
}

func runSimulatedPhone(baseURL, destination string, circuits int, logf func(string, ...any)) error {
	for attempt := 0; attempt < circuits; {
		logf("simulated phone waiting for command %d", attempt+1)
		command, err := nextSimulatedCommand(baseURL)
		if err != nil {
			return err
		}
		if command.Type == model.CommandClose {
			continue
		}
		attempt++
		logf("simulated phone received circuit %s", command.CircuitID)
		target, err := net.DialTimeout("tcp", destination, 3*time.Second)
		if err != nil {
			return err
		}
		header := http.Header{"Authorization": []string{"Bearer agent-test-token-2026"}}
		dialCtx, cancelDial := context.WithTimeout(context.Background(), 3*time.Second)
		socket, _, err := websocket.Dial(
			dialCtx,
			strings.Replace(baseURL, "http://", "ws://", 1)+fmt.Sprintf(
				"/agent/v1/circuits/%s/ws?node_id=sim-phone",
				command.CircuitID,
			),
			&websocket.DialOptions{HTTPHeader: header, Subprotocols: []string{circuitWebSocketProtocol}},
		)
		cancelDial()
		if err != nil {
			target.Close()
			return err
		}
		logf("simulated phone opened WebSocket %s", command.CircuitID)
		stream := websocket.NetConn(context.Background(), socket, websocket.MessageBinary)
		if err := postSimulatedStatus(baseURL, command.CircuitID, "connected"); err != nil {
			stream.Close()
			target.Close()
			return err
		}
		logf("simulated phone marked circuit connected %s", command.CircuitID)
		result := make(chan error, 2)
		go func() { _, copyErr := io.Copy(target, stream); result <- copyErr }()
		go func() { _, copyErr := io.Copy(stream, target); result <- copyErr }()
		<-result
		stream.Close()
		target.Close()
	}
	return nil
}

func nextSimulatedCommand(baseURL string) (model.Command, error) {
	for {
		request, _ := http.NewRequest(http.MethodGet, baseURL+"/agent/v1/control?node_id=sim-phone", nil)
		request.Header.Set("Authorization", "Bearer agent-test-token-2026")
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			return model.Command{}, err
		}
		if response.StatusCode == http.StatusNoContent {
			response.Body.Close()
			continue
		}
		if response.StatusCode != http.StatusOK {
			response.Body.Close()
			return model.Command{}, fmt.Errorf("control returned %s", response.Status)
		}
		var command model.Command
		err = json.NewDecoder(response.Body).Decode(&command)
		response.Body.Close()
		return command, err
	}
}

func postSimulatedStatus(baseURL, circuitID, status string) error {
	body := strings.NewReader(fmt.Sprintf(`{"node_id":"sim-phone","status":%q}`, status))
	request, _ := http.NewRequest(
		http.MethodPost,
		baseURL+"/agent/v1/circuits/"+circuitID+"/status",
		body,
	)
	request.Header.Set("Authorization", "Bearer agent-test-token-2026")
	request.Header.Set("Content-Type", "application/json")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusNoContent {
		return fmt.Errorf("status returned %s", response.Status)
	}
	return nil
}

func exchangeThroughSOCKS(t *testing.T, address string, destinationPort int, payload []byte) []byte {
	t.Helper()
	connection, err := net.DialTimeout("tcp", address, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(30 * time.Second))
	reader := bufio.NewReader(connection)
	_, _ = connection.Write([]byte{0x05, 0x01, 0x02})
	assertSimulatedBytes(t, reader, []byte{0x05, 0x02})
	username := []byte("proxy")
	password := []byte("proxy-test-password-2026")
	auth := append([]byte{0x01, byte(len(username))}, username...)
	auth = append(auth, byte(len(password)))
	auth = append(auth, password...)
	_, _ = connection.Write(auth)
	assertSimulatedBytes(t, reader, []byte{0x01, 0x00})

	request := []byte{0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1}
	port := make([]byte, 2)
	binary.BigEndian.PutUint16(port, uint16(destinationPort))
	_, _ = connection.Write(append(request, port...))
	reply := make([]byte, 10)
	if _, err := io.ReadFull(reader, reply); err != nil {
		t.Fatal(err)
	}
	if reply[1] != 0x00 {
		t.Fatalf("SOCKS connect failed with code %d", reply[1])
	}
	if _, err := connection.Write(payload); err != nil {
		t.Fatal(err)
	}
	actual := make([]byte, len(payload))
	if _, err := io.ReadFull(reader, actual); err != nil {
		t.Fatal(err)
	}
	return actual
}

func assertSimulatedBytes(t *testing.T, reader io.Reader, expected []byte) {
	t.Helper()
	actual := make([]byte, len(expected))
	if _, err := io.ReadFull(reader, actual); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(actual, expected) {
		t.Fatalf("expected %v, got %v", expected, actual)
	}
}

func serveEcho(listener net.Listener) {
	for {
		connection, err := listener.Accept()
		if err != nil {
			return
		}
		go func() {
			defer connection.Close()
			_, _ = io.Copy(connection, connection)
		}()
	}
}
