package personal

import (
	"bytes"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"sort"
	"time"
)

const (
	certFileName    = "tls.crt"
	keyFileName     = "tls.key"
	certCommonName  = "PocketExit Personal"
	certValidity    = 397 * 24 * time.Hour
	certRenewBefore = 30 * 24 * time.Hour
)

type Certificate struct {
	Leaf     *x509.Certificate
	TLS      tls.Certificate
	Pin      string
	CertPath string
	KeyPath  string
	// Issued reports whether this call wrote a new key pair, which invalidates
	// the pin held by already-paired phones.
	Issued bool
}

func EnsureCertificate(directory string, hostIPs []net.IP) (Certificate, error) {
	return ensureCertificate(directory, hostIPs, time.Now())
}

func IssueCertificate(directory string, hostIPs []net.IP, key *ecdsa.PrivateKey) (Certificate, error) {
	return issueCertificate(directory, hostIPs, key, time.Now())
}

func Pin(certificate *x509.Certificate) (string, error) {
	if certificate == nil {
		return "", fmt.Errorf("certificate is nil")
	}
	return pinPublicKey(certificate.PublicKey)
}

func LocalIPs() ([]net.IP, error) {
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil, fmt.Errorf("list network interfaces: %w", err)
	}
	seen := make(map[string]struct{})
	result := make([]net.IP, 0, len(interfaces))
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addresses, err := iface.Addrs()
		if err != nil {
			// An interface can disappear between the listing and the query.
			continue
		}
		for _, address := range addresses {
			network, ok := address.(*net.IPNet)
			if !ok {
				continue
			}
			ip := canonicalIP(network.IP)
			if ip == nil || ip.IsLoopback() || (!ip.IsGlobalUnicast() && !ip.IsLinkLocalUnicast()) {
				continue
			}
			key := string(ip.To16())
			if _, duplicate := seen[key]; duplicate {
				continue
			}
			seen[key] = struct{}{}
			result = append(result, ip)
		}
	}
	sortIPs(result)
	return result, nil
}

func ensureCertificate(directory string, hostIPs []net.IP, now time.Time) (Certificate, error) {
	existing, err := loadCertificate(directory)
	if err == nil && !expiringSoon(existing.Leaf, now) && coversIPs(existing.Leaf, hostIPs) {
		return existing, nil
	}
	// Missing, unparsable, expiring or no longer covering the host's addresses:
	// the contract regenerates the key alongside the certificate.
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return Certificate{}, fmt.Errorf("generate P-256 key: %w", err)
	}
	return issueCertificate(directory, hostIPs, key, now)
}

func issueCertificate(directory string, hostIPs []net.IP, key *ecdsa.PrivateKey, now time.Time) (Certificate, error) {
	if key == nil {
		return Certificate{}, fmt.Errorf("private key is nil")
	}
	serialLimit := new(big.Int).Lsh(big.NewInt(1), 128)
	serial, err := rand.Int(rand.Reader, serialLimit)
	if err != nil {
		return Certificate{}, fmt.Errorf("generate certificate serial: %w", err)
	}
	if serial.Sign() == 0 {
		serial = big.NewInt(1)
	}

	now = now.UTC().Truncate(time.Second)
	template := x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: certCommonName},
		Issuer:                pkix.Name{CommonName: certCommonName},
		NotBefore:             now,
		NotAfter:              now.Add(certValidity),
		SignatureAlgorithm:    x509.ECDSAWithSHA256,
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IsCA:                  false,
		DNSNames:              []string{"localhost"},
		IPAddresses:           requiredIPs(hostIPs),
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, key.Public(), key)
	if err != nil {
		return Certificate{}, fmt.Errorf("create self-signed certificate: %w", err)
	}
	leaf, err := x509.ParseCertificate(der)
	if err != nil {
		return Certificate{}, fmt.Errorf("parse generated certificate: %w", err)
	}
	pkcs8, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return Certificate{}, fmt.Errorf("encode private key: %w", err)
	}
	pin, err := pinPublicKey(key.Public())
	if err != nil {
		return Certificate{}, err
	}

	certPath := filepath.Join(directory, certFileName)
	keyPath := filepath.Join(directory, keyFileName)
	// The key lands first: a certificate on disk without its key is what the
	// reload path treats as unparsable, and regenerating is cheap.
	if err := writeFileAtomic(keyPath, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: pkcs8}), 0o600); err != nil {
		return Certificate{}, err
	}
	if err := writeFileAtomic(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o644); err != nil {
		return Certificate{}, err
	}
	return Certificate{
		Leaf:     leaf,
		TLS:      tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key, Leaf: leaf},
		Pin:      pin,
		CertPath: certPath,
		KeyPath:  keyPath,
		Issued:   true,
	}, nil
}

func loadCertificate(directory string) (Certificate, error) {
	certPath := filepath.Join(directory, certFileName)
	keyPath := filepath.Join(directory, keyFileName)

	certPEM, err := os.ReadFile(certPath)
	if err != nil {
		return Certificate{}, fmt.Errorf("read %s: %w", certPath, err)
	}
	certBlock, _ := pem.Decode(certPEM)
	if certBlock == nil || certBlock.Type != "CERTIFICATE" {
		return Certificate{}, fmt.Errorf("%s does not contain a PEM certificate", certPath)
	}
	leaf, err := x509.ParseCertificate(certBlock.Bytes)
	if err != nil {
		return Certificate{}, fmt.Errorf("parse %s: %w", certPath, err)
	}

	keyPEM, err := os.ReadFile(keyPath)
	if err != nil {
		return Certificate{}, fmt.Errorf("read %s: %w", keyPath, err)
	}
	keyBlock, _ := pem.Decode(keyPEM)
	if keyBlock == nil || keyBlock.Type != "PRIVATE KEY" {
		return Certificate{}, fmt.Errorf("%s does not contain a PKCS#8 PEM key", keyPath)
	}
	parsed, err := x509.ParsePKCS8PrivateKey(keyBlock.Bytes)
	if err != nil {
		return Certificate{}, fmt.Errorf("parse %s: %w", keyPath, err)
	}
	key, ok := parsed.(*ecdsa.PrivateKey)
	if !ok {
		return Certificate{}, fmt.Errorf("%s is not an ECDSA private key", keyPath)
	}
	public, ok := leaf.PublicKey.(*ecdsa.PublicKey)
	if !ok || !key.PublicKey.Equal(public) {
		return Certificate{}, fmt.Errorf("%s does not match %s", keyPath, certPath)
	}
	pin, err := pinPublicKey(leaf.PublicKey)
	if err != nil {
		return Certificate{}, err
	}
	return Certificate{
		Leaf:     leaf,
		TLS:      tls.Certificate{Certificate: [][]byte{certBlock.Bytes}, PrivateKey: key, Leaf: leaf},
		Pin:      pin,
		CertPath: certPath,
		KeyPath:  keyPath,
	}, nil
}

func pinPublicKey(public crypto.PublicKey) (string, error) {
	// The pin covers the SubjectPublicKeyInfo, not the certificate, so a
	// re-issue that reuses the key keeps paired phones working.
	spki, err := x509.MarshalPKIXPublicKey(public)
	if err != nil {
		return "", fmt.Errorf("encode subject public key info: %w", err)
	}
	digest := sha256.Sum256(spki)
	return base64.RawURLEncoding.EncodeToString(digest[:]), nil
}

func expiringSoon(leaf *x509.Certificate, now time.Time) bool {
	return !now.Add(certRenewBefore).Before(leaf.NotAfter)
}

func coversIPs(leaf *x509.Certificate, hostIPs []net.IP) bool {
	present := make(map[string]struct{}, len(leaf.IPAddresses))
	for _, ip := range leaf.IPAddresses {
		present[string(ip.To16())] = struct{}{}
	}
	for _, ip := range requiredIPs(hostIPs) {
		if _, ok := present[string(ip.To16())]; !ok {
			return false
		}
	}
	return true
}

func requiredIPs(hostIPs []net.IP) []net.IP {
	seen := make(map[string]struct{}, len(hostIPs)+2)
	result := make([]net.IP, 0, len(hostIPs)+2)
	for _, ip := range append([]net.IP{net.IPv4(127, 0, 0, 1), net.IPv6loopback}, hostIPs...) {
		canonical := canonicalIP(ip)
		if canonical == nil {
			continue
		}
		key := string(canonical.To16())
		if _, duplicate := seen[key]; duplicate {
			continue
		}
		seen[key] = struct{}{}
		result = append(result, canonical)
	}
	sortIPs(result)
	return result
}

func canonicalIP(ip net.IP) net.IP {
	if ipv4 := ip.To4(); ipv4 != nil {
		return ipv4
	}
	return ip.To16()
}

func sortIPs(ips []net.IP) {
	sort.Slice(ips, func(i, j int) bool {
		return bytes.Compare(ips[i].To16(), ips[j].To16()) < 0
	})
}
