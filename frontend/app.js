"use strict";

const POLICIES = [
  "AUTO",
  "WIFI_ONLY",
  "CELLULAR_ONLY",
  "WIFI_PREFERRED",
  "CELLULAR_PREFERRED",
];

// The pairing protocol fixes the code lifetime at ten minutes, so the countdown
// bar drains against that span rather than against whatever is left when this
// tab happens to load.
const PAIRING_TTL_SECONDS = 600;

// Personal mode binds SOCKS to loopback with the username from state.json. The
// pairing response carries no socks block, so these are the documented defaults
// used until one is present.
const DEFAULT_SOCKS = { host: "127.0.0.1", port: 1080, username: "proxy" };

const state = {
  token: sessionStorage.getItem("pocketexit.adminToken") || "",
  nodes: [],
  circuits: [],
  pairing: null,
  pairingCode: "",
  // Bumped by every mint and cancel so a poll that was already in flight
  // cannot put a stale code back on screen.
  pairingGeneration: 0,
  personal: false,
  modeKnown: false,
  unpairTarget: null,
  trafficSamples: Array.from({ length: 36 }, () => ({ up: 0, down: 0 })),
  previousTraffic: null,
  polling: false,
  timer: null,
};

const elements = {
  authForm: document.querySelector("#auth-form"),
  token: document.querySelector("#admin-token"),
  saveToken: document.querySelector("#save-token"),
  connection: document.querySelector("#connection-state"),
  refresh: document.querySelector("#refresh"),
  signOut: document.querySelector("#sign-out"),
  welcome: document.querySelector("#welcome"),
  welcomeCopy: document.querySelector("#welcome-copy"),
  dashboard: document.querySelector("#dashboard"),
  pairing: document.querySelector("#pairing"),
  pairingIdle: document.querySelector("#pairing-idle"),
  pairingActive: document.querySelector("#pairing-active"),
  pairingMint: document.querySelector("#pairing-mint"),
  pairingRegenerate: document.querySelector("#pairing-regenerate"),
  pairingCancel: document.querySelector("#pairing-cancel"),
  pairingQr: document.querySelector("#pairing-qr"),
  pairingCode: document.querySelector("#pairing-code"),
  pairingCountdown: document.querySelector("#pairing-countdown"),
  pairingProgress: document.querySelector("#pairing-progress"),
  factSocks: document.querySelector("#fact-socks"),
  factSocksNote: document.querySelector("#fact-socks-note"),
  factPin: document.querySelector("#fact-pin"),
  factServer: document.querySelector("#fact-server"),
  nodes: document.querySelector("#nodes"),
  circuits: document.querySelector("#circuits"),
  filter: document.querySelector("#circuit-filter"),
  toast: document.querySelector("#toast"),
  nodeCount: document.querySelector("#node-count"),
  onlineCount: document.querySelector("#online-count"),
  circuitCount: document.querySelector("#circuit-count"),
  trafficTotal: document.querySelector("#traffic-total"),
  trafficRate: document.querySelector("#traffic-rate"),
  trafficChart: document.querySelector("#traffic-chart"),
  trafficDescription: document.querySelector("#traffic-description"),
  lastSync: document.querySelector("#last-sync"),
  pairDialog: document.querySelector("#pair-dialog"),
  pairDetail: document.querySelector("#pair-detail"),
  pairQr: document.querySelector("#pair-qr"),
  pairClose: document.querySelector("#pair-close"),
  unpairDialog: document.querySelector("#unpair-dialog"),
  unpairDetail: document.querySelector("#unpair-detail"),
  unpairNote: document.querySelector("#unpair-note"),
  unpairCancel: document.querySelector("#unpair-cancel"),
  unpairConfirm: document.querySelector("#unpair-confirm"),
};

elements.token.value = state.token;
elements.authForm.addEventListener("submit", (event) => {
  event.preventDefault();
  saveToken();
});
elements.refresh.addEventListener("click", refresh);
elements.signOut.addEventListener("click", signOut);
elements.filter.addEventListener("change", renderCircuits);
window.addEventListener("resize", drawTrafficChart);
elements.pairClose.addEventListener("click", () => elements.pairDialog.close());
elements.pairDialog.addEventListener("close", () => elements.pairQr.removeAttribute("src"));
elements.pairingMint.addEventListener("click", mintPairingCode);
elements.pairingRegenerate.addEventListener("click", mintPairingCode);
elements.pairingCancel.addEventListener("click", cancelPairingCode);
elements.unpairCancel.addEventListener("click", () => elements.unpairDialog.close());
elements.unpairConfirm.addEventListener("click", unpairNode);
elements.unpairDialog.addEventListener("close", () => { state.unpairTarget = null; });
for (const button of document.querySelectorAll(".copy-button")) {
  button.addEventListener("click", () => copyValue(button));
}

renderAccess();
detectMode();
if (state.token) refresh();
startPolling();
setInterval(renderCountdown, 1000);

function saveToken() {
  state.token = elements.token.value.trim();
  sessionStorage.setItem("pocketexit.adminToken", state.token);
  renderAccess();
  refresh();
}

function signOut() {
  state.token = "";
  state.nodes = [];
  state.circuits = [];
  state.pairing = null;
  state.pairingCode = "";
  sessionStorage.removeItem("pocketexit.adminToken");
  elements.token.value = "";
  setConnection("Not connected", "neutral");
  renderAccess();
  renderPairing();
}

// renderAccess decides between the sign-in panel and the dashboard. A bare
// password box is the wrong first thing to meet, so the panel explains where
// the token comes from before asking for it.
function renderAccess() {
  const connected = Boolean(state.token);
  elements.welcome.hidden = connected;
  elements.dashboard.hidden = !connected;
  elements.refresh.hidden = !connected;
  elements.signOut.hidden = !connected;
  if (!connected) elements.token.focus({ preventScroll: true });
  renderWelcomeCopy();
}

function renderWelcomeCopy() {
  if (!state.modeKnown) {
    elements.welcomeCopy.textContent = "Paste the control-plane admin token to load the fleet.";
    return;
  }
  elements.welcomeCopy.textContent = state.personal
    ? "PocketExit printed the token in the terminal when you started it: the line beginning “Admin token”, in the block under “PocketExit personal mode”. Copy it and paste it below — there is nothing else to configure."
    : "Paste the control-plane admin token. In server mode that is the ADMIN_TOKEN the backend was deployed with.";
}

// detectMode tells the two modes apart before a token exists: the pairing
// endpoints are only mounted in personal mode, so the path is known there and
// unknown in server mode. The probe uses OPTIONS, which no handler answers, so
// personal mode replies 405 from the router without running the admin guard and
// without logging a failed authentication into the user's terminal. Server mode
// has no such route and replies 404. The first authenticated pairing fetch
// settles the mode either way.
async function detectMode() {
  try {
    const response = await fetch("/api/v1/pairing", { method: "OPTIONS", cache: "no-store" });
    if (response.status === 405 || response.status === 401 || response.status === 200) {
      state.personal = true;
      state.modeKnown = true;
    } else if (response.status === 404) {
      state.personal = false;
      state.modeKnown = true;
    }
  } catch (error) {
    // The server is unreachable; the mode stays unknown and the copy stays
    // neutral until a request succeeds.
  }
  renderWelcomeCopy();
  renderPairing();
}

function startPolling() {
  clearInterval(state.timer);
  state.timer = setInterval(() => {
    if (state.token && document.visibilityState === "visible") refresh(false);
  }, 3000);
}

async function refresh(showErrors = true) {
  if (!state.token || state.polling) return;
  state.polling = true;
  elements.refresh.disabled = true;
  elements.refresh.textContent = "Refreshing";
  elements.nodes.setAttribute("aria-busy", "true");
  try {
    const [nodesResponse, circuitsResponse] = await Promise.all([
      api("/api/v1/nodes"),
      api("/api/v1/circuits"),
    ]);
    state.nodes = nodesResponse.nodes || [];
    state.circuits = circuitsResponse.circuits || [];
    if (state.personal || !state.modeKnown) await refreshPairing();
    setConnection("Connected", "online");
    elements.lastSync.textContent = `Updated ${new Date().toLocaleTimeString([], {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    })}`;
    render();
  } catch (error) {
    if (error.status === 401) {
      // The stored token is wrong or the server was restarted with a new one;
      // send the operator back to the panel that explains where to find it.
      signOut();
      setConnection("Token rejected", "error");
      showToast("That admin token was rejected", true);
      return;
    }
    setConnection(error.message, "error");
    if (showErrors) showToast(error.message, true);
  } finally {
    state.polling = false;
    elements.refresh.disabled = false;
    elements.refresh.textContent = "Refresh";
    elements.nodes.setAttribute("aria-busy", "false");
  }
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: {
      Authorization: `Bearer ${state.token}`,
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
  });
  if (response.status === 204) return null;
  const type = response.headers.get("content-type") || "";
  const payload = type.includes("application/json") ? await response.json() : await response.text();
  if (!response.ok) {
    const message = payload?.error || payload || `HTTP ${response.status}`;
    const error = new Error(message);
    // Callers distinguish "not in this mode" (404) and "cannot be revoked at
    // runtime" (409) from a genuine failure.
    error.status = response.status;
    throw error;
  }
  return payload;
}

function render() {
  renderSummary();
  renderNodes();
  renderCircuits();
}

async function refreshPairing() {
  const generation = state.pairingGeneration;
  let payload = null;
  try {
    payload = await api("/api/v1/pairing");
    state.personal = true;
  } catch (error) {
    if (error.status !== 404) throw error;
    // Server mode: pairing is not part of this deployment.
    state.personal = false;
  }
  state.modeKnown = true;
  // A code minted or cancelled while this poll was in flight wins over it.
  if (generation !== state.pairingGeneration) return;
  state.pairing = state.personal ? payload : null;
  renderPairing();
}

async function mintPairingCode() {
  setPairingBusy(true);
  try {
    const payload = await api("/api/v1/pairing", { method: "POST" });
    state.pairingGeneration += 1;
    state.pairing = payload;
    state.personal = true;
    state.modeKnown = true;
    // Force the QR to be redrawn even when the server hands back a code that
    // happens to match the one on screen.
    state.pairingCode = "";
    renderPairing();
    showToast("Pairing code ready — scan it with the phone's camera");
  } catch (error) {
    showToast(error.message, true);
  } finally {
    setPairingBusy(false);
  }
}

async function cancelPairingCode() {
  setPairingBusy(true);
  try {
    await api("/api/v1/pairing", { method: "DELETE" });
    state.pairingGeneration += 1;
    await refreshPairing();
    showToast("Pairing code cancelled");
  } catch (error) {
    showToast(error.message, true);
  } finally {
    setPairingBusy(false);
  }
}

function setPairingBusy(busy) {
  elements.pairingMint.disabled = busy;
  elements.pairingRegenerate.disabled = busy;
  elements.pairingCancel.disabled = busy;
}

function renderPairing() {
  elements.pairing.hidden = !(state.personal && state.token);
  const pairing = state.pairing;
  if (!pairing) {
    state.pairingCode = "";
    elements.pairingQr.removeAttribute("src");
    return;
  }

  const socks = socksEndpoint(pairing);
  elements.factSocks.textContent = `${socks.host}:${socks.port}`;
  elements.factSocksNote.textContent = `SOCKS5 with username ${socks.username}; the password is on the “SOCKS password” line in the terminal.`;
  elements.factPin.textContent = pairing.fingerprint || "not pinned";
  elements.factServer.textContent = pairing.server_url || "—";

  const active = Boolean(pairing.active);
  elements.pairingIdle.hidden = active;
  elements.pairingActive.hidden = !active;
  if (!active) {
    state.pairingCode = "";
    elements.pairingQr.removeAttribute("src");
    return;
  }
  if (pairing.code !== state.pairingCode) {
    state.pairingCode = pairing.code || "";
    elements.pairingCode.textContent = state.pairingCode;
    const source = svgDataURI(pairing.qr_svg);
    if (source) {
      elements.pairingQr.src = source;
    } else {
      elements.pairingQr.removeAttribute("src");
    }
  }
  renderCountdown();
}

// renderCountdown runs once a second while a code is on screen. An expired code
// is left visible with its state named rather than vanishing under the user.
function renderCountdown() {
  if (elements.pairing.hidden || !state.pairing || !state.pairing.active) return;
  const expiry = new Date(state.pairing.expires_at).getTime();
  if (Number.isNaN(expiry)) {
    elements.pairingCountdown.textContent = "";
    return;
  }
  const remaining = Math.max(0, Math.round((expiry - Date.now()) / 1000));
  elements.pairingCountdown.textContent = remaining > 0
    ? `Expires in ${formatClock(remaining)}`
    : "This code has expired. Generate a new one.";
  elements.pairingCountdown.className = `pairing-countdown${remaining > 0 ? "" : " expired"}`;
  elements.pairingProgress.max = PAIRING_TTL_SECONDS;
  elements.pairingProgress.value = Math.min(remaining, PAIRING_TTL_SECONDS);
}

// socksEndpoint prefers a hint from the server and falls back to the
// personal-mode defaults, which is where the listener sits unless --socks-addr
// moved it.
function socksEndpoint(pairing) {
  const hint = pairing.socks || {};
  return {
    host: hint.host || DEFAULT_SOCKS.host,
    port: hint.port || DEFAULT_SOCKS.port,
    username: hint.username || DEFAULT_SOCKS.username,
  };
}

// svgDataURI wraps the server-rendered QR for an <img>, where an SVG cannot run
// script. The markup is checked rather than trusted, and it never reaches the
// document as HTML.
function svgDataURI(svg) {
  if (typeof svg !== "string" || !svg.trimStart().startsWith("<svg")) return "";
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

async function copyValue(button) {
  const target = document.querySelector(`#${button.dataset.copy}`);
  const value = target ? target.textContent.trim() : "";
  if (!value || value === "—") {
    showToast("Nothing to copy yet", true);
    return;
  }
  try {
    await navigator.clipboard.writeText(value);
    button.textContent = "Copied";
    setTimeout(() => { button.textContent = "Copy"; }, 1600);
  } catch (error) {
    // Clipboard access can be refused; select the value so the keyboard still
    // works.
    selectText(target);
    showToast("Copy was blocked — the value is selected, press Ctrl+C", true);
  }
}

function selectText(element) {
  const range = document.createRange();
  range.selectNodeContents(element);
  const selection = window.getSelection();
  selection.removeAllRanges();
  selection.addRange(range);
}

function renderSummary() {
  const online = state.nodes.filter((node) => node.online).length;
  const active = state.circuits.filter((circuit) => ["open", "pending"].includes(circuit.status));
  const up = state.nodes.reduce((sum, node) => sum + (node.bytes_up || 0), 0);
  const down = state.nodes.reduce((sum, node) => sum + (node.bytes_down || 0), 0);
  const traffic = up + down;
  if (state.previousTraffic) {
    state.trafficSamples.push({
      up: Math.max(0, up - state.previousTraffic.up) / 3,
      down: Math.max(0, down - state.previousTraffic.down) / 3,
    });
    state.trafficSamples.shift();
  }
  state.previousTraffic = { up, down };
  const latest = state.trafficSamples.at(-1);
  elements.nodeCount.textContent = String(state.nodes.length);
  elements.onlineCount.textContent = String(online);
  elements.circuitCount.textContent = String(active.length);
  elements.trafficTotal.textContent = formatBytes(traffic);
  elements.trafficRate.textContent = `${formatBytes(latest.up + latest.down)}/s`;
  elements.trafficDescription.textContent = `Current download ${formatBytes(latest.down)} per second and upload ${formatBytes(latest.up)} per second.`;
  drawTrafficChart();
}

function drawTrafficChart() {
  const canvas = elements.trafficChart;
  const bounds = canvas.getBoundingClientRect();
  if (!bounds.width || !bounds.height) return;
  const scale = window.devicePixelRatio || 1;
  canvas.width = Math.round(bounds.width * scale);
  canvas.height = Math.round(bounds.height * scale);
  const context = canvas.getContext("2d");
  context.scale(scale, scale);
  context.clearRect(0, 0, bounds.width, bounds.height);
  context.strokeStyle = "rgba(255,255,255,.07)";
  context.lineWidth = 1;
  for (const ratio of [0, .5, 1]) {
    context.beginPath();
    context.moveTo(0, bounds.height * ratio);
    context.lineTo(bounds.width, bounds.height * ratio);
    context.stroke();
  }
  const ceiling = Math.max(1, ...state.trafficSamples.flatMap((sample) => [sample.up, sample.down]));
  drawLine("down", "#38bdf8", "rgba(56,189,248,.12)");
  drawLine("up", "#a78bfa");

  function drawLine(field, color, fill) {
    const points = state.trafficSamples.map((sample, index) => ({
      x: bounds.width * index / (state.trafficSamples.length - 1),
      y: bounds.height - sample[field] / ceiling * bounds.height * .88,
    }));
    plot(points);
    if (fill) {
      context.lineTo(bounds.width, bounds.height);
      context.lineTo(0, bounds.height);
      context.closePath();
      context.fillStyle = fill;
      context.fill();
      plot(points);
    }
    context.strokeStyle = color;
    context.lineWidth = field === "down" ? 2.5 : 2;
    context.lineCap = "round";
    context.lineJoin = "round";
    context.stroke();
  }

  function plot(points) {
    context.beginPath();
    context.moveTo(points[0].x, points[0].y);
    for (let index = 1; index < points.length; index += 1) {
      const previous = points[index - 1];
      const point = points[index];
      const middle = (previous.x + point.x) / 2;
      context.bezierCurveTo(middle, previous.y, middle, point.y, point.x, point.y);
    }
  }
}

function renderNodes() {
  elements.nodes.replaceChildren();
  if (!state.nodes.length) {
    elements.nodes.append(empty(state.personal
      ? "No phones paired yet. Generate a pairing code above and scan it with the phone you want to exit through."
      : "No Android nodes have registered yet."));
    return;
  }
  for (const node of state.nodes) elements.nodes.append(nodeCard(node));
}

function nodeCard(node) {
  const card = document.createElement("article");
  card.className = `node-card${node.enabled ? "" : " disabled"}`;

  const header = el("div", "node-header");
  const titleArea = el("div");
  const title = el("div", "node-title");
  title.append(statusDot(node.online ? "online" : "offline", node.online ? "Online" : "Offline"));
  title.append(el("h3", "", node.device_name || node.node_id));
  titleArea.append(title);
  titleArea.append(el("div", "node-meta", `${node.node_id} · ${node.app_version || "unknown build"} · seen ${formatAge(node.last_seen)}`));

  const actions = el("div", "node-actions");
  if (!state.personal) {
    // Server-mode onboarding is per node, because the QR carries that node's
    // own token. Personal mode pairs from the panel above instead: its code
    // carries no node, so re-scanning it would mint a second phone.
    const pair = el("button", "secondary pair-button", "Pair phone");
    pair.type = "button";
    pair.addEventListener("click", () => openPairing(node));
    actions.append(pair);
  }
  const toggle = el("label", "switch");
  const checkbox = document.createElement("input");
  checkbox.type = "checkbox";
  checkbox.checked = Boolean(node.enabled);
  checkbox.addEventListener("change", () => updateNode(node.node_id, { enabled: checkbox.checked }));
  toggle.append(checkbox, document.createTextNode("Selectable"));
  const unpair = el("button", "danger-button", "Unpair");
  unpair.type = "button";
  unpair.addEventListener("click", () => confirmUnpair(node));
  actions.append(toggle, unpair);
  header.append(titleArea, actions);

  const body = el("div", "node-body");
  const networkGrid = el("div", "network-grid");
  networkGrid.append(networkCard("Wi-Fi", node.wifi), networkCard("Cellular", node.cellular));
  body.append(networkGrid);

  const policyGrid = el("div", "policy-grid");
  policyGrid.append(policyControl(node, "Control tunnel", "control_policy"));
  policyGrid.append(policyControl(node, "Proxy exit", "exit_policy"));
  body.append(policyGrid);

  const stats = el("div", "node-stats");
  stats.append(miniStat("Active route", node.active_control_network || "—"));
  stats.append(miniStat("Transport", node.transport_protocol || "—"));
  stats.append(miniStat("Circuits", String(node.active_circuits || 0)));
  stats.append(miniStat("Battery", `${node.battery_percent ?? 0}%${node.charging ? " · charging" : ""}`));
  stats.append(miniStat("Up", formatBytes(node.bytes_up || 0)));
  stats.append(miniStat("Down", formatBytes(node.bytes_down || 0)));
  stats.append(miniStat("Total", formatBytes((node.bytes_up || 0) + (node.bytes_down || 0))));
  body.append(stats);

  card.append(header, body);
  return card;
}

async function openPairing(node) {
  try {
    const payload = await api(`/api/v1/nodes/${encodeURIComponent(node.node_id)}/onboarding`);
    elements.pairDetail.textContent = `${node.device_name || node.node_id} · ${node.node_id}`;
    const source = svgDataURI(payload.qr_svg);
    if (!source) {
      showToast("The server returned an unreadable QR code", true);
      return;
    }
    elements.pairQr.src = source;
    elements.pairDialog.showModal();
  } catch (error) {
    showToast(error.message, true);
  }
}

function confirmUnpair(node) {
  state.unpairTarget = node;
  elements.unpairDetail.textContent = `${node.device_name || node.node_id} · ${node.node_id}`;
  elements.unpairNote.textContent = "Its token is revoked, its open circuits close, and it leaves the fleet. Pair it again with a new code whenever you want it back.";
  elements.unpairConfirm.hidden = false;
  elements.unpairConfirm.disabled = false;
  elements.unpairCancel.textContent = "Keep it";
  elements.unpairDialog.showModal();
}

async function unpairNode() {
  const node = state.unpairTarget;
  if (!node) return;
  const name = node.device_name || node.node_id;
  elements.unpairConfirm.disabled = true;
  try {
    await api(`/api/v1/nodes/${encodeURIComponent(node.node_id)}`, { method: "DELETE" });
    elements.unpairDialog.close();
    showToast(`Unpaired ${name}`);
    await refresh(false);
  } catch (error) {
    if (error.status === 409) {
      // Server mode reads its nodes from AGENT_TOKENS_JSON, so there is nothing
      // the dashboard can revoke. Explain that in place instead of flashing a
      // failure the operator cannot act on.
      elements.unpairNote.textContent = `${name} is configured on the backend in AGENT_TOKENS_JSON, so it cannot be revoked while the server is running. Remove its entry from that file and restart the backend; until then you can clear the "Selectable" switch to stop routing through it.`;
      elements.unpairConfirm.hidden = true;
      elements.unpairCancel.textContent = "Close";
      return;
    }
    elements.unpairDialog.close();
    if (error.status === 404) {
      showToast(`${name} is already unpaired`);
      await refresh(false);
      return;
    }
    showToast(error.message, true);
  } finally {
    elements.unpairConfirm.disabled = false;
  }
}

function networkCard(name, network = {}) {
  const card = el("div", "network-card");
  const title = el("div", "network-title");
  title.append(el("span", "", name));
  const status = network.available
    ? network.validated ? statusDot("online", "Internet") : statusDot("warning", "No validation")
    : statusDot("offline", "Unavailable");
  title.append(status);
  card.append(title);

  const details = el("div", "network-details");
  details.append(detail("Interface", network.interface_name || "—"));
  details.append(detail("Link", `${formatRate(network.down_kbps)} ↓ / ${formatRate(network.up_kbps)} ↑`));
  details.append(detail("MTU", network.mtu ? String(network.mtu) : "—"));
  details.append(detail("Metered", network.metered ? "Yes" : "No"));
  card.append(details);
  return card;
}

function policyControl(node, label, field) {
  const wrapper = el("label", "", label);
  const select = document.createElement("select");
  for (const policy of POLICIES) {
    const option = document.createElement("option");
    option.value = policy;
    option.textContent = prettyPolicy(policy);
    option.selected = node[field] === policy;
    select.append(option);
  }
  select.addEventListener("change", () => updateNode(node.node_id, { [field]: select.value }));
  wrapper.append(select);
  return wrapper;
}

async function updateNode(nodeId, patch) {
  try {
    await api(`/api/v1/nodes/${encodeURIComponent(nodeId)}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    });
    showToast(`Updated ${nodeId}`);
    await refresh(false);
  } catch (error) {
    showToast(error.message, true);
    await refresh(false);
  }
}

function renderCircuits() {
  elements.circuits.replaceChildren();
  const visible = state.circuits.filter((circuit) =>
    elements.filter.value === "all" || ["open", "pending"].includes(circuit.status)
  );
  if (!visible.length) {
    const row = document.createElement("tr");
    const cell = el("td", "empty-cell", "No matching circuits.");
    cell.colSpan = 8;
    row.append(cell);
    elements.circuits.append(row);
    return;
  }
  for (const circuit of visible) {
    const row = document.createElement("tr");
    const statusCell = document.createElement("td");
    statusCell.append(statusDot(statusClass(circuit.status), circuit.status));
    row.append(statusCell);
    row.append(el("td", "", circuit.node_id));
    const protocolCell = document.createElement("td");
    protocolCell.append(el("span", "protocol-pill", circuit.protocol));
    row.append(protocolCell);
    row.append(el("td", "", `${circuit.target_host}:${circuit.target_port}`));
    row.append(el("td", "", prettyPolicy(circuit.exit_policy)));
    row.append(el("td", "", `${formatBytes(circuit.bytes_down)} ↓ / ${formatBytes(circuit.bytes_up)} ↑`));
    row.append(el("td", "", formatAge(circuit.created_at)));
    const action = document.createElement("td");
    if (["open", "pending"].includes(circuit.status)) {
      const button = el("button", "danger-button", "Close");
      button.addEventListener("click", () => closeCircuit(circuit.id));
      action.append(button);
    }
    row.append(action);
    ["Status", "Node", "Protocol", "Target", "Exit", "Traffic", "Age", "Action"].forEach((label, index) => {
      row.children[index].dataset.label = label;
    });
    elements.circuits.append(row);
  }
}

async function closeCircuit(id) {
  try {
    await api(`/api/v1/circuits/${encodeURIComponent(id)}`, { method: "DELETE" });
    showToast("Circuit closed");
    await refresh(false);
  } catch (error) {
    showToast(error.message, true);
  }
}

function statusDot(kind, text) {
  return el("span", `status ${kind}`, text);
}

function statusClass(status) {
  if (status === "open") return "online";
  if (status === "pending") return "warning";
  if (status === "failed") return "error";
  return "offline";
}

function detail(label, value) {
  const row = el("span");
  row.append(document.createTextNode(label));
  row.append(el("b", "", value));
  return row;
}

function miniStat(label, value) {
  const wrapper = el("div", "mini-stat");
  wrapper.append(el("span", "", label), el("strong", "", value));
  return wrapper;
}

function empty(message) {
  return el("div", "empty-state", message);
}

function el(tag, className = "", text = "") {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== "") element.textContent = text;
  return element;
}

function setConnection(message, kind) {
  elements.connection.textContent = message;
  elements.connection.className = `status ${kind}`;
}

let toastTimer;
function showToast(message, error = false) {
  clearTimeout(toastTimer);
  elements.toast.textContent = message;
  elements.toast.className = `visible${error ? " error" : ""}`;
  toastTimer = setTimeout(() => { elements.toast.className = ""; }, 3200);
}

function prettyPolicy(policy = "") {
  return policy.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function formatBytes(value = 0) {
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

function formatRate(kbps = 0) {
  if (!kbps) return "—";
  return kbps >= 1000 ? `${(kbps / 1000).toFixed(0)} Mbps` : `${kbps} Kbps`;
}

function formatClock(seconds) {
  const minutes = Math.floor(seconds / 60);
  return `${minutes}:${String(seconds % 60).padStart(2, "0")}`;
}

function formatAge(timestamp) {
  if (!timestamp) return "never";
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(timestamp).getTime()) / 1000));
  if (seconds < 5) return "now";
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}
