package proxy

import (
	"bufio"
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/CesarPetrescu/pocket-exit/backend/internal/circuit"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/config"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/model"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/nodes"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/protocol"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/security"
)

const (
	socksVersion = 0x05
	authUserPass = 0x02
	noAcceptable = 0xff

	commandConnect      = 0x01
	commandUDPAssociate = 0x03

	replySuccess             = 0x00
	replyGeneralFailure      = 0x01
	replyNotAllowed          = 0x02
	replyNetworkUnreachable  = 0x03
	replyHostUnreachable     = 0x04
	replyConnectionRefused   = 0x05
	replyCommandNotSupported = 0x07
	replyAddressNotSupported = 0x08

	addressIPv4   = 0x01
	addressDomain = 0x03
	addressIPv6   = 0x04
)

type Server struct {
	config   config.Config
	nodes    *nodes.Registry
	circuits *circuit.Manager
	logger   *slog.Logger
	ports    *portPool
}

func New(config config.Config, nodes *nodes.Registry, circuits *circuit.Manager, logger *slog.Logger) *Server {
	return &Server{
		config:   config,
		nodes:    nodes,
		circuits: circuits,
		logger:   logger,
		ports:    newPortPool(config.UDPPortStart, config.UDPPortEnd),
	}
}

func (s *Server) Run(ctx context.Context) error {
	listener, err := net.Listen("tcp", s.config.SOCKSAddr)
	if err != nil {
		return fmt.Errorf("listen SOCKS5: %w", err)
	}
	defer listener.Close()
	return s.Serve(ctx, listener)
}

func (s *Server) Serve(ctx context.Context, listener net.Listener) error {
	go func() {
		<-ctx.Done()
		_ = listener.Close()
	}()

	for {
		connection, err := listener.Accept()
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
				return nil
			}
			if temporary, ok := err.(net.Error); ok && temporary.Temporary() {
				time.Sleep(50 * time.Millisecond)
				continue
			}
			return fmt.Errorf("accept SOCKS5: %w", err)
		}
		go s.handleConnection(ctx, connection)
	}
}

type selector struct {
	nodeID string
	policy model.Policy
}

func (s *Server) handleConnection(ctx context.Context, connection net.Conn) {
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(30 * time.Second))
	reader := bufio.NewReaderSize(connection, 32*1024)

	selected, err := s.negotiate(reader, connection)
	if err != nil {
		s.logger.Debug("SOCKS negotiation failed", "remote", connection.RemoteAddr(), "error", err)
		return
	}
	request, err := readRequest(reader)
	if err != nil {
		_ = writeReply(connection, replyAddressNotSupported, socksAddress{})
		return
	}
	_ = connection.SetDeadline(time.Time{})

	switch request.command {
	case commandConnect:
		s.handleTCP(ctx, connection, reader, selected, request.address)
	case commandUDPAssociate:
		s.handleUDP(ctx, connection, selected)
	default:
		_ = writeReply(connection, replyCommandNotSupported, socksAddress{})
	}
}

func (s *Server) negotiate(reader *bufio.Reader, writer io.Writer) (selector, error) {
	header := make([]byte, 2)
	if _, err := io.ReadFull(reader, header); err != nil {
		return selector{}, err
	}
	if header[0] != socksVersion || header[1] == 0 {
		return selector{}, fmt.Errorf("invalid greeting")
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(reader, methods); err != nil {
		return selector{}, err
	}
	accepted := false
	for _, method := range methods {
		if method == authUserPass {
			accepted = true
			break
		}
	}
	if !accepted {
		_, _ = writer.Write([]byte{socksVersion, noAcceptable})
		return selector{}, fmt.Errorf("username/password authentication required")
	}
	if _, err := writer.Write([]byte{socksVersion, authUserPass}); err != nil {
		return selector{}, err
	}

	version, err := reader.ReadByte()
	if err != nil || version != 0x01 {
		return selector{}, fmt.Errorf("invalid auth version")
	}
	usernameLength, err := reader.ReadByte()
	if err != nil || usernameLength == 0 {
		return selector{}, fmt.Errorf("invalid username length")
	}
	username := make([]byte, int(usernameLength))
	if _, err := io.ReadFull(reader, username); err != nil {
		return selector{}, err
	}
	passwordLength, err := reader.ReadByte()
	if err != nil || passwordLength == 0 {
		return selector{}, fmt.Errorf("invalid password length")
	}
	password := make([]byte, int(passwordLength))
	if _, err := io.ReadFull(reader, password); err != nil {
		return selector{}, err
	}

	selected, baseUsername, err := parseSelector(string(username))
	valid := err == nil && secureEqual(baseUsername, s.config.SOCKSUsername) && secureEqual(string(password), s.config.SOCKSPassword)
	if !valid {
		_, _ = writer.Write([]byte{0x01, 0x01})
		return selector{}, fmt.Errorf("invalid credentials or selector")
	}
	if _, err := writer.Write([]byte{0x01, 0x00}); err != nil {
		return selector{}, err
	}
	return selected, nil
}

type socksRequest struct {
	command byte
	address socksAddress
}

type socksAddress struct {
	typeCode byte
	host     string
	port     int
}

func readRequest(reader io.Reader) (socksRequest, error) {
	header := make([]byte, 3)
	if _, err := io.ReadFull(reader, header); err != nil {
		return socksRequest{}, err
	}
	if header[0] != socksVersion || header[2] != 0x00 {
		return socksRequest{}, fmt.Errorf("invalid request header")
	}
	address, err := readAddress(reader)
	if err != nil {
		return socksRequest{}, err
	}
	return socksRequest{command: header[1], address: address}, nil
}

func readAddress(reader io.Reader) (socksAddress, error) {
	var addressType [1]byte
	if _, err := io.ReadFull(reader, addressType[:]); err != nil {
		return socksAddress{}, err
	}
	address := socksAddress{typeCode: addressType[0]}
	switch address.typeCode {
	case addressIPv4:
		value := make([]byte, net.IPv4len)
		if _, err := io.ReadFull(reader, value); err != nil {
			return socksAddress{}, err
		}
		address.host = net.IP(value).String()
	case addressIPv6:
		value := make([]byte, net.IPv6len)
		if _, err := io.ReadFull(reader, value); err != nil {
			return socksAddress{}, err
		}
		address.host = net.IP(value).String()
	case addressDomain:
		var length [1]byte
		if _, err := io.ReadFull(reader, length[:]); err != nil {
			return socksAddress{}, err
		}
		if length[0] == 0 {
			return socksAddress{}, fmt.Errorf("empty destination domain")
		}
		value := make([]byte, int(length[0]))
		if _, err := io.ReadFull(reader, value); err != nil {
			return socksAddress{}, err
		}
		address.host = string(value)
	default:
		return socksAddress{}, fmt.Errorf("unsupported address type %d", address.typeCode)
	}
	var port [2]byte
	if _, err := io.ReadFull(reader, port[:]); err != nil {
		return socksAddress{}, err
	}
	address.port = int(binary.BigEndian.Uint16(port[:]))
	return address, nil
}

func (s *Server) handleTCP(ctx context.Context, connection net.Conn, bufferedClient *bufio.Reader, selected selector, target socksAddress) {
	if target.port == 0 {
		_ = writeReply(connection, replyAddressNotSupported, socksAddress{})
		return
	}
	if err := security.ValidateHost(target.host, s.config.AllowPrivateDestinations); err != nil {
		_ = writeReply(connection, replyNotAllowed, socksAddress{})
		return
	}
	policy := selected.policy
	node, err := s.nodes.Choose(selected.nodeID, policy)
	if err != nil {
		_ = writeReply(connection, replyNetworkUnreachable, socksAddress{})
		return
	}
	if !policy.Valid() {
		policy = node.ExitPolicy
	}
	c, err := s.circuits.CreateLimited(node.NodeID, model.ProtocolTCP, target.host, target.port, policy, s.config.MaxCircuitsPerNode)
	if err != nil {
		_ = writeReply(connection, replyGeneralFailure, socksAddress{})
		return
	}
	defer func() {
		c.Close(nil)
		s.queueClose(c)
	}()

	command := model.Command{
		Type:         model.CommandOpenTCP,
		CircuitID:    c.ID(),
		TargetHost:   target.host,
		TargetPort:   target.port,
		ExitPolicy:   policy,
		AllowPrivate: s.config.AllowPrivateDestinations,
	}
	queueCtx, cancelQueue := context.WithTimeout(ctx, 2*time.Second)
	err = s.nodes.QueueCommand(queueCtx, node.NodeID, command)
	cancelQueue()
	if err != nil {
		_ = writeReply(connection, replyHostUnreachable, socksAddress{})
		return
	}
	openCtx, cancelOpen := context.WithTimeout(ctx, s.config.OpenTimeout)
	err = c.WaitReady(openCtx)
	cancelOpen()
	if err != nil {
		_ = writeReply(connection, replyConnectionRefused, socksAddress{})
		return
	}
	if err := writeReply(connection, replySuccess, socksAddress{typeCode: addressIPv4, host: "0.0.0.0", port: 0}); err != nil {
		return
	}

	_ = connection.SetDeadline(time.Time{})
	result := make(chan error, 2)
	go func() {
		_, copyErr := io.CopyBuffer(writerFunc(c.WriteDown), bufferedClient, make([]byte, 32*1024))
		_ = c.CloseDown()
		result <- copyErr
	}()
	go func() {
		_, copyErr := io.CopyBuffer(connection, readerFunc(c.ReadUp), make([]byte, 32*1024))
		result <- copyErr
	}()

	select {
	case <-ctx.Done():
	case <-c.Done():
	case <-result:
	}
	_ = connection.SetDeadline(time.Now())
}

func (s *Server) handleUDP(ctx context.Context, control net.Conn, selected selector) {
	port, ok := s.ports.Acquire()
	if !ok {
		_ = writeReply(control, replyGeneralFailure, socksAddress{})
		return
	}
	defer s.ports.Release(port)

	listenAddress := net.JoinHostPort(s.config.UDPBindHost, fmt.Sprintf("%d", port))
	packetConnection, err := net.ListenPacket("udp", listenAddress)
	if err != nil {
		_ = writeReply(control, replyGeneralFailure, socksAddress{})
		s.logger.Error("listen UDP relay", "port", port, "error", err)
		return
	}
	defer packetConnection.Close()

	relayAddress := addressFromHostPort(s.config.PublicProxyHost, port)
	if err := writeReply(control, replySuccess, relayAddress); err != nil {
		return
	}

	association := &udpAssociation{
		server:      s,
		selected:    selected,
		connection:  packetConnection,
		circuits:    make(map[string]*udpTarget),
		idleTimeout: s.config.IdleTimeout,
		maxTargets:  64,
	}
	associationCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	go association.run(associationCtx)

	buffer := make([]byte, 1)
	for {
		if _, err := control.Read(buffer); err != nil {
			break
		}
	}
	association.close()
}

func writeReply(writer io.Writer, reply byte, address socksAddress) error {
	if address.typeCode == 0 {
		address = socksAddress{typeCode: addressIPv4, host: "0.0.0.0", port: 0}
	}
	encoded, err := encodeAddress(address)
	if err != nil {
		return err
	}
	response := append([]byte{socksVersion, reply, 0x00}, encoded...)
	_, err = writer.Write(response)
	return err
}

func encodeAddress(address socksAddress) ([]byte, error) {
	var result []byte
	ip := net.ParseIP(address.host)
	switch {
	case address.typeCode == addressIPv4 || (address.typeCode == 0 && ip != nil && ip.To4() != nil):
		ipv4 := ip.To4()
		if ipv4 == nil {
			return nil, fmt.Errorf("invalid IPv4 address")
		}
		result = append([]byte{addressIPv4}, ipv4...)
	case address.typeCode == addressIPv6 || (address.typeCode == 0 && ip != nil):
		ipv6 := ip.To16()
		if ipv6 == nil {
			return nil, fmt.Errorf("invalid IPv6 address")
		}
		result = append([]byte{addressIPv6}, ipv6...)
	default:
		if len(address.host) == 0 || len(address.host) > 255 {
			return nil, fmt.Errorf("invalid domain address")
		}
		result = append([]byte{addressDomain, byte(len(address.host))}, []byte(address.host)...)
	}
	port := make([]byte, 2)
	binary.BigEndian.PutUint16(port, uint16(address.port))
	return append(result, port...), nil
}

func addressFromHostPort(host string, port int) socksAddress {
	if ip := net.ParseIP(host); ip != nil {
		if ip.To4() != nil {
			return socksAddress{typeCode: addressIPv4, host: host, port: port}
		}
		return socksAddress{typeCode: addressIPv6, host: host, port: port}
	}
	return socksAddress{typeCode: addressDomain, host: host, port: port}
}

func parseSelector(username string) (selector, string, error) {
	username = strings.TrimSpace(username)
	if username == "" {
		return selector{}, "", fmt.Errorf("empty username")
	}
	selected := selector{}
	baseAndNode := username
	if index := strings.LastIndex(username, "!"); index >= 0 {
		baseAndNode = username[:index]
		policy, err := parsePolicyAlias(username[index+1:])
		if err != nil {
			return selector{}, "", err
		}
		selected.policy = policy
	}
	base := baseAndNode
	if index := strings.LastIndex(baseAndNode, "@"); index >= 0 {
		base = baseAndNode[:index]
		selected.nodeID = strings.TrimSpace(baseAndNode[index+1:])
		if selected.nodeID == "" {
			return selector{}, "", fmt.Errorf("empty node selector")
		}
	}
	if base == "" {
		return selector{}, "", fmt.Errorf("empty base username")
	}
	return selected, base, nil
}

func parsePolicyAlias(value string) (model.Policy, error) {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "auto":
		return model.PolicyAuto, nil
	case "wifi", "wifi_only":
		return model.PolicyWiFiOnly, nil
	case "cell", "cellular", "lte", "5g", "cellular_only":
		return model.PolicyCellularOnly, nil
	case "wifi_preferred":
		return model.PolicyWiFiPreferred, nil
	case "cellular_preferred", "cell_preferred":
		return model.PolicyCellularPreferred, nil
	default:
		return "", fmt.Errorf("unknown network policy")
	}
}

func secureEqual(left, right string) bool {
	if len(left) != len(right) || len(left) == 0 {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(left), []byte(right)) == 1
}

func (s *Server) queueClose(c *circuit.Circuit) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_ = s.nodes.QueueCommand(ctx, c.NodeID(), model.Command{Type: model.CommandClose, CircuitID: c.ID()})
}

type udpTarget struct {
	address socksAddress
	circuit *circuit.Circuit
}

type udpAssociation struct {
	server      *Server
	selected    selector
	connection  net.PacketConn
	idleTimeout time.Duration
	maxTargets  int

	mu         sync.Mutex
	clientPeer net.Addr
	circuits   map[string]*udpTarget
	closed     bool
}

func (a *udpAssociation) run(ctx context.Context) {
	buffer := make([]byte, protocol.MaxDatagramSize+300)
	for {
		if deadlineSetter, ok := a.connection.(interface{ SetReadDeadline(time.Time) error }); ok {
			_ = deadlineSetter.SetReadDeadline(time.Now().Add(a.idleTimeout))
		}
		n, peer, err := a.connection.ReadFrom(buffer)
		if err != nil {
			if ctx.Err() == nil && !errors.Is(err, net.ErrClosed) {
				a.server.logger.Debug("UDP association ended", "error", err)
			}
			return
		}
		if !a.acceptPeer(peer) {
			continue
		}
		address, payload, err := parseUDPRequest(buffer[:n])
		if err != nil {
			continue
		}
		if err := security.ValidateHost(address.host, a.server.config.AllowPrivateDestinations); err != nil {
			continue
		}
		target, err := a.getOrCreateTarget(ctx, address)
		if err != nil {
			a.server.logger.Debug("create UDP circuit", "target", address.host, "error", err)
			continue
		}
		if err := protocol.WriteDatagram(writerFunc(target.circuit.WriteDown), payload); err != nil {
			continue
		}
	}
}

func (a *udpAssociation) acceptPeer(peer net.Addr) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.clientPeer == nil {
		a.clientPeer = peer
		return true
	}
	return a.clientPeer.String() == peer.String()
}

func (a *udpAssociation) getOrCreateTarget(ctx context.Context, address socksAddress) (*udpTarget, error) {
	key := fmt.Sprintf("%s:%d", strings.ToLower(address.host), address.port)
	a.mu.Lock()
	if target, ok := a.circuits[key]; ok {
		a.mu.Unlock()
		return target, nil
	}
	if len(a.circuits) >= a.maxTargets {
		a.mu.Unlock()
		return nil, fmt.Errorf("UDP association target limit reached")
	}
	a.mu.Unlock()

	node, err := a.server.nodes.Choose(a.selected.nodeID, a.selected.policy)
	if err != nil {
		return nil, err
	}
	policy := a.selected.policy
	if !policy.Valid() {
		policy = node.ExitPolicy
	}
	c, err := a.server.circuits.CreateLimited(node.NodeID, model.ProtocolUDP, address.host, address.port, policy, a.server.config.MaxCircuitsPerNode)
	if err != nil {
		return nil, err
	}
	command := model.Command{
		Type:         model.CommandOpenUDP,
		CircuitID:    c.ID(),
		TargetHost:   address.host,
		TargetPort:   address.port,
		ExitPolicy:   policy,
		AllowPrivate: a.server.config.AllowPrivateDestinations,
	}
	queueCtx, cancelQueue := context.WithTimeout(ctx, 2*time.Second)
	err = a.server.nodes.QueueCommand(queueCtx, node.NodeID, command)
	cancelQueue()
	if err != nil {
		c.Close(err)
		return nil, err
	}
	openCtx, cancelOpen := context.WithTimeout(ctx, a.server.config.OpenTimeout)
	err = c.WaitReady(openCtx)
	cancelOpen()
	if err != nil {
		c.Close(err)
		return nil, err
	}

	target := &udpTarget{address: address, circuit: c}
	a.mu.Lock()
	if existing, ok := a.circuits[key]; ok {
		a.mu.Unlock()
		c.Close(nil)
		return existing, nil
	}
	a.circuits[key] = target
	a.mu.Unlock()
	go a.readResponses(target)
	return target, nil
}

func (a *udpAssociation) readResponses(target *udpTarget) {
	for {
		payload, err := protocol.ReadDatagram(readerFunc(target.circuit.ReadUp))
		if err != nil {
			return
		}
		a.mu.Lock()
		peer := a.clientPeer
		closed := a.closed
		a.mu.Unlock()
		if peer == nil || closed {
			return
		}
		packet, err := buildUDPResponse(target.address, payload)
		if err != nil {
			return
		}
		if _, err := a.connection.WriteTo(packet, peer); err != nil {
			return
		}
	}
}

func (a *udpAssociation) close() {
	a.mu.Lock()
	if a.closed {
		a.mu.Unlock()
		return
	}
	a.closed = true
	targets := make([]*udpTarget, 0, len(a.circuits))
	for _, target := range a.circuits {
		targets = append(targets, target)
	}
	a.mu.Unlock()
	_ = a.connection.Close()
	for _, target := range targets {
		target.circuit.Close(nil)
		a.server.queueClose(target.circuit)
	}
}

func parseUDPRequest(packet []byte) (socksAddress, []byte, error) {
	if len(packet) < 4 || packet[0] != 0 || packet[1] != 0 {
		return socksAddress{}, nil, fmt.Errorf("invalid UDP packet")
	}
	if packet[2] != 0 {
		return socksAddress{}, nil, fmt.Errorf("fragmented SOCKS UDP packets are unsupported")
	}
	reader := bytes.NewReader(packet[3:])
	address, err := readAddress(reader)
	if err != nil {
		return socksAddress{}, nil, err
	}
	if address.port == 0 {
		return socksAddress{}, nil, fmt.Errorf("destination port must be non-zero")
	}
	payload, err := io.ReadAll(reader)
	if err != nil {
		return socksAddress{}, nil, err
	}
	return address, payload, nil
}

func buildUDPResponse(address socksAddress, payload []byte) ([]byte, error) {
	encoded, err := encodeAddress(address)
	if err != nil {
		return nil, err
	}
	packet := make([]byte, 0, 3+len(encoded)+len(payload))
	packet = append(packet, 0x00, 0x00, 0x00)
	packet = append(packet, encoded...)
	packet = append(packet, payload...)
	return packet, nil
}

type portPool struct {
	mu    sync.Mutex
	start int
	end   int
	used  map[int]bool
}

func newPortPool(start, end int) *portPool {
	return &portPool{start: start, end: end, used: make(map[int]bool)}
}

func (p *portPool) Acquire() (int, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for port := p.start; port <= p.end; port++ {
		if !p.used[port] {
			p.used[port] = true
			return port, true
		}
	}
	return 0, false
}

func (p *portPool) Release(port int) {
	p.mu.Lock()
	delete(p.used, port)
	p.mu.Unlock()
}

type writerFunc func([]byte) (int, error)

func (f writerFunc) Write(payload []byte) (int, error) { return f(payload) }

type readerFunc func([]byte) (int, error)

func (f readerFunc) Read(payload []byte) (int, error) { return f(payload) }
