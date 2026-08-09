package security

import (
	"fmt"
	"net"
	"strings"
)

var blockedNetworks = mustCIDRs(
	"0.0.0.0/8",
	"10.0.0.0/8",
	"100.64.0.0/10",
	"127.0.0.0/8",
	"169.254.0.0/16",
	"172.16.0.0/12",
	"192.0.0.0/24",
	"192.0.2.0/24",
	"192.168.0.0/16",
	"198.18.0.0/15",
	"198.51.100.0/24",
	"203.0.113.0/24",
	"224.0.0.0/4",
	"240.0.0.0/4",
	"::/128",
	"::1/128",
	"fc00::/7",
	"fe80::/10",
	"ff00::/8",
	"2001:db8::/32",
)

func ValidateHost(host string, allowPrivate bool) error {
	host = strings.TrimSpace(strings.TrimSuffix(host, "."))
	if host == "" || len(host) > 253 {
		return fmt.Errorf("invalid destination host")
	}
	if strings.EqualFold(host, "localhost") || strings.HasSuffix(strings.ToLower(host), ".localhost") {
		return fmt.Errorf("localhost destinations are blocked")
	}
	if ip := net.ParseIP(host); ip != nil {
		return ValidateIP(ip, allowPrivate)
	}
	return nil
}

func ValidateIP(ip net.IP, allowPrivate bool) error {
	if ip == nil {
		return fmt.Errorf("invalid destination IP")
	}
	if allowPrivate {
		return nil
	}
	// Normalize IPv4-compatible and IPv4-mapped IPv6 forms before matching.
	// This prevents an address such as ::ffff:127.0.0.1 bypassing IPv4 ACLs.
	if ipv4 := ip.To4(); ipv4 != nil {
		ip = ipv4
	}
	for _, network := range blockedNetworks {
		if network.Contains(ip) {
			return fmt.Errorf("private, local, multicast, or documentation destinations are blocked")
		}
	}
	return nil
}

func mustCIDRs(values ...string) []*net.IPNet {
	result := make([]*net.IPNet, 0, len(values))
	for _, value := range values {
		_, network, err := net.ParseCIDR(value)
		if err != nil {
			panic(err)
		}
		result = append(result, network)
	}
	return result
}
