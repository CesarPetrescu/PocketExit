package httpapi

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"path"
	"strings"
	"time"

	"github.com/CesarPetrescu/pocket-exit/backend/internal/personal"
	"github.com/skip2/go-qrcode"
)

const (
	maxDeviceNameLength = 64
	maxNodeIDLength     = 64
	// The suffix is appended to a slug of the device name, so the slug is
	// truncated to leave room for it.
	nodeIDSuffixBytes = 4
	nodeIDSuffixLen   = 2*nodeIDSuffixBytes + 1
	nodeIDMintTries   = 8
	// The one body every 401 from the claim endpoint carries.
	claimRejectedMessage = "pairing code was not accepted"
	// The dashboard is served from this process in personal mode, so the
	// policy nginx applies in server mode is applied here instead.
	dashboardCSP = "default-src 'self'; connect-src 'self'; img-src 'self' data:; style-src 'self'; " +
		"script-src 'self'; base-uri 'none'; form-action 'none'; object-src 'none'; frame-ancestors 'none'"
)

type claimRequest struct {
	Code       string `json:"code"`
	DeviceName string `json:"device_name"`
	NodeID     string `json:"node_id"`
}

// socksHint tells the phone's "you're paired" screen where the proxy listens.
// It deliberately carries no password.
type socksHint struct {
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Username string `json:"username"`
}

type claimResponse struct {
	NodeID     string    `json:"node_id"`
	AgentToken string    `json:"agent_token"`
	ServerURL  string    `json:"server_url"`
	SOCKS      socksHint `json:"socks"`
}

type pairingResponse struct {
	Active      bool      `json:"active"`
	Code        string    `json:"code"`
	ExpiresAt   time.Time `json:"expires_at"`
	URI         string    `json:"uri"`
	QRSVG       string    `json:"qr_svg"`
	Fingerprint string    `json:"fingerprint"`
	ServerURL   string    `json:"server_url"`
}

// claim is the only unauthenticated write endpoint in the system. It is guarded
// by the pairing code, the per-code attempt counter and a per-address rate
// limit.
func (s *Server) claim(w http.ResponseWriter, r *http.Request) {
	var request claimRequest
	if err := decodeJSON(w, r, &request); err != nil {
		return
	}
	deviceName := strings.TrimSpace(request.DeviceName)
	if len(deviceName) < 1 || len(deviceName) > maxDeviceNameLength {
		writeError(w, http.StatusBadRequest, fmt.Sprintf("device_name must contain 1-%d bytes", maxDeviceNameLength))
		return
	}
	// The rate limit is a security control, so the address comes from the
	// connection. X-Forwarded-For is attacker-controlled and is never consulted
	// here, and personal mode has no proxy in front of it anyway.
	if err := s.pairing.Claim(r.RemoteAddr, request.Code); err != nil {
		s.logger.Warn("pairing claim rejected", "event", "pairing_claim_failed", "error", err.Error())
		writeError(w, claimStatus(err), claimMessage(err))
		return
	}

	nodeID, token, err := s.mintNode(request.NodeID, deviceName)
	if err != nil {
		s.logger.Error("could not register paired node", "event", "pairing_mint_failed", "error", err)
		writeError(w, http.StatusInternalServerError, "could not register the paired node")
		return
	}
	s.logger.Info("node paired", "event", "node_paired", "node_id", nodeID, "device_name", deviceName)
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, claimResponse{
		NodeID:     nodeID,
		AgentToken: token,
		ServerURL:  s.config.ServerOrigin(),
		SOCKS:      s.socksHint(),
	})
}

// claimStatus maps a pairing failure onto the contract's status codes: the rate
// limiter answers 429, every other rejection answers 401 so a caller cannot
// tell an expired code from a wrong one.
func claimStatus(err error) int {
	if errors.Is(err, personal.ErrPairingRateLimited) {
		return http.StatusTooManyRequests
	}
	return http.StatusUnauthorized
}

// claimMessage keeps the status code's promise: every 401 carries the same
// body, so no code, an expired code and a wrong code are indistinguishable to
// the caller. The specific reason is in the logger.Warn line above. The rate
// limiter is already visible in its own status, so it says what it is.
func claimMessage(err error) string {
	if errors.Is(err, personal.ErrPairingRateLimited) {
		return err.Error()
	}
	return claimRejectedMessage
}

// mintNode registers the phone under the node ID it asked for when that ID is
// free, and otherwise under a slug of the device name plus a random suffix.
// Whatever the client sends is sanitised first: it does not choose freely.
func (s *Server) mintNode(requested, deviceName string) (string, string, error) {
	if nodeID := sanitizeNodeID(requested); nodeID != "" {
		token, err := s.tokens.Mint(nodeID, deviceName)
		if err == nil {
			return nodeID, token, nil
		}
		if !errors.Is(err, personal.ErrNodeExists) {
			return "", "", err
		}
	}
	base := slugDeviceName(deviceName)
	for attempt := 0; attempt < nodeIDMintTries; attempt++ {
		suffix, err := randomSuffix()
		if err != nil {
			return "", "", err
		}
		nodeID := base + "-" + suffix
		token, err := s.tokens.Mint(nodeID, deviceName)
		if err == nil {
			return nodeID, token, nil
		}
		if !errors.Is(err, personal.ErrNodeExists) {
			return "", "", err
		}
	}
	return "", "", fmt.Errorf("could not allocate a free node ID for %q", deviceName)
}

// sanitizeNodeID reduces a client-supplied node ID to [A-Za-z0-9._-]{1,64} by
// dropping everything else. An empty result means the server picks the ID.
func sanitizeNodeID(value string) string {
	var builder strings.Builder
	for _, character := range strings.TrimSpace(value) {
		if (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') ||
			(character >= '0' && character <= '9') || character == '.' || character == '_' || character == '-' {
			builder.WriteRune(character)
		}
		if builder.Len() >= maxNodeIDLength {
			break
		}
	}
	return builder.String()
}

// slugDeviceName turns "Pixel 8" into "pixel-8", the stem the random suffix is
// appended to.
func slugDeviceName(deviceName string) string {
	var builder strings.Builder
	previousDash := false
	for _, character := range strings.ToLower(strings.TrimSpace(deviceName)) {
		switch {
		case (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9') ||
			character == '.' || character == '_':
			builder.WriteRune(character)
			previousDash = false
		case !previousDash && builder.Len() > 0:
			builder.WriteRune('-')
			previousDash = true
		}
		if builder.Len() >= maxNodeIDLength-nodeIDSuffixLen {
			break
		}
	}
	slug := strings.Trim(builder.String(), "-.")
	if slug == "" {
		return "node"
	}
	return slug
}

func randomSuffix() (string, error) {
	buffer := make([]byte, nodeIDSuffixBytes)
	if _, err := rand.Read(buffer); err != nil {
		return "", fmt.Errorf("read random bytes: %w", err)
	}
	return hex.EncodeToString(buffer), nil
}

// socksHint derives the display hint from the configured listener. A wildcard
// listener is reported as loopback, which is where the laptop's own browser
// reaches it.
func (s *Server) socksHint() socksHint {
	hint := socksHint{Host: "127.0.0.1", Port: 1080, Username: s.config.SOCKSUsername}
	host, port, err := net.SplitHostPort(s.config.SOCKSAddr)
	if err != nil {
		return hint
	}
	if parsed, err := parsePort(port); err == nil {
		hint.Port = parsed
	}
	if ip := net.ParseIP(host); host != "" && (ip == nil || !ip.IsUnspecified()) {
		hint.Host = host
	}
	return hint
}

func (s *Server) pairingStatus(w http.ResponseWriter, _ *http.Request) {
	code, active := s.pairing.Active()
	s.writePairing(w, code, active)
}

func (s *Server) mintPairingCode(w http.ResponseWriter, _ *http.Request) {
	code, err := s.pairing.Mint()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not generate a pairing code")
		return
	}
	s.logger.Info("pairing code minted", "event", "pairing_mint", "expires_at", code.ExpiresAt)
	s.writePairing(w, code, true)
}

func (s *Server) cancelPairingCode(w http.ResponseWriter, _ *http.Request) {
	s.pairing.Cancel()
	s.logger.Info("pairing code cancelled", "event", "pairing_cancel")
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) writePairing(w http.ResponseWriter, code personal.PairingCode, active bool) {
	response := pairingResponse{
		Active:      active,
		Fingerprint: s.config.CertPin,
		ServerURL:   s.config.ServerOrigin(),
	}
	if active {
		response.Code = code.Display()
		response.ExpiresAt = code.ExpiresAt
		response.URI = s.PairingURI(code.Code)
		rendered, err := qrcode.New(response.URI, qrcode.Medium)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "could not generate pairing QR")
			return
		}
		response.QRSVG = qrSVG(rendered.Bitmap())
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, response)
}

// PairingURI renders the version 2 onboarding URI a phone scans. The CLI uses
// it to draw the same QR the dashboard shows.
func (s *Server) PairingURI(code string) string {
	query := url.Values{
		"v":      {"2"},
		"server": {s.config.ServerOrigin()},
		"pair":   {code},
	}
	// The pin is optional: a personal-mode server behind a real certificate
	// pairs without pinning and the agent validates it normally.
	if s.config.CertPin != "" {
		query.Set("fp", s.config.CertPin)
	}
	if s.config.ServerName != "" {
		query.Set("name", s.config.ServerName)
	}
	return (&url.URL{Scheme: "pocketexit", Host: "configure", RawQuery: query.Encode()}).String()
}

// staticFiles serves the dashboard in personal mode, where no nginx sits in
// front of the process. It never lists a directory, never escapes the root, and
// carries the same content security policy nginx applies in server mode.
func (s *Server) staticFiles(directory string) http.Handler {
	root := http.Dir(directory)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		name := path.Clean("/" + strings.TrimPrefix(r.URL.Path, "/"))
		if name == "/" {
			name = "/index.html"
		}
		// path.Clean already resolved the dot segments; anything left is a
		// literal ".." in a file name and has no business being served.
		if strings.Contains(name, "..") {
			writeError(w, http.StatusNotFound, "not found")
			return
		}
		file, err := root.Open(name)
		if err != nil {
			if !errors.Is(err, os.ErrNotExist) {
				s.logger.Debug("dashboard file could not be opened", "path", name, "error", err)
			}
			writeError(w, http.StatusNotFound, "not found")
			return
		}
		defer file.Close()
		info, err := file.Stat()
		if err != nil || info.IsDir() {
			writeError(w, http.StatusNotFound, "not found")
			return
		}
		w.Header().Set("Content-Security-Policy", dashboardCSP)
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
		w.Header().Set("Cache-Control", "no-store")
		http.ServeContent(w, r, info.Name(), info.ModTime(), file)
	})
}
