package main

import (
	"context"
	"crypto/tls"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/CesarPetrescu/pocket-exit/backend/internal/circuit"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/config"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/httpapi"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/nodes"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/personal"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/proxy"
	"github.com/skip2/go-qrcode"
)

const (
	defaultPersonalHTTPSAddr = "0.0.0.0:8443"
	defaultPersonalSOCKSAddr = "127.0.0.1:1080"
	personalAuditLogName     = "audit.jsonl"
)

func runPersonal(arguments []string) error {
	flags := flag.NewFlagSet("personal", flag.ContinueOnError)
	flags.SetOutput(os.Stderr)
	directory := flags.String("dir", "", "state directory (default $POCKETEXIT_HOME, else ~/.pocketexit)")
	httpsAddr := flags.String("https-addr", defaultPersonalHTTPSAddr, "HTTPS listener address")
	socksAddr := flags.String("socks-addr", defaultPersonalSOCKSAddr, "SOCKS5 listener address")
	frontend := flags.String("frontend", "", "dashboard directory to serve (default the bundled frontend)")
	pair := flags.Bool("pair", false, "mint a pairing code at startup and print its QR code")
	flags.Usage = func() {
		fmt.Fprint(os.Stderr, "Usage: pocketexit personal [flags]\n\nFlags:\n")
		flags.PrintDefaults()
	}
	if err := flags.Parse(arguments); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return nil
		}
		return err
	}
	if flags.NArg() > 0 {
		return fmt.Errorf("unexpected argument %q", flags.Arg(0))
	}

	stateDirectory := strings.TrimSpace(*directory)
	if stateDirectory == "" {
		home, err := personal.Home()
		if err != nil {
			return err
		}
		stateDirectory = home
	}
	store, err := personal.Open(stateDirectory)
	if err != nil {
		return err
	}
	hostIPs, err := personal.LocalIPs()
	if err != nil {
		return err
	}
	certificate, err := personal.EnsureCertificate(store.Directory(), hostIPs)
	if err != nil {
		return err
	}

	// The listener is bound before the configuration is assembled because
	// --https-addr may ask for port 0, and the advertised URL has to carry the
	// port the phone will actually dial.
	listener, err := net.Listen("tcp", *httpsAddr)
	if err != nil {
		return fmt.Errorf("listen on %s: %w", *httpsAddr, err)
	}
	defer listener.Close()

	frontendDirectory := strings.TrimSpace(*frontend)
	if frontendDirectory == "" {
		frontendDirectory = defaultFrontendDirectory()
	}
	serverName, err := os.Hostname()
	if err != nil {
		serverName = ""
	}
	cfg, err := config.Personal(config.PersonalOptions{
		HTTPAddr:      listener.Addr().String(),
		SOCKSAddr:     *socksAddr,
		AdminToken:    store.AdminToken(),
		SOCKSUsername: store.SOCKSUsername(),
		SOCKSPassword: store.SOCKSPassword(),
		AuditLogPath:  filepath.Join(store.Directory(), personalAuditLogName),
		FrontendDir:   frontendDirectory,
		ServerURL:     advertisedURL(listener.Addr(), hostIPs),
		ServerName:    truncateServerName(serverName),
		CertPin:       certificate.Pin,
	})
	if err != nil {
		return err
	}

	logger, auditFile, err := newLogger(cfg.LogJSON, cfg.AuditLogPath)
	if err != nil {
		return err
	}
	// As in runServer: a deferred Close that drops its error can lose the
	// last audit records without anyone noticing.
	defer func() {
		if closeErr := auditFile.Close(); closeErr != nil {
			logger.Error("closing the audit log failed", "error", closeErr)
		}
	}()
	registry := nodes.NewRegistry(cfg.NodeOfflineAfter, cfg.MaxCircuitsPerNode)
	circuits := circuit.NewManager()
	pairing := personal.NewPairing()
	api := httpapi.NewPersonal(cfg, registry, circuits, logger, store, pairing)
	socks := proxy.New(cfg, registry, circuits, logger)

	code := personal.PairingCode{}
	paired := false
	if *pair {
		if code, err = pairing.Mint(); err != nil {
			return err
		}
		paired = true
	}
	if err := writeStartupBlock(os.Stdout, cfg, certificate, store, api, code, paired); err != nil {
		return err
	}
	if certificate.Issued {
		logger.Info("issued a new certificate", "event", "certificate_issued", "path", certificate.CertPath, "pin", certificate.Pin)
	}
	if !loopbackAddress(cfg.SOCKSAddr) {
		logger.Warn(
			"SOCKS listener is not on a loopback address: it exposes an authenticated proxy to the local network",
			"event", "socks_not_loopback", "address", cfg.SOCKSAddr)
	}
	if cfg.FrontendDir == "" {
		logger.Warn("no dashboard directory was found: pass --frontend to serve one", "event", "frontend_missing")
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	httpServer := &http.Server{
		Handler:           api.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
		MaxHeaderBytes:    1 << 20,
		TLSConfig: &tls.Config{
			Certificates: []tls.Certificate{certificate.TLS},
			MinVersion:   tls.VersionTLS12,
		},
	}

	errorsChannel := make(chan error, 2)
	go func() {
		logger.Info("HTTPS API listening", "address", listener.Addr().String(), "server_url", cfg.ServerURL)
		// The certificate lives in the TLS configuration, so no paths are given.
		err := httpServer.ServeTLS(listener, "", "")
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			errorsChannel <- err
			return
		}
		errorsChannel <- nil
	}()
	go func() {
		logger.Info("SOCKS5 proxy listening", "address", cfg.SOCKSAddr, "udp_port_start", cfg.UDPPortStart, "udp_port_end", cfg.UDPPortEnd)
		errorsChannel <- socks.Run(ctx)
	}()
	go pruneLoop(ctx, circuits, logger)

	select {
	case <-ctx.Done():
		logger.Info("shutdown requested")
	case err := <-errorsChannel:
		if err != nil {
			logger.Error("server stopped unexpectedly", "error", err)
		}
		cancel()
	}

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()
	if err := httpServer.Shutdown(shutdownCtx); err != nil {
		logger.Error("HTTPS shutdown failed", "error", err)
	}
	return nil
}

// writeStartupBlock prints everything the user needs to reach their own server:
// where the dashboard is, how to point a browser's proxy at it, which key the
// phone should pin, and the code to scan.
func writeStartupBlock(
	out io.Writer,
	cfg config.Config,
	certificate personal.Certificate,
	store *personal.Store,
	api *httpapi.Server,
	code personal.PairingCode,
	paired bool,
) error {
	fmt.Fprintf(out, "\nPocketExit personal mode\n\n")
	fmt.Fprintf(out, "  Dashboard       %s/\n", cfg.ServerURL)
	fmt.Fprintf(out, "  Admin token     %s\n", cfg.AdminToken)
	fmt.Fprintf(out, "  SOCKS5 proxy    %s\n", cfg.SOCKSAddr)
	fmt.Fprintf(out, "  SOCKS username  %s\n", cfg.SOCKSUsername)
	fmt.Fprintf(out, "  SOCKS password  %s\n", cfg.SOCKSPassword)
	fmt.Fprintf(out, "  Certificate pin %s\n", certificate.Pin)
	fmt.Fprintf(out, "  Certificate     %s\n", certificate.CertPath)
	fmt.Fprintf(out, "  State directory %s\n", store.Directory())
	if cfg.FrontendDir != "" {
		fmt.Fprintf(out, "  Dashboard files %s\n", cfg.FrontendDir)
	}
	if !paired {
		fmt.Fprintf(out, "\n  No pairing code is active. Mint one from the dashboard, or restart with --pair.\n\n")
		return nil
	}

	uri := api.PairingURI(code.Code)
	fmt.Fprintf(out, "\n  Pairing code    %s  (expires %s)\n",
		code.Display(), code.ExpiresAt.Local().Format(time.Kitchen))
	fmt.Fprintf(out, "  Scan this with the phone's camera app:\n\n")
	rendered, err := qrcode.New(uri, qrcode.Medium)
	if err != nil {
		return fmt.Errorf("render pairing QR: %w", err)
	}
	fmt.Fprint(out, rendered.ToSmallString(false))
	fmt.Fprintf(out, "\n  %s\n\n", uri)
	return nil
}

// advertisedURL is the origin the phone dials. A listener bound to one
// interface advertises that interface; a wildcard listener advertises the
// host's first LAN address, falling back to loopback on a machine with none.
func advertisedURL(address net.Addr, hostIPs []net.IP) string {
	host := "127.0.0.1"
	port := 8443
	if tcpAddr, ok := address.(*net.TCPAddr); ok {
		port = tcpAddr.Port
		switch {
		case tcpAddr.IP != nil && !tcpAddr.IP.IsUnspecified():
			host = tcpAddr.IP.String()
		case len(hostIPs) > 0:
			host = hostIPs[0].String()
		}
	}
	return "https://" + net.JoinHostPort(host, strconv.Itoa(port))
}

// defaultFrontendDirectory finds the dashboard next to the binary or in the
// checkout the process was started from. An empty result disables serving.
func defaultFrontendDirectory() string {
	candidates := []string{}
	if working, err := os.Getwd(); err == nil {
		candidates = append(candidates,
			filepath.Join(working, "frontend"),
			filepath.Join(working, "..", "frontend"))
	}
	if executable, err := os.Executable(); err == nil {
		base := filepath.Dir(executable)
		candidates = append(candidates,
			filepath.Join(base, "frontend"),
			filepath.Join(base, "..", "frontend"),
			filepath.Join(base, "..", "share", "pocketexit", "frontend"))
	}
	for _, candidate := range candidates {
		if info, err := os.Stat(filepath.Join(candidate, "index.html")); err == nil && !info.IsDir() {
			if resolved, err := filepath.Abs(candidate); err == nil {
				return resolved
			}
			return candidate
		}
	}
	return ""
}

func loopbackAddress(address string) bool {
	host, _, err := net.SplitHostPort(address)
	if err != nil || host == "" {
		return false
	}
	if ip := net.ParseIP(strings.Trim(host, "[]")); ip != nil {
		return ip.IsLoopback()
	}
	return strings.EqualFold(host, "localhost")
}

// maxServerNameRunes mirrors the contract's 64-character ceiling on the
// onboarding URI's display label. Counted in runes, matching config.
const maxServerNameRunes = 64

// truncateServerName keeps the onboarding URI's display label within the
// contract's 64-character ceiling. It counts runes rather than bytes, so a
// multi-byte character is never cut in half into invalid UTF-8.
func truncateServerName(name string) string {
	name = strings.TrimSpace(name)
	runes := []rune(name)
	if len(runes) <= maxServerNameRunes {
		return name
	}
	return strings.TrimSpace(string(runes[:maxServerNameRunes]))
}
