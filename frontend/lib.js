// The parts of the dashboard that are decisions rather than drawing.
//
// app.js is a browser-only script: it reads the document at load, so it cannot
// be imported by a test. Everything here is pure, takes its inputs as
// arguments, and is covered by lib.test.js, which runs under `node --test`
// with no build step and no dependencies — the same deal the rest of the
// frontend gets.

// Personal mode binds SOCKS to loopback with the username from state.json. The
// pairing response carries no socks block, so these are the documented
// defaults used until one is present.
export const DEFAULT_SOCKS = { host: "127.0.0.1", port: 1080, username: "proxy" };

export function prettyPolicy(policy = "") {
  return policy.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
}

export function formatBytes(value = 0) {
  const bytes = Number(value) || 0;
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KiB", "MiB", "GiB", "TiB"];
  let current = bytes / 1024;
  let unit = units[0];
  for (let index = 1; index < units.length && current >= 1024; index += 1) {
    current /= 1024;
    unit = units[index];
  }
  return `${current.toFixed(current >= 100 ? 0 : current >= 10 ? 1 : 2)} ${unit}`;
}

export function formatRate(kbps = 0) {
  if (!kbps) return "—";
  return kbps >= 1000 ? `${(kbps / 1000).toFixed(0)} Mbps` : `${kbps} Kbps`;
}

export function formatClock(seconds) {
  const minutes = Math.floor(seconds / 60);
  return `${minutes}:${String(seconds % 60).padStart(2, "0")}`;
}

export function formatAge(timestamp, now = Date.now()) {
  if (!timestamp) return "never";
  const seconds = Math.max(0, Math.floor((now - new Date(timestamp).getTime()) / 1000));
  if (seconds < 5) return "now";
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}

export function statusClass(status) {
  if (status === "open") return "online";
  if (status === "pending") return "warning";
  if (status === "failed") return "error";
  return "offline";
}

// socksEndpoint prefers a hint from the server and falls back to the
// personal-mode defaults, which is where the listener sits unless --socks-addr
// moved it.
export function socksEndpoint(pairing) {
  const hint = (pairing && pairing.socks) || {};
  return {
    host: hint.host || DEFAULT_SOCKS.host,
    port: hint.port || DEFAULT_SOCKS.port,
    username: hint.username || DEFAULT_SOCKS.username,
  };
}

// svgDataURI wraps the server-rendered QR for an <img>, where an SVG cannot run
// script. The markup is checked rather than trusted, and it never reaches the
// document as HTML.
export function svgDataURI(svg) {
  if (typeof svg !== "string" || !svg.trimStart().startsWith("<svg")) return "";
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

// trafficSample turns two cumulative counter readings into a per-second rate.
// The counters only ever climb while a node is up, but a node that re-registers
// starts again from zero, which would otherwise plot as a huge negative spike;
// clamping at zero drops that one sample instead.
export function trafficSample(previous, current, seconds) {
  return {
    up: Math.max(0, current.up - previous.up) / seconds,
    down: Math.max(0, current.down - previous.down) / seconds,
  };
}

// secondsRemaining returns null when the server sent a timestamp that cannot be
// read, which is the difference between "no countdown" and a countdown stuck at
// NaN.
export function secondsRemaining(expiresAt, now = Date.now()) {
  const expiry = new Date(expiresAt).getTime();
  if (Number.isNaN(expiry)) return null;
  return Math.max(0, Math.round((expiry - now) / 1000));
}
