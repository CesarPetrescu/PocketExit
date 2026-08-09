package security

import (
	"net"
	"testing"
)

func TestValidateHostBlocksLocalhost(t *testing.T) {
	for _, host := range []string{"localhost", "api.LOCALHOST.", "127.0.0.1", "::1", "::ffff:127.0.0.1"} {
		if err := ValidateHost(host, false); err == nil {
			t.Fatalf("expected %q to be blocked", host)
		}
	}
}

func TestValidateIPRanges(t *testing.T) {
	for _, value := range []string{
		"10.0.0.1",
		"100.64.0.1",
		"169.254.169.254",
		"192.168.1.1",
		"203.0.113.10",
		"fc00::1",
		"fe80::1",
		"2001:db8::1",
		"::ffff:10.0.0.1",
	} {
		if err := ValidateIP(net.ParseIP(value), false); err == nil {
			t.Fatalf("expected %s to be blocked", value)
		}
	}
	for _, value := range []string{"1.1.1.1", "8.8.8.8", "2606:4700:4700::1111"} {
		if err := ValidateIP(net.ParseIP(value), false); err != nil {
			t.Fatalf("expected %s to be allowed: %v", value, err)
		}
	}
}

func TestAllowPrivateOverride(t *testing.T) {
	if err := ValidateIP(net.ParseIP("127.0.0.1"), true); err != nil {
		t.Fatalf("expected private override to permit address: %v", err)
	}
}
