package config

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
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
}

func Load() (Config, error) {
	cfg := Config{
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
	if cfg.UDPPortStart, err = envInt("UDP_PORT_START", 12000); err != nil {
		return Config{}, err
	}
	if cfg.UDPPortEnd, err = envInt("UDP_PORT_END", 12031); err != nil {
		return Config{}, err
	}
	if cfg.NodeOfflineAfter, err = envDuration("NODE_OFFLINE_AFTER", 45*time.Second); err != nil {
		return Config{}, err
	}
	if cfg.CommandWait, err = envDuration("COMMAND_WAIT", 5*time.Second); err != nil {
		return Config{}, err
	}
	if cfg.OpenTimeout, err = envDuration("OPEN_TIMEOUT", 45*time.Second); err != nil {
		return Config{}, err
	}
	if cfg.IdleTimeout, err = envDuration("IDLE_TIMEOUT", 2*time.Minute); err != nil {
		return Config{}, err
	}
	if cfg.MaxCircuitsPerNode, err = envInt("MAX_CIRCUITS_PER_NODE", 128); err != nil {
		return Config{}, err
	}
	if cfg.MaxBytesPerCircuit, err = envInt64("MAX_BYTES_PER_CIRCUIT", 1<<30); err != nil {
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
	if cfg.UDPPortStart < 1024 || cfg.UDPPortEnd < cfg.UDPPortStart || cfg.UDPPortEnd-cfg.UDPPortStart > 1024 {
		return Config{}, fmt.Errorf("invalid UDP port range %d-%d", cfg.UDPPortStart, cfg.UDPPortEnd)
	}
	if len(cfg.AdminToken) < 16 || len(cfg.AdminToken) > 4096 {
		return Config{}, fmt.Errorf("ADMIN_TOKEN must contain 16-4096 bytes")
	}
	if len(cfg.SOCKSUsername) < 1 || len(cfg.SOCKSUsername) > 255 {
		return Config{}, fmt.Errorf("SOCKS username must contain 1-255 bytes")
	}
	if len(cfg.SOCKSPassword) < 16 || len(cfg.SOCKSPassword) > 255 {
		return Config{}, fmt.Errorf("SOCKS password must contain 16-255 bytes")
	}
	if cfg.MaxCircuitsPerNode < 1 || cfg.MaxCircuitsPerNode > 65535 {
		return Config{}, fmt.Errorf("MAX_CIRCUITS_PER_NODE must be between 1 and 65535")
	}
	if cfg.MaxBytesPerCircuit < 1<<20 {
		return Config{}, fmt.Errorf("MAX_BYTES_PER_CIRCUIT must be at least 1048576")
	}
	if !strings.HasPrefix(cfg.AuditLogPath, "/") || strings.ContainsRune(cfg.AuditLogPath, '\x00') {
		return Config{}, fmt.Errorf("AUDIT_LOG_PATH must be an absolute path")
	}
	if cfg.NodeOfflineAfter <= 0 || cfg.CommandWait <= 0 || cfg.OpenTimeout <= 0 || cfg.IdleTimeout <= 0 {
		return Config{}, fmt.Errorf("timeouts must be positive")
	}
	if err := validatePublicHost(cfg.PublicProxyHost); err != nil {
		return Config{}, fmt.Errorf("invalid PUBLIC_PROXY_HOST: %w", err)
	}
	for nodeID, token := range cfg.AgentTokens {
		if !validNodeID(nodeID) {
			return Config{}, fmt.Errorf("AGENT_TOKENS_JSON contains invalid node ID %q", nodeID)
		}
		if len(strings.TrimSpace(token)) < 16 || len(token) > 4096 {
			return Config{}, fmt.Errorf("agent token for %q must contain 16-4096 bytes", nodeID)
		}
	}
	return cfg, nil
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
