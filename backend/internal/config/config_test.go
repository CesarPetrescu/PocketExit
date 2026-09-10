package config

import (
	"strings"
	"testing"
)

func setValidEnvironment(t *testing.T) {
	t.Helper()
	t.Setenv("HTTP_ADDR", ":8080")
	t.Setenv("SOCKS_ADDR", ":1080")
	t.Setenv("PUBLIC_PROXY_HOST", "proxy.example.com")
	t.Setenv("UDP_BIND_HOST", "0.0.0.0")
	t.Setenv("UDP_PORT_START", "12000")
	t.Setenv("UDP_PORT_END", "12031")
	t.Setenv("ADMIN_TOKEN", "admin-test-token")
	t.Setenv("SOCKS_USERNAME", "proxy")
	t.Setenv("SOCKS_PASSWORD", "proxy-test-password")
	t.Setenv("AGENT_TOKENS_JSON", `{"phone-a":"agent-test-token"}`)
	t.Setenv("NODE_OFFLINE_AFTER", "45s")
	t.Setenv("COMMAND_WAIT", "5s")
	t.Setenv("OPEN_TIMEOUT", "45s")
	t.Setenv("IDLE_TIMEOUT", "2m")
	t.Setenv("MAX_CIRCUITS_PER_NODE", "128")
	t.Setenv("MAX_BYTES_PER_CIRCUIT", "1073741824")
	t.Setenv("AUDIT_LOG_PATH", "/tmp/pocketexit-audit-test.jsonl")
	t.Setenv("ALLOW_PRIVATE_DESTINATIONS", "false")
	t.Setenv("LOG_JSON", "true")
}

func TestLoadValidConfiguration(t *testing.T) {
	setValidEnvironment(t)
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.PublicProxyHost != "proxy.example.com" || cfg.AgentTokens["phone-a"] != "agent-test-token" {
		t.Fatalf("unexpected config: %+v", cfg)
	}
}

func TestLoadRejectsMalformedValues(t *testing.T) {
	tests := []struct {
		name  string
		key   string
		value string
	}{
		{name: "integer", key: "MAX_CIRCUITS_PER_NODE", value: "many"},
		{name: "boolean", key: "LOG_JSON", value: "sometimes"},
		{name: "duration", key: "OPEN_TIMEOUT", value: "later"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			setValidEnvironment(t)
			t.Setenv(test.key, test.value)
			if _, err := Load(); err == nil {
				t.Fatalf("expected %s=%q to fail", test.key, test.value)
			}
		})
	}
}

func TestLoadRejectsUnsafeRangesAndCredentials(t *testing.T) {
	tests := []struct {
		name  string
		key   string
		value string
	}{
		{name: "privileged UDP port", key: "UDP_PORT_START", value: "53"},
		{name: "zero circuits", key: "MAX_CIRCUITS_PER_NODE", value: "0"},
		{name: "tiny byte quota", key: "MAX_BYTES_PER_CIRCUIT", value: "1000"},
		{name: "oversized SOCKS username", key: "SOCKS_USERNAME", value: strings.Repeat("u", 256)},
		{name: "short admin token", key: "ADMIN_TOKEN", value: "short"},
		{name: "short SOCKS password", key: "SOCKS_PASSWORD", value: "short"},
		{name: "oversized SOCKS password", key: "SOCKS_PASSWORD", value: strings.Repeat("p", 256)},
		{name: "host containing port", key: "PUBLIC_PROXY_HOST", value: "proxy.example.com:443"},
		{name: "empty agent token", key: "AGENT_TOKENS_JSON", value: `{"phone-a":""}`},
		{name: "invalid node ID", key: "AGENT_TOKENS_JSON", value: `{"phone@a":"agent-test-token"}`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			setValidEnvironment(t)
			t.Setenv(test.key, test.value)
			if _, err := Load(); err == nil {
				t.Fatalf("expected %s=%q to fail", test.key, test.value)
			}
		})
	}
}

func TestValidatePublicHost(t *testing.T) {
	for _, valid := range []string{"proxy.example.com", "203.0.113.9", "2001:db8::1"} {
		if err := validatePublicHost(valid); err != nil {
			t.Fatalf("expected %q to be valid: %v", valid, err)
		}
	}
	for _, invalid := range []string{"", "https://proxy.example.com", "proxy.example.com:443", "bad_label.example"} {
		if err := validatePublicHost(invalid); err == nil {
			t.Fatalf("expected %q to be invalid", invalid)
		}
	}
}

func TestLoadRequiresAgentTokenMap(t *testing.T) {
	setValidEnvironment(t)
	t.Setenv("AGENT_TOKENS_JSON", "")
	if _, err := Load(); err == nil {
		t.Fatal("expected AGENT_TOKENS_JSON to be required")
	}
}

func validPersonalOptions() PersonalOptions {
	return PersonalOptions{
		HTTPAddr:      "0.0.0.0:8443",
		SOCKSAddr:     "127.0.0.1:1080",
		AdminToken:    "admin-test-token-2026",
		SOCKSUsername: "proxy",
		SOCKSPassword: "proxy-test-password-2026",
		AuditLogPath:  "/tmp/pocketexit-personal-audit-test.jsonl",
		FrontendDir:   "/srv/pocketexit/frontend",
		ServerURL:     "https://192.168.1.50:8443",
		ServerName:    "cesar-laptop",
		CertPin:       "0uZ9nBRTn0hM1s5Vd4xJ1cV0v2Q3s4T5u6W7x8Y9z0A",
	}
}

func TestPersonalNeedsNoEnvironment(t *testing.T) {
	// Personal mode reads none of the server-mode variables: the credentials
	// come from state.json and the tokens are minted by pairing.
	t.Setenv("AGENT_TOKENS_JSON", "")
	t.Setenv("ADMIN_TOKEN", "")
	t.Setenv("SOCKS_PASSWORD", "")
	if _, err := Load(); err == nil {
		t.Fatal("expected server mode to still require AGENT_TOKENS_JSON")
	}
	cfg, err := Personal(validPersonalOptions())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Mode != ModePersonal || len(cfg.AgentTokens) != 0 {
		t.Fatalf("unexpected config: %+v", cfg)
	}
	if cfg.ServerOrigin() != "https://192.168.1.50:8443" {
		t.Fatalf("unexpected server origin %q", cfg.ServerOrigin())
	}
	if cfg.SOCKSAddr != "127.0.0.1:1080" || cfg.UDPBindHost != "127.0.0.1" || cfg.AllowPrivateDestinations {
		t.Fatalf("unexpected personal defaults: %+v", cfg)
	}
}

func TestPersonalRejectsUnusableValues(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*PersonalOptions)
	}{
		{name: "short admin token", mutate: func(o *PersonalOptions) { o.AdminToken = "short" }},
		{name: "short SOCKS password", mutate: func(o *PersonalOptions) { o.SOCKSPassword = "short" }},
		{name: "relative audit log", mutate: func(o *PersonalOptions) { o.AuditLogPath = "audit.jsonl" }},
		{name: "plaintext server URL", mutate: func(o *PersonalOptions) { o.ServerURL = "http://192.168.1.50:8443" }},
		{name: "server URL with a path", mutate: func(o *PersonalOptions) { o.ServerURL = "https://192.168.1.50:8443/pair" }},
		{name: "server URL with userinfo", mutate: func(o *PersonalOptions) { o.ServerURL = "https://user@192.168.1.50:8443" }},
		{name: "server URL without a host", mutate: func(o *PersonalOptions) { o.ServerURL = "https://" }},
		{name: "oversized server name", mutate: func(o *PersonalOptions) { o.ServerName = strings.Repeat("n", 65) }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			options := validPersonalOptions()
			test.mutate(&options)
			if _, err := Personal(options); err == nil {
				t.Fatal("expected the configuration to be rejected")
			}
		})
	}
}
