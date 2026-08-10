package httpapi

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/CesarPetrescu/pocket-exit/backend/internal/circuit"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/config"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/model"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/nodes"
	"github.com/coder/websocket"
	"github.com/skip2/go-qrcode"
)

const circuitWebSocketProtocol = "pocketexit.circuit.v1"

type Server struct {
	config   config.Config
	nodes    *nodes.Registry
	circuits *circuit.Manager
	logger   *slog.Logger
}

func New(config config.Config, nodes *nodes.Registry, circuits *circuit.Manager, logger *slog.Logger) *Server {
	return &Server{config: config, nodes: nodes, circuits: circuits, logger: logger}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/health", s.health)
	mux.HandleFunc("GET /api/v1/nodes", s.admin(s.listNodes))
	mux.HandleFunc("PATCH /api/v1/nodes/{nodeID}", s.admin(s.updateNode))
	mux.HandleFunc("GET /api/v1/nodes/{nodeID}/onboarding", s.admin(s.onboarding))
	mux.HandleFunc("GET /api/v1/circuits", s.admin(s.listCircuits))
	mux.HandleFunc("DELETE /api/v1/circuits/{circuitID}", s.admin(s.closeCircuit))
	mux.HandleFunc("GET /api/v1/metrics", s.admin(s.metrics))

	mux.HandleFunc("POST /agent/v1/heartbeat", s.heartbeat)
	mux.HandleFunc("GET /agent/v1/control", s.control)
	mux.HandleFunc("POST /agent/v1/circuits/{circuitID}/status", s.circuitStatus)
	mux.HandleFunc("GET /agent/v1/circuits/{circuitID}/ws", s.circuitWebSocket)
	mux.HandleFunc("GET /agent/v1/circuits/{circuitID}/down", s.circuitDown)
	mux.HandleFunc("POST /agent/v1/circuits/{circuitID}/up", s.circuitUp)

	return s.recoverPanic(s.requestLog(s.securityHeaders(mux)))
}

func (s *Server) circuitWebSocket(w http.ResponseWriter, r *http.Request) {
	c, ok := s.authorizedCircuit(w, r)
	if !ok {
		return
	}
	connection, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		Subprotocols:    []string{circuitWebSocketProtocol},
		CompressionMode: websocket.CompressionDisabled,
	})
	if err != nil {
		s.logger.Debug("agent WebSocket upgrade failed", "circuit_id", c.ID(), "error", err)
		return
	}
	defer connection.CloseNow()
	if connection.Subprotocol() != circuitWebSocketProtocol {
		_ = connection.Close(websocket.StatusPolicyViolation, "required subprotocol missing")
		return
	}
	connection.SetReadLimit(1 << 20)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stream := websocket.NetConn(ctx, connection, websocket.MessageBinary)
	defer stream.Close()

	result := make(chan error, 2)
	go func() {
		_, copyErr := io.CopyBuffer(writerFunc(c.WriteUp), stream, make([]byte, 64*1024))
		_ = c.CloseUp()
		result <- copyErr
	}()
	go func() {
		_, copyErr := io.CopyBuffer(stream, readerFunc(c.ReadDown), make([]byte, 64*1024))
		result <- copyErr
	}()

	select {
	case <-c.Done():
	case copyErr := <-result:
		if copyErr != nil && !isExpectedStreamError(copyErr) {
			s.logger.Debug("agent WebSocket stream ended", "circuit_id", c.ID(), "error", copyErr)
		}
	}
	c.Close(nil)
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status": "ok",
		"time":   time.Now().UTC(),
	})
}

func (s *Server) heartbeat(w http.ResponseWriter, r *http.Request) {
	var request model.HeartbeatRequest
	if err := decodeJSON(w, r, &request); err != nil {
		return
	}
	if !s.authenticateAgent(r, request.NodeID) {
		writeError(w, http.StatusUnauthorized, "invalid node credentials")
		return
	}
	node, err := s.nodes.Heartbeat(request)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, node)
}

func (s *Server) control(w http.ResponseWriter, r *http.Request) {
	nodeID := strings.TrimSpace(r.URL.Query().Get("node_id"))
	if !s.authenticateAgent(r, nodeID) {
		writeError(w, http.StatusUnauthorized, "invalid node credentials")
		return
	}
	if _, ok := s.nodes.Get(nodeID); !ok {
		writeError(w, http.StatusConflict, "send a heartbeat before opening the control channel")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), s.config.CommandWait)
	defer cancel()
	command, ok, err := s.nodes.NextCommand(ctx, nodeID)
	if err != nil {
		if errors.Is(err, context.Canceled) {
			return
		}
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	if !ok {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	writeJSON(w, http.StatusOK, command)
}

type statusRequest struct {
	NodeID string `json:"node_id"`
	Status string `json:"status"`
	Error  string `json:"error"`
}

func (s *Server) circuitStatus(w http.ResponseWriter, r *http.Request) {
	circuitID := r.PathValue("circuitID")
	var request statusRequest
	if err := decodeJSON(w, r, &request); err != nil {
		return
	}
	if !s.authenticateAgent(r, request.NodeID) {
		writeError(w, http.StatusUnauthorized, "invalid node credentials")
		return
	}
	c, ok := s.circuits.Get(circuitID)
	if !ok {
		writeError(w, http.StatusNotFound, "circuit not found")
		return
	}
	if c.NodeID() != request.NodeID {
		writeError(w, http.StatusForbidden, "circuit belongs to another node")
		return
	}

	switch strings.ToLower(request.Status) {
	case "connected", "open":
		c.MarkOpen()
	case "failed":
		c.MarkFailed(errors.New(defaultString(request.Error, "agent failed to open destination")))
	case "closed":
		c.Close(nil)
	default:
		writeError(w, http.StatusBadRequest, "status must be connected, failed, or closed")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) circuitDown(w http.ResponseWriter, r *http.Request) {
	c, ok := s.authorizedCircuit(w, r)
	if !ok {
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	if flusher, ok := w.(http.Flusher); ok {
		flusher.Flush()
	}
	if err := copyCircuitDown(w, c); err != nil && !isExpectedStreamError(err) {
		s.logger.Debug("agent down stream ended", "circuit_id", c.ID(), "error", err)
	}
}

func (s *Server) circuitUp(w http.ResponseWriter, r *http.Request) {
	c, ok := s.authorizedCircuit(w, r)
	if !ok {
		return
	}
	defer c.CloseUp()
	buffer := make([]byte, 32*1024)
	for {
		n, err := r.Body.Read(buffer)
		if n > 0 {
			if _, writeErr := c.WriteUp(buffer[:n]); writeErr != nil {
				if !isExpectedStreamError(writeErr) {
					s.logger.Debug("agent up stream write ended", "circuit_id", c.ID(), "error", writeErr)
				}
				break
			}
		}
		if err != nil {
			if err != io.EOF && !isExpectedStreamError(err) {
				s.logger.Debug("agent up stream read ended", "circuit_id", c.ID(), "error", err)
			}
			break
		}
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) authorizedCircuit(w http.ResponseWriter, r *http.Request) (*circuit.Circuit, bool) {
	nodeID := strings.TrimSpace(r.URL.Query().Get("node_id"))
	if !s.authenticateAgent(r, nodeID) {
		writeError(w, http.StatusUnauthorized, "invalid node credentials")
		return nil, false
	}
	circuitID := r.PathValue("circuitID")
	c, ok := s.circuits.Get(circuitID)
	if !ok {
		writeError(w, http.StatusNotFound, "circuit not found")
		return nil, false
	}
	if c.NodeID() != nodeID {
		writeError(w, http.StatusForbidden, "circuit belongs to another node")
		return nil, false
	}
	return c, true
}

func (s *Server) listNodes(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"nodes": s.nodes.List()})
}

func (s *Server) onboarding(w http.ResponseWriter, r *http.Request) {
	nodeID := r.PathValue("nodeID")
	token, ok := s.config.AgentTokens[nodeID]
	if !ok {
		writeError(w, http.StatusNotFound, "node is not configured")
		return
	}
	query := url.Values{
		"v":      {"1"},
		"server": {"https://" + s.config.PublicProxyHost},
		"node":   {nodeID},
		"token":  {token},
	}
	onboardingURI := (&url.URL{Scheme: "pocketexit", Host: "configure", RawQuery: query.Encode()}).String()
	code, err := qrcode.New(onboardingURI, qrcode.Medium)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not generate onboarding QR")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]string{
		"node_id":        nodeID,
		"onboarding_uri": onboardingURI,
		"qr_svg":         qrSVG(code.Bitmap()),
	})
}

func qrSVG(bitmap [][]bool) string {
	size := len(bitmap)
	var path strings.Builder
	for y, row := range bitmap {
		for x, dark := range row {
			if dark {
				fmt.Fprintf(&path, "M%d %dh1v1h-1z", x, y)
			}
		}
	}
	return fmt.Sprintf(
		`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges"><rect width="100%%" height="100%%" fill="white"/><path d="%s" fill="black"/></svg>`,
		size,
		size,
		path.String(),
	)
}

type updateNodeRequest struct {
	Enabled       *bool         `json:"enabled"`
	ControlPolicy *model.Policy `json:"control_policy"`
	ExitPolicy    *model.Policy `json:"exit_policy"`
}

func (s *Server) updateNode(w http.ResponseWriter, r *http.Request) {
	var request updateNodeRequest
	if err := decodeJSON(w, r, &request); err != nil {
		return
	}
	node, err := s.nodes.Update(r.PathValue("nodeID"), request.Enabled, request.ControlPolicy, request.ExitPolicy)
	if err != nil {
		status := http.StatusBadRequest
		if errors.Is(err, nodes.ErrNodeNotFound) {
			status = http.StatusNotFound
		}
		writeError(w, status, err.Error())
		return
	}
	s.logger.Info("node settings updated", "event", "node_update", "node_id", node.NodeID)
	writeJSON(w, http.StatusOK, node)
}

func (s *Server) listCircuits(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"circuits": s.circuits.List()})
}

func (s *Server) closeCircuit(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("circuitID")
	c, ok := s.circuits.Get(id)
	if !ok {
		writeError(w, http.StatusNotFound, "circuit not found")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	_ = s.nodes.QueueCommand(ctx, c.NodeID(), model.Command{Type: model.CommandClose, CircuitID: id})
	c.Close(nil)
	s.logger.Info("circuit closed by administrator", "event", "admin_circuit_close", "circuit_id", id, "node_id", c.NodeID())
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) metrics(w http.ResponseWriter, _ *http.Request) {
	nodesList := s.nodes.List()
	circuits := s.circuits.List()
	online := 0
	for _, node := range nodesList {
		if node.Online {
			online++
		}
	}
	open := 0
	for _, item := range circuits {
		if item.Status == model.CircuitOpen || item.Status == model.CircuitPending {
			open++
		}
	}
	w.Header().Set("Content-Type", "text/plain; version=0.0.4")
	_, _ = fmt.Fprintf(w, "pocketexit_nodes_total %d\n", len(nodesList))
	_, _ = fmt.Fprintf(w, "pocketexit_nodes_online %d\n", online)
	_, _ = fmt.Fprintf(w, "pocketexit_circuits_total %d\n", len(circuits))
	_, _ = fmt.Fprintf(w, "pocketexit_circuits_open %d\n", open)
}

func (s *Server) authenticateAgent(r *http.Request, nodeID string) bool {
	authenticated := s.nodes.Authenticate(nodeID, bearerToken(r), s.config.AgentTokens)
	if !authenticated {
		s.logger.Warn("agent authentication failed", "event", "agent_auth_failed", "node_id", nodeID)
	}
	return authenticated
}

func (s *Server) admin(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !secureEqual(bearerToken(r), s.config.AdminToken) {
			s.logger.Warn("admin authentication failed", "event", "admin_auth_failed", "path", r.URL.Path)
			writeError(w, http.StatusUnauthorized, "invalid admin token")
			return
		}
		next(w, r)
	}
}

func (s *Server) requestLog(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		next.ServeHTTP(w, r)
		if !strings.Contains(r.URL.Path, "/control") {
			s.logger.Debug("http request", "method", r.Method, "path", r.URL.Path, "duration_ms", time.Since(started).Milliseconds())
		}
	})
}

func (s *Server) securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(w, r)
	})
}

func (s *Server) recoverPanic(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if recovered := recover(); recovered != nil {
				s.logger.Error("panic in HTTP handler", "panic", recovered, "path", r.URL.Path)
				writeError(w, http.StatusInternalServerError, "internal server error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func copyCircuitDown(w http.ResponseWriter, c *circuit.Circuit) error {
	buffer := make([]byte, 32*1024)
	for {
		n, err := c.ReadDown(buffer)
		if n > 0 {
			if _, writeErr := w.Write(buffer[:n]); writeErr != nil {
				return writeErr
			}
			if flusher, ok := w.(http.Flusher); ok {
				flusher.Flush()
			}
		}
		if err != nil {
			return err
		}
	}
}

func decodeJSON(w http.ResponseWriter, r *http.Request, destination any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		return err
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func bearerToken(r *http.Request) string {
	value := strings.TrimSpace(r.Header.Get("Authorization"))
	if len(value) < 8 || !strings.EqualFold(value[:7], "Bearer ") {
		return ""
	}
	return strings.TrimSpace(value[7:])
}

func secureEqual(left, right string) bool {
	if len(left) != len(right) || len(left) == 0 {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(left), []byte(right)) == 1
}

func defaultString(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return value
}

func isExpectedStreamError(err error) bool {
	if err == nil || errors.Is(err, io.EOF) || errors.Is(err, context.Canceled) {
		return true
	}
	text := strings.ToLower(err.Error())
	for _, fragment := range []string{"closed pipe", "connection reset", "broken pipe", "use of closed network connection", "context canceled"} {
		if strings.Contains(text, fragment) {
			return true
		}
	}
	return false
}

type writerFunc func([]byte) (int, error)

func (f writerFunc) Write(payload []byte) (int, error) { return f(payload) }

type readerFunc func([]byte) (int, error)

func (f readerFunc) Read(payload []byte) (int, error) { return f(payload) }

func parsePort(value string) (int, error) {
	port, err := strconv.Atoi(value)
	if err != nil || port < 1 || port > 65535 {
		return 0, fmt.Errorf("invalid port")
	}
	return port, nil
}
