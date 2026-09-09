package personal

import (
	"crypto/rand"
	"crypto/subtle"
	"errors"
	"fmt"
	"net"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode"
)

const (
	// Crockford base32: no I, L, O or U, so a code can be read aloud.
	crockfordAlphabet   = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
	pairingCodeLength   = 8
	pairingCodeTTL      = 10 * time.Minute
	pairingCodeAttempts = 5
	pairingRateLimit    = 10
	pairingRateWindow   = 10 * time.Minute
	// Hard ceiling on tracked source addresses, so a flood from spoofed or
	// rotating addresses cannot grow the limiter beyond the time-based sweep.
	pairingRateAddresses = 4096
)

var (
	ErrNoPairingCode      = errors.New("no pairing code is active")
	ErrPairingCodeExpired = errors.New("pairing code has expired")
	ErrPairingCodeInvalid = errors.New("pairing code is invalid")
	ErrPairingRateLimited = errors.New("too many pairing attempts from this address")
)

type PairingCode struct {
	Code      string    `json:"code"`
	ExpiresAt time.Time `json:"expires_at"`
}

// Display renders the code in groups of four for reading aloud. Separators are
// ignored on submission.
func (c PairingCode) Display() string {
	return FormatCode(c.Code)
}

type Pairing struct {
	mu        sync.Mutex
	now       func() time.Time
	code      string
	expiresAt time.Time
	attempts  int
	addresses map[string][]time.Time
}

func NewPairing() *Pairing {
	return &Pairing{
		now:       time.Now,
		addresses: map[string][]time.Time{},
	}
}

// Mint replaces any active code. Codes live in memory only, so restarting the
// server invalidates an outstanding one.
func (p *Pairing) Mint() (PairingCode, error) {
	code, err := generateCode()
	if err != nil {
		return PairingCode{}, err
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	p.code = code
	p.expiresAt = p.now().UTC().Add(pairingCodeTTL).Truncate(time.Second)
	p.attempts = 0
	return PairingCode{Code: p.code, ExpiresAt: p.expiresAt}, nil
}

func (p *Pairing) Active() (PairingCode, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.code == "" {
		return PairingCode{}, false
	}
	if !p.now().Before(p.expiresAt) {
		p.clear()
		return PairingCode{}, false
	}
	return PairingCode{Code: p.code, ExpiresAt: p.expiresAt}, true
}

func (p *Pairing) Cancel() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.clear()
}

// Claim consumes the active code when submitted matches it. The code is single
// use on success and burns after five failures; callers map
// ErrPairingRateLimited to 429 and every other error to 401.
func (p *Pairing) Claim(remoteAddr, submitted string) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	now := p.now()
	if err := p.rateLimit(remoteAddr, now); err != nil {
		return err
	}
	if p.code == "" {
		return ErrNoPairingCode
	}
	if !now.Before(p.expiresAt) {
		p.clear()
		return ErrPairingCodeExpired
	}
	if !constantTimeEqual(p.code, NormalizeCode(submitted)) {
		p.attempts++
		if p.attempts >= pairingCodeAttempts {
			p.clear()
		}
		return ErrPairingCodeInvalid
	}
	p.clear()
	return nil
}

func (p *Pairing) clear() {
	p.code = ""
	p.expiresAt = time.Time{}
	p.attempts = 0
}

func (p *Pairing) rateLimit(remoteAddr string, now time.Time) error {
	p.sweepAddresses(now)
	key := limiterKey(remoteAddr)
	if len(p.addresses[key]) >= pairingRateLimit {
		return ErrPairingRateLimited
	}
	p.addresses[key] = append(p.addresses[key], now)
	return nil
}

func (p *Pairing) sweepAddresses(now time.Time) {
	cutoff := now.Add(-pairingRateWindow)
	for key, attempts := range p.addresses {
		kept := attempts[:0]
		for _, attempt := range attempts {
			if attempt.After(cutoff) {
				kept = append(kept, attempt)
			}
		}
		if len(kept) == 0 {
			delete(p.addresses, key)
			continue
		}
		p.addresses[key] = kept
	}
	if len(p.addresses) <= pairingRateAddresses {
		return
	}
	// Still oversized after the sweep: drop the least recently active entries.
	keys := make([]string, 0, len(p.addresses))
	for key := range p.addresses {
		keys = append(keys, key)
	}
	sort.Slice(keys, func(i, j int) bool {
		left := p.addresses[keys[i]]
		right := p.addresses[keys[j]]
		return left[len(left)-1].Before(right[len(right)-1])
	})
	for _, key := range keys[:len(p.addresses)-pairingRateAddresses] {
		delete(p.addresses, key)
	}
}

// NormalizeCode folds a submitted code to its canonical form: upper case,
// without separators, with the Crockford confusables mapped onto the digits
// they resemble.
func NormalizeCode(value string) string {
	var builder strings.Builder
	builder.Grow(len(value))
	for _, character := range strings.ToUpper(value) {
		switch {
		case character == '-' || unicode.IsSpace(character):
			continue
		case character == 'I' || character == 'L':
			builder.WriteRune('1')
		case character == 'O':
			builder.WriteRune('0')
		default:
			builder.WriteRune(character)
		}
	}
	return builder.String()
}

func FormatCode(code string) string {
	var builder strings.Builder
	for index, character := range code {
		if index > 0 && index%4 == 0 {
			builder.WriteRune('-')
		}
		builder.WriteRune(character)
	}
	return builder.String()
}

func generateCode() (string, error) {
	buffer := make([]byte, pairingCodeLength)
	for index := range buffer {
		symbol, err := randomIndex(len(crockfordAlphabet))
		if err != nil {
			return "", err
		}
		buffer[index] = crockfordAlphabet[symbol]
	}
	return string(buffer), nil
}

// randomIndex returns a uniform value in [0, bound) by rejecting the tail of
// the byte range that does not divide evenly, rather than taking a biased
// modulus.
func randomIndex(bound int) (int, error) {
	if bound < 1 || bound > 256 {
		return 0, fmt.Errorf("bound %d is out of range", bound)
	}
	limit := 256 - (256 % bound)
	buffer := make([]byte, 1)
	for {
		if _, err := rand.Read(buffer); err != nil {
			return 0, fmt.Errorf("read random bytes: %w", err)
		}
		if int(buffer[0]) < limit {
			return int(buffer[0]) % bound, nil
		}
	}
}

func limiterKey(remoteAddr string) string {
	remoteAddr = strings.TrimSpace(remoteAddr)
	if host, _, err := net.SplitHostPort(remoteAddr); err == nil {
		remoteAddr = host
	}
	if ip := net.ParseIP(strings.Trim(remoteAddr, "[]")); ip != nil {
		return ip.String()
	}
	return remoteAddr
}

func constantTimeEqual(left, right string) bool {
	if len(left) != len(right) || len(left) == 0 {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(left), []byte(right)) == 1
}
