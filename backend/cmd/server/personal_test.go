package main

import (
	"net"
	"strings"
	"testing"
	"unicode/utf8"
)

// advertisedURL decides the origin printed into the QR a phone scans. Getting
// it wrong is not a cosmetic bug: a phone cannot reach 127.0.0.1, so advertising
// loopback from a wildcard listener makes pairing impossible.
func TestAdvertisedURL(t *testing.T) {
	hostIPs := []net.IP{net.ParseIP("192.168.1.50"), net.ParseIP("fe80::1")}

	tests := []struct {
		name    string
		address net.Addr
		hostIPs []net.IP
		want    string
	}{
		{
			name:    "a listener bound to one interface advertises that interface",
			address: &net.TCPAddr{IP: net.ParseIP("192.168.1.50"), Port: 8443},
			hostIPs: hostIPs,
			want:    "https://192.168.1.50:8443",
		},
		{
			name:    "a wildcard listener advertises the host's first address",
			address: &net.TCPAddr{IP: net.IPv4zero, Port: 8443},
			hostIPs: hostIPs,
			want:    "https://192.168.1.50:8443",
		},
		{
			name:    "an IPv6 wildcard listener also advertises the host address",
			address: &net.TCPAddr{IP: net.IPv6unspecified, Port: 9000},
			hostIPs: hostIPs,
			want:    "https://192.168.1.50:9000",
		},
		{
			name:    "a host with no LAN address falls back to loopback",
			address: &net.TCPAddr{IP: net.IPv4zero, Port: 8443},
			hostIPs: nil,
			want:    "https://127.0.0.1:8443",
		},
		{
			name:    "an explicit loopback listener is advertised as loopback",
			address: &net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: 18443},
			hostIPs: hostIPs,
			want:    "https://127.0.0.1:18443",
		},
		{
			name:    "an IPv6 host is bracketed so the URI parses",
			address: &net.TCPAddr{IP: net.ParseIP("2001:db8::5"), Port: 8443},
			hostIPs: hostIPs,
			want:    "https://[2001:db8::5]:8443",
		},
		{
			name:    "a non-TCP address falls back to the documented default",
			address: &net.UnixAddr{Name: "/tmp/socket", Net: "unix"},
			hostIPs: hostIPs,
			want:    "https://127.0.0.1:8443",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := advertisedURL(test.address, test.hostIPs); got != test.want {
				t.Fatalf("advertisedURL = %q, want %q", got, test.want)
			}
		})
	}
}

// The wildcard case takes hostIPs[0], so the ordering personal.LocalIPs applies
// decides what a phone is told to dial. An IPv6 link-local address is not
// reachable without a zone identifier, so IPv4 has to come first.
func TestAdvertisedURLPrefersAReachableAddress(t *testing.T) {
	// The order personal.LocalIPs produces: IPv4 sorts ahead of IPv6 because
	// its 16-byte form is the ::ffff: mapping, which begins with zero bytes.
	hostIPs := []net.IP{
		net.ParseIP("192.168.1.50").To4(),
		net.ParseIP("2001:db8::5"),
		net.ParseIP("fe80::1"),
	}
	got := advertisedURL(&net.TCPAddr{IP: net.IPv4zero, Port: 8443}, hostIPs)
	if strings.Contains(got, "fe80") {
		t.Fatalf("advertised a link-local address a phone cannot dial: %q", got)
	}
	if got != "https://192.168.1.50:8443" {
		t.Fatalf("advertisedURL = %q, want the IPv4 LAN address", got)
	}
}

func TestLoopbackAddress(t *testing.T) {
	tests := []struct {
		address string
		want    bool
	}{
		{"127.0.0.1:1080", true},
		{"127.0.0.53:1080", true},
		{"[::1]:1080", true},
		{"localhost:1080", true},
		{"LocalHost:1080", true},
		{"0.0.0.0:1080", false},
		{"192.168.1.50:1080", false},
		{"[2001:db8::5]:1080", false},
		{"example.com:1080", false},
		{"", false},
		{"1080", false},
		{":1080", false},
	}

	for _, test := range tests {
		t.Run(test.address, func(t *testing.T) {
			if got := loopbackAddress(test.address); got != test.want {
				t.Fatalf("loopbackAddress(%q) = %v, want %v", test.address, got, test.want)
			}
		})
	}
}

func TestTruncateServerName(t *testing.T) {
	tests := []struct {
		name  string
		input string
		want  string
	}{
		{name: "short names pass through", input: "Cesar's laptop", want: "Cesar's laptop"},
		{name: "surrounding whitespace is trimmed", input: "  laptop  ", want: "laptop"},
		{name: "empty stays empty", input: "   ", want: ""},
		{
			name:  "exactly at the ceiling is kept whole",
			input: strings.Repeat("a", 64),
			want:  strings.Repeat("a", 64),
		},
		{
			name:  "one over the ceiling is cut",
			input: strings.Repeat("a", 65),
			want:  strings.Repeat("a", 64),
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := truncateServerName(test.input); got != test.want {
				t.Fatalf("truncateServerName(%q) = %q, want %q", test.input, got, test.want)
			}
		})
	}
}

// Truncation used to slice bytes, which cuts a multi-byte character in half and
// puts invalid UTF-8 into the onboarding URI the phone parses.
func TestTruncateServerNameNeverSplitsARune(t *testing.T) {
	for _, input := range []string{
		strings.Repeat("é", 100),
		strings.Repeat("日", 100),
		strings.Repeat("🔒", 100),
		strings.Repeat("a", 63) + strings.Repeat("é", 10),
	} {
		got := truncateServerName(input)
		if !utf8.ValidString(got) {
			t.Fatalf("truncateServerName(%q…) produced invalid UTF-8: %q", input[:12], got)
		}
		if count := utf8.RuneCountInString(got); count > maxServerNameRunes {
			t.Fatalf("truncateServerName kept %d runes, want at most %d", count, maxServerNameRunes)
		}
	}
}
