"use strict";

const POLICIES = [
  "AUTO",
  "WIFI_ONLY",
  "CELLULAR_ONLY",
  "WIFI_PREFERRED",
  "CELLULAR_PREFERRED",
];

const state = {
  token: sessionStorage.getItem("pocketexit.adminToken") || "",
  nodes: [],
  circuits: [],
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
};

elements.token.value = state.token;
elements.authForm.addEventListener("submit", (event) => {
  event.preventDefault();
  saveToken();
});
elements.refresh.addEventListener("click", refresh);
elements.filter.addEventListener("change", renderCircuits);
window.addEventListener("resize", drawTrafficChart);
elements.pairClose.addEventListener("click", () => elements.pairDialog.close());
elements.pairDialog.addEventListener("close", () => elements.pairQr.removeAttribute("src"));

if (state.token) refresh();
startPolling();

function saveToken() {
  state.token = elements.token.value.trim();
  sessionStorage.setItem("pocketexit.adminToken", state.token);
  refresh();
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
    setConnection("Connected", "online");
    elements.lastSync.textContent = `Updated ${new Date().toLocaleTimeString([], {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    })}`;
    render();
  } catch (error) {
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
    throw new Error(message);
  }
  return payload;
}

function render() {
  renderSummary();
  renderNodes();
  renderCircuits();
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
    elements.nodes.append(empty("No Android nodes have registered yet."));
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
  const pair = el("button", "secondary pair-button", "Pair phone");
  pair.type = "button";
  pair.addEventListener("click", () => openPairing(node));
  const toggle = el("label", "switch");
  const checkbox = document.createElement("input");
  checkbox.type = "checkbox";
  checkbox.checked = Boolean(node.enabled);
  checkbox.addEventListener("change", () => updateNode(node.node_id, { enabled: checkbox.checked }));
  toggle.append(checkbox, document.createTextNode("Selectable"));
  actions.append(pair, toggle);
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
    elements.pairQr.src = `data:image/svg+xml;base64,${btoa(payload.qr_svg)}`;
    elements.pairDialog.showModal();
  } catch (error) {
    showToast(error.message, true);
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

function formatAge(timestamp) {
  if (!timestamp) return "never";
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(timestamp).getTime()) / 1000));
  if (seconds < 5) return "now";
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}
