package personal

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/asn1"
	"encoding/base64"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

var basicConstraintsOID = asn1.ObjectIdentifier{2, 5, 29, 19}

func seedCertificate(t *testing.T, directory string, hostIPs []net.IP, issuedAt time.Time) (Certificate, *ecdsa.PrivateKey) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	certificate, err := issueCertificate(directory, hostIPs, key, issuedAt)
	if err != nil {
		t.Fatal(err)
	}
	return certificate, key
}

func TestIssueCertificateFollowsTheContract(t *testing.T) {
	directory := t.TempDir()
	hostIPs := []net.IP{net.ParseIP("192.168.1.50"), net.ParseIP("2001:db8::5")}
	certificate, key := seedCertificate(t, directory, hostIPs, time.Now())

	leaf := certificate.Leaf
	if leaf.Subject.CommonName != certCommonName || leaf.Issuer.CommonName != certCommonName {
		t.Fatalf("unexpected subject/issuer: %q/%q", leaf.Subject.CommonName, leaf.Issuer.CommonName)
	}
	// The leaf signs itself, so it is checked directly: CheckSignatureFrom
	// insists on a CA parent, which a CA:FALSE certificate is not.
	if err := leaf.CheckSignature(leaf.SignatureAlgorithm, leaf.RawTBSCertificate, leaf.Signature); err != nil {
		t.Fatalf("certificate is not self-signed: %v", err)
	}
	if leaf.SignatureAlgorithm != x509.ECDSAWithSHA256 {
		t.Fatalf("unexpected signature algorithm %v", leaf.SignatureAlgorithm)
	}
	public, ok := leaf.PublicKey.(*ecdsa.PublicKey)
	if !ok || public.Curve != elliptic.P256() {
		t.Fatalf("unexpected public key %T", leaf.PublicKey)
	}
	if validity := leaf.NotAfter.Sub(leaf.NotBefore); validity != certValidity {
		t.Fatalf("validity is %v, expected %v", validity, certValidity)
	}
	if leaf.KeyUsage != x509.KeyUsageDigitalSignature|x509.KeyUsageKeyEncipherment {
		t.Fatalf("unexpected key usage %v", leaf.KeyUsage)
	}
	if len(leaf.ExtKeyUsage) != 1 || leaf.ExtKeyUsage[0] != x509.ExtKeyUsageServerAuth {
		t.Fatalf("unexpected extended key usage %v", leaf.ExtKeyUsage)
	}
	if leaf.IsCA || !leaf.BasicConstraintsValid {
		t.Fatalf("expected CA:FALSE with basic constraints present")
	}
	critical := false
	for _, extension := range leaf.Extensions {
		if extension.Id.Equal(basicConstraintsOID) {
			critical = extension.Critical
		}
	}
	if !critical {
		t.Fatal("basic constraints extension is not critical")
	}
	if len(leaf.DNSNames) != 1 || leaf.DNSNames[0] != "localhost" {
		t.Fatalf("unexpected DNS SANs %v", leaf.DNSNames)
	}
	for _, expected := range append([]net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("::1")}, hostIPs...) {
		if !coversIPs(leaf, []net.IP{expected}) {
			t.Fatalf("SAN set is missing %s: %v", expected, leaf.IPAddresses)
		}
	}

	certInfo, err := os.Stat(certificate.CertPath)
	if err != nil {
		t.Fatal(err)
	}
	if mode := certInfo.Mode().Perm(); mode != 0o644 {
		t.Fatalf("certificate mode is %#o, expected 0644", mode)
	}
	keyInfo, err := os.Stat(certificate.KeyPath)
	if err != nil {
		t.Fatal(err)
	}
	if mode := keyInfo.Mode().Perm(); mode != 0o600 {
		t.Fatalf("key mode is %#o, expected 0600", mode)
	}

	reloaded, err := loadCertificate(directory)
	if err != nil {
		t.Fatalf("reload: %v", err)
	}
	if reloaded.Pin != certificate.Pin {
		t.Fatal("pin changed across a reload")
	}
	if reloaded.Issued {
		t.Fatal("a reload reported a fresh issue")
	}
	if !reloaded.TLS.PrivateKey.(*ecdsa.PrivateKey).Equal(key) {
		t.Fatal("reloaded key differs from the generated key")
	}
}

func TestEnsureCertificateRegenerationTriggers(t *testing.T) {
	hostIPs := []net.IP{net.ParseIP("192.168.1.50")}
	tests := []struct {
		name       string
		issuedAgo  time.Duration
		prepare    func(t *testing.T, directory string)
		hostIPs    []net.IP
		wantIssued bool
	}{
		{name: "fresh and covering", hostIPs: hostIPs},
		{
			name:       "missing certificate",
			prepare:    func(t *testing.T, directory string) { removeFile(t, filepath.Join(directory, certFileName)) },
			hostIPs:    hostIPs,
			wantIssued: true,
		},
		{
			name:       "missing key",
			prepare:    func(t *testing.T, directory string) { removeFile(t, filepath.Join(directory, keyFileName)) },
			hostIPs:    hostIPs,
			wantIssued: true,
		},
		{
			name: "unparsable certificate",
			prepare: func(t *testing.T, directory string) {
				writeFile(t, filepath.Join(directory, certFileName), "not a certificate")
			},
			hostIPs:    hostIPs,
			wantIssued: true,
		},
		{
			name:       "unparsable key",
			prepare:    func(t *testing.T, directory string) { writeFile(t, filepath.Join(directory, keyFileName), "not a key") },
			hostIPs:    hostIPs,
			wantIssued: true,
		},
		{
			name: "key does not match the certificate",
			prepare: func(t *testing.T, directory string) {
				other := filepath.Join(t.TempDir(), "other")
				if err := os.MkdirAll(other, 0o700); err != nil {
					t.Fatal(err)
				}
				seedCertificate(t, other, hostIPs, time.Now())
				copyFile(t, filepath.Join(other, keyFileName), filepath.Join(directory, keyFileName))
			},
			hostIPs:    hostIPs,
			wantIssued: true,
		},
		{name: "expired", issuedAgo: certValidity + 24*time.Hour, hostIPs: hostIPs, wantIssued: true},
		{name: "inside the renewal window", issuedAgo: certValidity - certRenewBefore + time.Hour, hostIPs: hostIPs, wantIssued: true},
		{name: "just outside the renewal window", issuedAgo: certValidity - certRenewBefore - 24*time.Hour, hostIPs: hostIPs},
		// A moved laptop re-issues the certificate over the same key, so the
		// SAN set follows the machine while the pin -- and every phone paired
		// against it -- survives. Issued stays false: it means the key rotated.
		{
			name:    "new host address",
			hostIPs: append([]net.IP{net.ParseIP("10.0.0.7")}, hostIPs...),
		},
		{name: "host address removed", hostIPs: nil},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			directory := t.TempDir()
			now := time.Now()
			seeded, _ := seedCertificate(t, directory, hostIPs, now.Add(-test.issuedAgo))
			if test.prepare != nil {
				test.prepare(t, directory)
			}

			ensured, err := ensureCertificate(directory, test.hostIPs, now)
			if err != nil {
				t.Fatal(err)
			}
			if ensured.Issued != test.wantIssued {
				t.Fatalf("Issued=%v, expected %v", ensured.Issued, test.wantIssued)
			}
			if test.wantIssued && ensured.Pin == seeded.Pin {
				t.Fatal("a regenerated certificate reused the pinned key")
			}
			if !test.wantIssued && ensured.Pin != seeded.Pin {
				t.Fatal("the pin changed without a regeneration")
			}
			if !coversIPs(ensured.Leaf, test.hostIPs) {
				t.Fatalf("SAN set does not cover %v: %v", test.hostIPs, ensured.Leaf.IPAddresses)
			}
			if _, err := loadCertificate(directory); err != nil {
				t.Fatalf("the ensured certificate does not reload: %v", err)
			}
		})
	}
}

func TestPinIsStableAcrossAReissueThatReusesTheKey(t *testing.T) {
	directory := t.TempDir()
	first, key := seedCertificate(t, directory, []net.IP{net.ParseIP("192.168.1.50")}, time.Now())

	second, err := IssueCertificate(directory, []net.IP{net.ParseIP("10.0.0.7")}, key)
	if err != nil {
		t.Fatal(err)
	}
	if second.Pin != first.Pin {
		t.Fatalf("pin changed for a re-issue with the same key: %s vs %s", second.Pin, first.Pin)
	}
	if second.Leaf.SerialNumber.Cmp(first.Leaf.SerialNumber) == 0 {
		t.Fatal("the re-issued certificate reused the serial number")
	}
	if coversIPs(first.Leaf, []net.IP{net.ParseIP("10.0.0.7")}) {
		t.Fatal("the first certificate already covered the new address")
	}
	if !coversIPs(second.Leaf, []net.IP{net.ParseIP("10.0.0.7")}) {
		t.Fatal("the re-issued certificate does not cover the new address")
	}
}

func TestPinChangesOnKeyRotation(t *testing.T) {
	directory := t.TempDir()
	first, _ := seedCertificate(t, directory, nil, time.Now())
	second, _ := seedCertificate(t, directory, nil, time.Now())
	if second.Pin == first.Pin {
		t.Fatal("a new key produced the same pin")
	}
}

func TestPinCoversTheSubjectPublicKeyInfo(t *testing.T) {
	directory := t.TempDir()
	certificate, _ := seedCertificate(t, directory, nil, time.Now())

	spki, err := x509.MarshalPKIXPublicKey(certificate.Leaf.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(spki)
	expected := base64.RawURLEncoding.EncodeToString(digest[:])
	if certificate.Pin != expected {
		t.Fatalf("pin %s does not match the SPKI digest %s", certificate.Pin, expected)
	}

	whole := sha256.Sum256(certificate.Leaf.Raw)
	if certificate.Pin == base64.RawURLEncoding.EncodeToString(whole[:]) {
		t.Fatal("the pin hashes the whole certificate")
	}
	pin, err := Pin(certificate.Leaf)
	if err != nil {
		t.Fatal(err)
	}
	if pin != expected {
		t.Fatalf("Pin returned %s, expected %s", pin, expected)
	}
	if _, err := Pin(nil); err == nil {
		t.Fatal("expected Pin(nil) to fail")
	}
}

func TestEnsureCertificateServesTLSAndPresentsThePinnedKey(t *testing.T) {
	directory := t.TempDir()
	certificate, err := EnsureCertificate(directory, []net.IP{net.ParseIP("192.168.1.50")})
	if err != nil {
		t.Fatal(err)
	}

	serverSide, clientSide := net.Pipe()
	defer serverSide.Close()
	defer clientSide.Close()
	server := tls.Server(serverSide, &tls.Config{Certificates: []tls.Certificate{certificate.TLS}})
	handshake := make(chan error, 1)
	go func() { handshake <- server.Handshake() }()

	// The Android agent trusts the key, not the chain: it skips the platform
	// trust store and compares the leaf's SPKI pin.
	client := tls.Client(clientSide, &tls.Config{
		InsecureSkipVerify: true,
		VerifyPeerCertificate: func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			leaf, err := x509.ParseCertificate(rawCerts[0])
			if err != nil {
				return err
			}
			pin, err := Pin(leaf)
			if err != nil {
				return err
			}
			if pin != certificate.Pin {
				t.Errorf("presented pin %s, expected %s", pin, certificate.Pin)
			}
			return nil
		},
	})
	if err := client.Handshake(); err != nil {
		t.Fatalf("client handshake: %v", err)
	}
	if err := <-handshake; err != nil {
		t.Fatalf("server handshake: %v", err)
	}
}

func TestLocalIPsAreDedupedAndSorted(t *testing.T) {
	addresses, err := LocalIPs()
	if err != nil {
		t.Fatal(err)
	}
	seen := make(map[string]struct{}, len(addresses))
	for index, ip := range addresses {
		if ip.IsLoopback() {
			t.Fatalf("%s is a loopback address", ip)
		}
		if _, duplicate := seen[string(ip.To16())]; duplicate {
			t.Fatalf("%s appears twice", ip)
		}
		seen[string(ip.To16())] = struct{}{}
		if index > 0 && !sortedPair(addresses[index-1], ip) {
			t.Fatalf("%s sorts before %s", ip, addresses[index-1])
		}
	}

	repeat, err := LocalIPs()
	if err != nil {
		t.Fatal(err)
	}
	if len(repeat) != len(addresses) {
		t.Fatalf("LocalIPs is not deterministic: %v vs %v", repeat, addresses)
	}
	for index := range repeat {
		if !repeat[index].Equal(addresses[index]) {
			t.Fatalf("LocalIPs is not deterministic: %v vs %v", repeat, addresses)
		}
	}
}

func sortedPair(left, right net.IP) bool {
	ordered := []net.IP{right, left}
	sortIPs(ordered)
	return ordered[0].Equal(left)
}

func removeFile(t *testing.T, path string) {
	t.Helper()
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
}

func writeFile(t *testing.T, path, contents string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(contents), 0o600); err != nil {
		t.Fatal(err)
	}
}

func copyFile(t *testing.T, source, destination string) {
	t.Helper()
	payload, err := os.ReadFile(source)
	if err != nil {
		t.Fatal(err)
	}
	writeFile(t, destination, string(payload))
}

// A laptop that joins a different network gets a different address, which the
// certificate has to start covering. Doing that by rotating the key would
// change the pin and lock out every phone already paired against it, with
// re-pairing the only way back -- the precise failure that pinning the
// SubjectPublicKeyInfo instead of the whole certificate exists to avoid.
func TestMovingNetworksKeepsThePinAndTheAlreadyPairedPhones(t *testing.T) {
	directory := t.TempDir()
	now := time.Now()
	home := []net.IP{net.ParseIP("192.168.1.50").To4()}
	cafe := []net.IP{net.ParseIP("10.24.9.3").To4()}

	atHome, err := ensureCertificate(directory, home, now)
	if err != nil {
		t.Fatal(err)
	}
	if !atHome.Issued {
		t.Fatal("the first certificate should report that it issued a key")
	}

	atCafe, err := ensureCertificate(directory, cafe, now)
	if err != nil {
		t.Fatal(err)
	}
	if atCafe.Pin != atHome.Pin {
		t.Fatalf("the pin changed on a network move: %q became %q", atHome.Pin, atCafe.Pin)
	}
	if atCafe.Issued {
		t.Fatal("Issued must stay false when the key was reused")
	}
	if !coversIPs(atCafe.Leaf, cafe) {
		t.Fatalf("the re-issued certificate does not cover %v: %v", cafe, atCafe.Leaf.IPAddresses)
	}
	if atCafe.Leaf.SerialNumber.Cmp(atHome.Leaf.SerialNumber) == 0 {
		t.Fatal("the certificate was not actually re-issued")
	}

	// Expiry is the one condition that legitimately rotates the key, and the
	// pin has to move with it.
	expired, err := ensureCertificate(directory, cafe, now.Add(certValidity+time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if !expired.Issued || expired.Pin == atHome.Pin {
		t.Fatal("an expired certificate must rotate the key and the pin")
	}
}
