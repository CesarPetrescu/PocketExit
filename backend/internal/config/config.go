package config

import (
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	// ModeServer is the deployed backend: nginx terminates TLS, and every
	// credential arrives through the environment.
	ModeServer = "server"
	// ModePersonal is the zero-server laptop: the process terminates TLS
	// itself and reads its credentials from the state directory.
	ModePersonal = "personal"
)

// Defaults shared by both modes. Server mode reaches them through the
// environment fallbacks, personal mode uses them directly because it has no
// environment to read.
const (
	defaultUDPPortStart       = 12000
	defaultUDPPortEnd         = 12031
	defaultNodeOfflineAfter   = 45 * time.Second
	defaultCommandWait        = 5 * time.Second
	defaultOpenTimeout        = 45 * time.Second
	defaultIdleTimeout        = 2 * time.Minute
	defaultMaxCircuitsPerNode = 128
	defaultMaxBytesPerCircuit = 1 << 30
	maxServerNameLength       = 64
)

type Config struct {
	Mode                     string
	HTTPAddr                 string
	SOCKSAddr                string
	PublicProxyHost          string
	UDPBindHost              string
	UDPPortStart             int
	UDPPortEnd               int
	AdminToken               string
	SOCKSUsername            string
	SOCKSPassword            string
	AgentTokens              map[string]string
	NodeOfflineAfter         time.Duration
	CommandWait              time.Duration
	OpenTimeout              time.Duration
	IdleTimeout              time.Duration
	MaxCircuitsPerNode       int
	MaxBytesPerCircuit       int64
	AllowPrivateDestinations bool
	LogJSON                  bool
	AuditLogPath             string
	// Personal mode only. ServerURL is the https origin phones dial, port
	// included; ServerName and CertPin travel in the onboarding URI; an empty
	// FrontendDir disables dashboard serving.
	ServerURL   string
	ServerName  string
	CertPin     string
	FrontendDir string
}

// ServerOrigin is the https origin a phone dials: the address personal mode
// advertises, or the nginx front door in server mode.
func (c Config) ServerOrigin() string {
	if c.Mode == ModePersonal {
		return c.ServerURL
	}
	return "https://" + c.PublicProxyHost
}

func Load() (Config, error) {
	cfg := Config{
		Mode:            ModeServer,
		HTTPAddr:        env("HTTP_ADDR", ":8080"),
		SOCKSAddr:       env("SOCKS_ADDR", ":1080"),
		PublicProxyHost: env("PUBLIC_PROXY_HOST", "127.0.0.1"),
		UDPBindHost:     env("UDP_BIND_HOST", "0.0.0.0"),
		AdminToken:      env("ADMIN_TOKEN", "change-me-admin"),
		SOCKSUsername:   env("SOCKS_USERNAME", "proxy"),
		SOCKSPassword:   env("SOCKS_PASSWORD", "change-me-proxy"),
		AgentTokens:     map[string]string{},
		AuditLogPath:    env("AUDIT_LOG_PATH", "/data/audit.jsonl"),
	}

	var err error
	if cfg.UDPPortStart, err = envInt("UDP_PORT_START", defaultUDPPortStart); err != nil {
		return Config{}, err
	}
	if cfg.UDPPortEnd, err = envInt("UDP_PORT_END", defaultUDPPortEnd); err != nil {
		return Config{}, err
	}
	if cfg.NodeOfflineAfter, err = envDuration("NODE_OFFLINE_AFTER", defaultNodeOfflineAfter); err != nil {
		return Config{}, err
	}
	if cfg.CommandWait, err = envDuration("COMMAND_WAIT", defaultCommandWait); err != nil {
		return Config{}, err
	}
	if cfg.OpenTimeout, err = envDuration("OPEN_TIMEOUT", defaultOpenTimeout); err != nil {
		return Config{}, err
	}
	if cfg.IdleTimeout, err = envDuration("IDLE_TIMEOUT", defaultIdleTimeout); err != nil {
		return Config{}, err
	}
	if cfg.MaxCircuitsPerNode, err = envInt("MAX_CIRCUITS_PER_NODE", defaultMaxCircuitsPerNode); err != nil {
		return Config{}, err
	}
	if cfg.MaxBytesPerCircuit, err = envInt64("MAX_BYTES_PER_CIRCUIT", defaultMaxBytesPerCircuit); err != nil {
		return Config{}, err
	}
	if cfg.AllowPrivateDestinations, err = envBool("ALLOW_PRIVATE_DESTINATIONS", false); err != nil {
		return Config{}, err
	}
	if cfg.LogJSON, err = envBool("LOG_JSON", true); err != nil {
		return Config{}, err
	}

	rawAgentTokens := strings.TrimSpace(os.Getenv("AGENT_TOKENS_JSON"))
	if rawAgentTokens == "" {
		return Config{}, fmt.Errorf("AGENT_TOKENS_JSON is required")
	}
	if err := json.Unmarshal([]byte(rawAgentTokens), &cfg.AgentTokens); err != nil {
		return Config{}, fmt.Errorf("parse AGENT_TOKENS_JSON: %w", err)
	}
	if len(cfg.AgentTokens) == 0 {
		return Config{}, fmt.Errorf("AGENT_TOKENS_JSON must contain at least one node")
	}
	if err := cfg.validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

// PersonalOptions carries what the personal-mode CLI resolves before a
// configuration can exist: the listeners it parsed, the directory it opened and
// the secrets that directory generated.
type PersonalOptions struct {
	HTTPAddr      string
	SOCKSAddr     string
	AdminToken    string
	SOCKSUsername string
	SOCKSPassword string
	AuditLogPath  string
	FrontendDir   string
	ServerURL     string
	ServerName    string
	CertPin       string
}

// Personal assembles the personal-mode configuration. Nothing is read from the
// environment: AGENT_TOKENS_JSON, ADMIN_TOKEN and SOCKS_PASSWORD have no part
// in this mode because the tokens are minted by pairing and the credentials
// live in the state directory.
func Personal(options PersonalOptions) (Config, error) {
	cfg := Config{
		Mode:      ModePersonal,
		HTTPAddr:  options.HTTPAddr,
		SOCKSAddr: options.SOCKSAddr,
		// The UDP relay stays on loopback with the SOCKS listener, so an
		// authenticated open proxy is never published to the local network.
		PublicProxyHost:          "127.0.0.1",
		UDPBindHost:              "127.0.0.1",
		UDPPortStart:             defaultUDPPortStart,
		UDPPortEnd:               defaultUDPPortEnd,
		AdminToken:               options.AdminToken,
		SOCKSUsername:            options.SOCKSUsername,
		SOCKSPassword:            options.SOCKSPassword,
		AgentTokens:              map[string]string{},
		NodeOfflineAfter:         defaultNodeOfflineAfter,
		CommandWait:              defaultCommandWait,
		OpenTimeout:              defaultOpenTimeout,
		IdleTimeout:              defaultIdleTimeout,
		MaxCircuitsPerNode:       defaultMaxCircuitsPerNode,
		MaxBytesPerCircuit:       defaultMaxBytesPerCircuit,
		AllowPrivateDestinations: false,
		// A person is reading this terminal, not a log shipper.
		LogJSON:      false,
		AuditLogPath: options.AuditLogPath,
		ServerURL:    options.ServerURL,
		ServerName:   options.ServerName,
		CertPin:      options.CertPin,
		FrontendDir:  options.FrontendDir,
	}
	if err := validateServerURL(cfg.ServerURL); err != nil {
		return Config{}, fmt.Errorf("invalid server URL %q: %w", cfg.ServerURL, err)
	}
	if len(cfg.ServerName) > maxServerNameLength {
		return Config{}, fmt.Errorf("server name must contain at most %d bytes", maxServerNameLength)
	}
	if strings.ContainsRune(cfg.FrontendDir, '\x00') {
		return Config{}, fmt.Errorf("frontend directory must not contain a null byte")
	}
	if err := cfg.validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

// validate holds the invariants both modes share. The agent-token loop is a
// no-op in personal mode, where the map is empty because tokens are minted by
// pairing rather than configured.
func (c Config) validate() error {
	if c.UDPPortStart < 1024 || c.UDPPortEnd < c.UDPPortStart || c.UDPPortEnd-c.UDPPortStart > 1024 {
		return fmt.Errorf("invalid UDP port range %d-%d", c.UDPPortStart, c.UDPPortEnd)
	}
	if len(c.AdminToken) < 16 || len(c.AdminToken) > 4096 {
		return fmt.Errorf("ADMIN_TOKEN must contain 16-4096 bytes")
	}
	if len(c.SOCKSUsername) < 1 || len(c.SOCKSUsername) > 255 {
		return fmt.Errorf("SOCKS username must contain 1-255 bytes")
	}
	if len(c.SOCKSPassword) < 16 || len(c.SOCKSPassword) > 255 {
		return fmt.Errorf("SOCKS password must contain 16-255 bytes")
	}
	if c.MaxCircuitsPerNode < 1 || c.MaxCircuitsPerNode > 65535 {
		return fmt.Errorf("MAX_CIRCUITS_PER_NODE must be between 1 and 65535")
	}
	if c.MaxBytesPerCircuit < 1<<20 {
		return fmt.Errorf("MAX_BYTES_PER_CIRCUIT must be at least 1048576")
	}
	if !strings.HasPrefix(c.AuditLogPath, "/") || strings.ContainsRune(c.AuditLogPath, '\x00') {
		return fmt.Errorf("AUDIT_LOG_PATH must be an absolute path")
	}
	if c.NodeOfflineAfter <= 0 || c.CommandWait <= 0 || c.OpenTimeout <= 0 || c.IdleTimeout <= 0 {
		return fmt.Errorf("timeouts must be positive")
	}
	if err := validatePublicHost(c.PublicProxyHost); err != nil {
		return fmt.Errorf("invalid PUBLIC_PROXY_HOST: %w", err)
	}
	for nodeID, token := range c.AgentTokens {
		if !validNodeID(nodeID) {
			return fmt.Errorf("AGENT_TOKENS_JSON contains invalid node ID %q", nodeID)
		}
		if len(strings.TrimSpace(token)) < 16 || len(token) > 4096 {
			return fmt.Errorf("agent token for %q must contain 16-4096 bytes", nodeID)
		}
	}
	return nil
}

// validateServerURL enforces the onboarding contract's server field: an https
// origin with no path, query, fragment or userinfo.
func validateServerURL(value string) error {
	parsed, err := url.Parse(value)
	if err != nil {
		return err
	}
	if parsed.Scheme != "https" {
		return fmt.Errorf("expected an https origin")
	}
	if parsed.Host == "" {
		return fmt.Errorf("expected a host")
	}
	if parsed.User != nil {
		return fmt.Errorf("userinfo is not allowed")
	}
	if parsed.Path != "" || parsed.RawQuery != "" || parsed.Fragment != "" {
		return fmt.Errorf("path, query and fragment are not allowed")
	}
	host := parsed.Hostname()
	if host == "" {
		return fmt.Errorf("expected a host")
	}
	if port := parsed.Port(); port != "" {
		number, err := strconv.Atoi(port)
		if err != nil || number < 1 || number > 65535 {
			return fmt.Errorf("invalid port %q", port)
		}
	}
	return validatePublicHost(host)
}

func validNodeID(value string) bool {
	if len(value) < 1 || len(value) > 64 {
		return false
	}
	for _, character := range value {
		if (character < 'a' || character > 'z') && (character < 'A' || character > 'Z') &&
			(character < '0' || character > '9') && character != '.' && character != '_' && character != '-' {
			return false
		}
	}
	return true
}

func validatePublicHost(value string) error {
	host := strings.TrimSpace(strings.TrimSuffix(value, "."))
	if host == "" || len(host) > 253 || strings.ContainsAny(host, "/\\@[] ") {
		return fmt.Errorf("expected a DNS name or IP address without a port")
	}
	if net.ParseIP(host) != nil {
		return nil
	}
	labels := strings.Split(host, ".")
	for _, label := range labels {
		if len(label) < 1 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return fmt.Errorf("invalid DNS label")
		}
		for _, character := range label {
			if (character < 'a' || character > 'z') && (character < 'A' || character > 'Z') &&
				(character < '0' || character > '9') && character != '-' {
				return fmt.Errorf("invalid DNS label")
			}
		}
	}
	return nil
}

func env(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func envInt(key string, fallback int) (int, error) {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback, nil
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return 0, fmt.Errorf("parse %s: %w", key, err)
	}
	return parsed, nil
}

func envInt64(key string, fallback int64) (int64, error) {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback, nil
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("parse %s: %w", key, err)
	}
	return parsed, nil
}

func envBool(key string, fallback bool) (bool, error) {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback, nil
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return false, fmt.Errorf("parse %s: %w", key, err)
	}
	return parsed, nil
}

func envDuration(key string, fallback time.Duration) (time.Duration, error) {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback, nil
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return 0, fmt.Errorf("parse %s: %w", key, err)
	}
	return parsed, nil
}
