// Dashboard unit tests. Run with `node --test frontend/`, no dependencies and
// no build step, matching how the dashboard itself is served.
import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  DEFAULT_SOCKS,
  formatAge,
  formatBytes,
  formatClock,
  formatRate,
  prettyPolicy,
  secondsRemaining,
  socksEndpoint,
  statusClass,
  svgDataURI,
  trafficSample,
} from "./lib.js";

describe("formatBytes", () => {
  it("keeps small values in bytes", () => {
    assert.equal(formatBytes(0), "0 B");
    assert.equal(formatBytes(1023), "1023 B");
  });

  it("climbs a unit at a time", () => {
    assert.equal(formatBytes(1024), "1.00 KiB");
    assert.equal(formatBytes(1024 ** 2), "1.00 MiB");
    assert.equal(formatBytes(1024 ** 3), "1.00 GiB");
    assert.equal(formatBytes(1024 ** 4), "1.00 TiB");
  });

  it("stops at TiB rather than running off the end of the unit list", () => {
    assert.equal(formatBytes(1024 ** 5), "1024 TiB");
  });

  it("drops decimals as the number gets wider, so the column does not jump", () => {
    assert.equal(formatBytes(9.5 * 1024), "9.50 KiB");
    assert.equal(formatBytes(99 * 1024), "99.0 KiB");
    assert.equal(formatBytes(500 * 1024), "500 KiB");
  });

  it("treats a missing or unreadable counter as zero", () => {
    assert.equal(formatBytes(), "0 B");
    assert.equal(formatBytes(null), "0 B");
    assert.equal(formatBytes("not a number"), "0 B");
  });
});

describe("formatRate", () => {
  it("shows an em dash rather than a zero for an idle link", () => {
    assert.equal(formatRate(0), "—");
    assert.equal(formatRate(), "—");
  });

  it("switches to Mbps at a thousand Kbps", () => {
    assert.equal(formatRate(999), "999 Kbps");
    assert.equal(formatRate(1000), "1 Mbps");
    assert.equal(formatRate(45000), "45 Mbps");
  });
});

describe("formatClock", () => {
  it("pads the seconds so the countdown does not change width", () => {
    assert.equal(formatClock(600), "10:00");
    assert.equal(formatClock(65), "1:05");
    assert.equal(formatClock(9), "0:09");
    assert.equal(formatClock(0), "0:00");
  });
});

describe("formatAge", () => {
  const now = Date.parse("2026-09-09T18:00:00Z");
  const ago = (seconds) => new Date(now - seconds * 1000).toISOString();

  it("names the absence of a timestamp", () => {
    assert.equal(formatAge("", now), "never");
    assert.equal(formatAge(undefined, now), "never");
  });

  it("rounds down through each unit", () => {
    assert.equal(formatAge(ago(1), now), "now");
    assert.equal(formatAge(ago(5), now), "5s ago");
    assert.equal(formatAge(ago(59), now), "59s ago");
    assert.equal(formatAge(ago(60), now), "1m ago");
    assert.equal(formatAge(ago(3599), now), "59m ago");
    assert.equal(formatAge(ago(3600), now), "1h ago");
    assert.equal(formatAge(ago(86399), now), "23h ago");
    assert.equal(formatAge(ago(86400), now), "1d ago");
  });

  it("clamps a timestamp from the future instead of showing a negative age", () => {
    assert.equal(formatAge(ago(-90), now), "now");
  });
});

describe("prettyPolicy", () => {
  it("turns the wire form into a label", () => {
    assert.equal(prettyPolicy("WIFI_PREFERRED"), "Wifi Preferred");
    assert.equal(prettyPolicy("AUTO"), "Auto");
  });

  it("survives a policy the server added that this build does not know", () => {
    assert.equal(prettyPolicy(""), "");
    assert.equal(prettyPolicy(), "");
    assert.equal(prettyPolicy("SOME_FUTURE_MODE"), "Some Future Mode");
  });
});

describe("statusClass", () => {
  it("maps each circuit status to its indicator", () => {
    assert.equal(statusClass("open"), "online");
    assert.equal(statusClass("pending"), "warning");
    assert.equal(statusClass("failed"), "error");
    assert.equal(statusClass("closed"), "offline");
  });

  it("falls back to offline for a status this build does not know", () => {
    assert.equal(statusClass("draining"), "offline");
    assert.equal(statusClass(undefined), "offline");
  });
});

describe("socksEndpoint", () => {
  it("uses the server's hint when it sends one", () => {
    assert.deepEqual(
      socksEndpoint({ socks: { host: "10.0.0.4", port: 9050, username: "cesar" } }),
      { host: "10.0.0.4", port: 9050, username: "cesar" },
    );
  });

  it("falls back to the documented personal-mode defaults", () => {
    assert.deepEqual(socksEndpoint({}), DEFAULT_SOCKS);
    assert.deepEqual(socksEndpoint(null), DEFAULT_SOCKS);
  });

  it("fills in only the fields the hint left out", () => {
    assert.deepEqual(socksEndpoint({ socks: { port: 1081 } }), {
      host: DEFAULT_SOCKS.host,
      port: 1081,
      username: DEFAULT_SOCKS.username,
    });
  });
});

describe("svgDataURI", () => {
  it("wraps markup that really is an SVG", () => {
    const uri = svgDataURI('<svg xmlns="http://www.w3.org/2000/svg"></svg>');
    assert.ok(uri.startsWith("data:image/svg+xml;charset=utf-8,"));
    assert.ok(uri.includes("%3Csvg"));
  });

  it("tolerates the leading whitespace an XML declaration leaves behind", () => {
    assert.notEqual(svgDataURI("\n  <svg></svg>"), "");
  });

  // The QR arrives from the server as markup and is put in an img src. Anything
  // that is not an SVG has no business being there, so it becomes nothing at
  // all rather than a URI the browser will try to interpret.
  it("refuses anything that is not an SVG", () => {
    assert.equal(svgDataURI("<script>alert(1)</script>"), "");
    assert.equal(svgDataURI("javascript:alert(1)"), "");
    assert.equal(svgDataURI("<!DOCTYPE html><svg></svg>"), "");
    assert.equal(svgDataURI(""), "");
    assert.equal(svgDataURI(null), "");
    assert.equal(svgDataURI(42), "");
    assert.equal(svgDataURI({ toString: () => "<svg></svg>" }), "");
  });

  it("escapes the characters that would end the attribute early", () => {
    const uri = svgDataURI('<svg data-x="a b"># and &</svg>');
    for (const character of ['"', "#", "&", " "]) {
      assert.ok(!uri.includes(character), `${character} survived into the data URI`);
    }
  });
});

describe("trafficSample", () => {
  it("turns counter deltas into a per-second rate", () => {
    assert.deepEqual(
      trafficSample({ up: 100, down: 400 }, { up: 400, down: 1000 }, 3),
      { up: 100, down: 200 },
    );
  });

  it("reports zero while nothing moves", () => {
    assert.deepEqual(trafficSample({ up: 7, down: 7 }, { up: 7, down: 7 }, 3), { up: 0, down: 0 });
  });

  // A node that re-registers restarts its counters. Without the clamp the chart
  // would plot a large negative spike and rescale every other series to nothing.
  it("clamps a counter reset instead of plotting a negative rate", () => {
    assert.deepEqual(
      trafficSample({ up: 5_000, down: 9_000 }, { up: 12, down: 0 }, 3),
      { up: 0, down: 0 },
    );
  });
});

describe("secondsRemaining", () => {
  const now = Date.parse("2026-09-09T18:00:00Z");

  it("counts down to the expiry the server sent", () => {
    assert.equal(secondsRemaining("2026-09-09T18:10:00Z", now), 600);
    assert.equal(secondsRemaining("2026-09-09T18:00:30Z", now), 30);
  });

  it("floors at zero once the code has expired", () => {
    assert.equal(secondsRemaining("2026-09-09T17:59:00Z", now), 0);
  });

  // null is distinct from 0: the countdown is hidden rather than shown stuck.
  it("returns null for a timestamp it cannot read", () => {
    assert.equal(secondsRemaining("not a timestamp", now), null);
    assert.equal(secondsRemaining("", now), null);
    assert.equal(secondsRemaining(undefined, now), null);
  });
});
