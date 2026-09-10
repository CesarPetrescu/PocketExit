package personal

import (
	"errors"
	"strings"
	"sync"
	"testing"
	"time"
)

func newTestPairing() (*Pairing, *time.Time) {
	clock := time.Date(2026, 9, 9, 18, 4, 11, 0, time.UTC)
	pairing := NewPairing()
	pairing.now = func() time.Time { return clock }
	return pairing, &clock
}

func TestMintReplacesTheActiveCode(t *testing.T) {
	pairing, clock := newTestPairing()
	first, err := pairing.Mint()
	if err != nil {
		t.Fatal(err)
	}
	if len(first.Code) != pairingCodeLength {
		t.Fatalf("code %q is not %d characters", first.Code, pairingCodeLength)
	}
	if !first.ExpiresAt.Equal(clock.Add(pairingCodeTTL)) {
		t.Fatalf("expiry is %s, expected %s", first.ExpiresAt, clock.Add(pairingCodeTTL))
	}
	if display := first.Display(); len(display) != 9 || display[4] != '-' {
		t.Fatalf("unexpected display form %q", display)
	}

	second, err := pairing.Mint()
	if err != nil {
		t.Fatal(err)
	}
	if second.Code == first.Code {
		t.Fatal("minting twice returned the same code")
	}
	if err := pairing.Claim("192.0.2.10:4000", first.Code); !errors.Is(err, ErrPairingCodeInvalid) {
		t.Fatalf("expected the replaced code to be rejected, got %v", err)
	}
	if err := pairing.Claim("192.0.2.10:4000", second.Code); err != nil {
		t.Fatalf("expected the current code to be accepted: %v", err)
	}
}

func TestClaimIsSingleUse(t *testing.T) {
	pairing, _ := newTestPairing()
	code, err := pairing.Mint()
	if err != nil {
		t.Fatal(err)
	}
	if _, active := pairing.Active(); !active {
		t.Fatal("the minted code is not active")
	}
	if err := pairing.Claim("192.0.2.10:4000", code.Display()); err != nil {
		t.Fatal(err)
	}
	if err := pairing.Claim("192.0.2.10:4000", code.Code); !errors.Is(err, ErrNoPairingCode) {
		t.Fatalf("expected a consumed code to be gone, got %v", err)
	}
	if _, active := pairing.Active(); active {
		t.Fatal("a consumed code is still active")
	}
}

func TestClaimRejectsAnExpiredCode(t *testing.T) {
	pairing, clock := newTestPairing()
	code, err := pairing.Mint()
	if err != nil {
		t.Fatal(err)
	}

	*clock = clock.Add(pairingCodeTTL - time.Second)
	if _, active := pairing.Active(); !active {
		t.Fatal("the code expired early")
	}
	*clock = clock.Add(time.Second)
	if err := pairing.Claim("192.0.2.10:4000", code.Code); !errors.Is(err, ErrPairingCodeExpired) {
		t.Fatalf("expected ErrPairingCodeExpired, got %v", err)
	}
	if _, active := pairing.Active(); active {
		t.Fatal("an expired code is still active")
	}
	if err := pairing.Claim("192.0.2.10:4000", code.Code); !errors.Is(err, ErrNoPairingCode) {
		t.Fatalf("expected the expired code to be cleared, got %v", err)
	}
}

func TestClaimBurnsTheCodeAfterFiveFailures(t *testing.T) {
	pairing, _ := newTestPairing()
	code, err := pairing.Mint()
	if err != nil {
		t.Fatal(err)
	}
	wrong := NormalizeCode(strings.Repeat("Z", pairingCodeLength))
	if wrong == code.Code {
		wrong = NormalizeCode(strings.Repeat("Y", pairingCodeLength))
	}

	for attempt := 1; attempt < pairingCodeAttempts; attempt++ {
		if err := pairing.Claim("192.0.2.10:4000", wrong); !errors.Is(err, ErrPairingCodeInvalid) {
			t.Fatalf("attempt %d: expected ErrPairingCodeInvalid, got %v", attempt, err)
		}
		if _, active := pairing.Active(); !active {
			t.Fatalf("the code burned after %d attempts", attempt)
		}
	}
	if err := pairing.Claim("192.0.2.11:4000", wrong); !errors.Is(err, ErrPairingCodeInvalid) {
		t.Fatalf("final attempt: expected ErrPairingCodeInvalid, got %v", err)
	}
	if _, active := pairing.Active(); active {
		t.Fatalf("the code survived %d failed attempts", pairingCodeAttempts)
	}
	if err := pairing.Claim("192.0.2.12:4000", code.Code); !errors.Is(err, ErrNoPairingCode) {
		t.Fatalf("expected the burned code to be gone, got %v", err)
	}
}

func TestClaimRateLimitsPerRemoteAddress(t *testing.T) {
	pairing, clock := newTestPairing()
	for attempt := 0; attempt < pairingRateLimit; attempt++ {
		if err := pairing.Claim("192.0.2.10:4000", "A1B2C3D4"); errors.Is(err, ErrPairingRateLimited) {
			t.Fatalf("attempt %d was limited early", attempt)
		}
	}
	if err := pairing.Claim("192.0.2.10:9999", "A1B2C3D4"); !errors.Is(err, ErrPairingRateLimited) {
		t.Fatalf("expected ErrPairingRateLimited, got %v", err)
	}
	// The limit is per address, and the port is not part of the identity.
	if err := pairing.Claim("192.0.2.11:4000", "A1B2C3D4"); !errors.Is(err, ErrNoPairingCode) {
		t.Fatalf("a different address was limited: %v", err)
	}

	*clock = clock.Add(pairingRateWindow + time.Second)
	if err := pairing.Claim("192.0.2.10:4000", "A1B2C3D4"); !errors.Is(err, ErrNoPairingCode) {
		t.Fatalf("the window did not reopen: %v", err)
	}
	if tracked := len(pairing.addresses); tracked != 1 {
		t.Fatalf("the limiter tracks %d addresses, expected the stale ones to be evicted", tracked)
	}
}

func TestNormalizeCode(t *testing.T) {
	tests := []struct {
		name  string
		input string
		want  string
	}{
		{name: "already canonical", input: "A1B2C3D4", want: "A1B2C3D4"},
		{name: "lower case", input: "a1b2c3d4", want: "A1B2C3D4"},
		{name: "grouped", input: "A1B2-C3D4", want: "A1B2C3D4"},
		{name: "spoken aloud", input: " a1b2 - c3d4\t\n", want: "A1B2C3D4"},
		{name: "upper case confusables", input: "IL0O1234", want: "11001234"},
		{name: "lower case confusables", input: "iloO1234", want: "11001234"},
		{name: "empty", input: "  ", want: ""},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := NormalizeCode(test.input); got != test.want {
				t.Fatalf("NormalizeCode(%q) = %q, expected %q", test.input, got, test.want)
			}
		})
	}
}

func TestClaimAcceptsConfusablesInASubmittedCode(t *testing.T) {
	pairing, _ := newTestPairing()
	code, err := pairing.Mint()
	if err != nil {
		t.Fatal(err)
	}
	// A reader who mistook 1 for I or L and 0 for O still pairs.
	misread := strings.NewReplacer("1", "l", "0", "O").Replace(code.Display())
	if err := pairing.Claim("192.0.2.10:4000", strings.ToLower(misread)); err != nil {
		t.Fatalf("claim %q for code %q: %v", misread, code.Code, err)
	}
}

func TestCancelClearsTheActiveCode(t *testing.T) {
	pairing, _ := newTestPairing()
	code, err := pairing.Mint()
	if err != nil {
		t.Fatal(err)
	}
	pairing.Cancel()
	if _, active := pairing.Active(); active {
		t.Fatal("the code survived a cancel")
	}
	if err := pairing.Claim("192.0.2.10:4000", code.Code); !errors.Is(err, ErrNoPairingCode) {
		t.Fatalf("expected ErrNoPairingCode, got %v", err)
	}
}

func TestGeneratedCodesUseTheCrockfordAlphabet(t *testing.T) {
	seen := make(map[rune]int, len(crockfordAlphabet))
	for round := 0; round < 512; round++ {
		code, err := generateCode()
		if err != nil {
			t.Fatal(err)
		}
		if len(code) != pairingCodeLength {
			t.Fatalf("code %q is not %d characters", code, pairingCodeLength)
		}
		for _, character := range code {
			if !strings.ContainsRune(crockfordAlphabet, character) {
				t.Fatalf("code %q contains %q, which is outside the alphabet", code, character)
			}
			seen[character]++
		}
		if NormalizeCode(code) != code {
			t.Fatalf("code %q is not in canonical form", code)
		}
	}
	if len(seen) != len(crockfordAlphabet) {
		t.Fatalf("only %d of %d symbols were generated: %v", len(seen), len(crockfordAlphabet), seen)
	}
	for _, excluded := range "ILOU" {
		if seen[excluded] != 0 {
			t.Fatalf("the alphabet emitted the excluded symbol %q", excluded)
		}
	}
}

func TestRandomIndexIsUniform(t *testing.T) {
	tests := []struct {
		name    string
		bound   int
		samples int
		// tolerance is the accepted deviation from the expected count, wide
		// enough that a correct sampler effectively never trips it and a
		// modulo-biased one always does.
		tolerance float64
	}{
		{name: "power of two", bound: 32, samples: 128000, tolerance: 0.10},
		{name: "not a power of two", bound: 10, samples: 120000, tolerance: 0.05},
		{name: "worst case for modulo bias", bound: 129, samples: 258000, tolerance: 0.15},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			counts := make([]int, test.bound)
			for sample := 0; sample < test.samples; sample++ {
				value, err := randomIndex(test.bound)
				if err != nil {
					t.Fatal(err)
				}
				if value < 0 || value >= test.bound {
					t.Fatalf("randomIndex(%d) returned %d", test.bound, value)
				}
				counts[value]++
			}
			expected := float64(test.samples) / float64(test.bound)
			for value, count := range counts {
				deviation := float64(count)/expected - 1
				if deviation < -test.tolerance || deviation > test.tolerance {
					t.Fatalf("value %d occurred %d times, expected %.0f within %.0f%%",
						value, count, expected, test.tolerance*100)
				}
			}
		})
	}
}

func TestRandomIndexRejectsUnusableBounds(t *testing.T) {
	for _, bound := range []int{0, -1, 257} {
		if _, err := randomIndex(bound); err == nil {
			t.Fatalf("expected bound %d to be refused", bound)
		}
	}
	value, err := randomIndex(1)
	if err != nil || value != 0 {
		t.Fatalf("randomIndex(1) = %d, %v", value, err)
	}
}

func TestPairingIsSafeUnderConcurrentUse(t *testing.T) {
	pairing := NewPairing()
	var workers sync.WaitGroup
	for worker := 0; worker < 8; worker++ {
		workers.Add(1)
		go func(worker int) {
			defer workers.Done()
			for round := 0; round < 20; round++ {
				code, err := pairing.Mint()
				if err != nil {
					t.Errorf("mint: %v", err)
					return
				}
				pairing.Active()
				_ = pairing.Claim("192.0.2."+string(rune('0'+worker))+":4000", code.Code)
				pairing.Cancel()
			}
		}(worker)
	}
	workers.Wait()
}

func TestFormatCode(t *testing.T) {
	tests := []struct {
		input string
		want  string
	}{
		{input: "A1B2C3D4", want: "A1B2-C3D4"},
		{input: "A1B2", want: "A1B2"},
		{input: "", want: ""},
	}
	for _, test := range tests {
		if got := FormatCode(test.input); got != test.want {
			t.Fatalf("FormatCode(%q) = %q, expected %q", test.input, got, test.want)
		}
	}
}
