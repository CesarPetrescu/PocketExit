package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/CesarPetrescu/pocket-exit/backend/internal/circuit"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/config"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/httpapi"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/nodes"
	"github.com/CesarPetrescu/pocket-exit/backend/internal/proxy"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		panic(err)
	}
	logger, auditFile, err := newLogger(cfg.LogJSON, cfg.AuditLogPath)
	if err != nil {
		panic(err)
	}
	defer auditFile.Close()
	registry := nodes.NewRegistry(cfg.NodeOfflineAfter, cfg.MaxCircuitsPerNode)
	circuits := circuit.NewManager()
	api := httpapi.New(cfg, registry, circuits, logger)
	socks := proxy.New(cfg, registry, circuits, logger)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	httpServer := &http.Server{
		Addr:              cfg.HTTPAddr,
		Handler:           api.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
		MaxHeaderBytes:    1 << 20,
	}

	errorsChannel := make(chan error, 2)
	go func() {
		logger.Info("HTTP API listening", "address", cfg.HTTPAddr)
		err := httpServer.ListenAndServe()
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
		logger.Error("HTTP shutdown failed", "error", err)
	}
}

func pruneLoop(ctx context.Context, manager *circuit.Manager, logger *slog.Logger) {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if removed := manager.Prune(5 * time.Minute); removed > 0 {
				logger.Debug("pruned closed circuits", "count", removed)
			}
		}
	}
}

func newLogger(jsonOutput bool, auditPath string) (*slog.Logger, io.Closer, error) {
	auditFile, err := os.OpenFile(auditPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return nil, nil, err
	}
	output := io.MultiWriter(os.Stdout, auditFile)
	options := &slog.HandlerOptions{Level: slog.LevelInfo}
	if os.Getenv("LOG_LEVEL") == "debug" {
		options.Level = slog.LevelDebug
	}
	if jsonOutput {
		return slog.New(slog.NewJSONHandler(output, options)), auditFile, nil
	}
	return slog.New(slog.NewTextHandler(output, options)), auditFile, nil
}
