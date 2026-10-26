/**
 * Track map drawn on a HiDPI canvas, the web counterpart of the desktop TrackMapView: the driven
 * line coloured by a per-sample value (speed, or time gained/lost against a reference lap),
 * numbered markers, the start/finish dot, a gradient legend and a cursor linked to the charts.
 *
 * Input is the TrackOutline JSON of GET /api/sessions/{id}/laps/{lapId}/track
 * ({ source, distance[], x[], y[], headingClosureDeg, positionClosureM }).
 */
(function () {
  const BACKGROUND = "#0d1118";
  const CASING = "#1f2937";
  const PLAIN_LINE = "#ff3d2e";
  const LABEL = "#e8edf4";
  const MUTED = "#8b9cb3";
  const CURSOR = "#facc15";
  const NEUTRAL = [0x64, 0x74, 0x8b];
  const GAIN = [0x22, 0xc5, 0x5e];
  const LOSS = [0xef, 0x44, 0x44];
  const FONT = '"Source Sans 3", system-ui, sans-serif';

  const PAD = 28;
  /** Room under the outline for the legend and the caption. */
  const BOTTOM_RESERVE = 46;
  /** Upper bound of drawn line segments; long 200 Hz laps are thinned to this. */
  const MAX_SEGMENTS = 4000;
  const MIN_HEIGHT = 200;
  /** A marker label closer than this (px) to the S/F dot or an earlier label is left out. */
  const MIN_LABEL_GAP = 14;

  const NO_TRACK = "No GPS or yaw-rate channel in this lap: track map unavailable.";

  function clamp(v, lo, hi) {
    return Math.min(hi, Math.max(lo, v));
  }

  /** HSB colour as used by JavaFX Color.hsb (h in degrees, s and v 0–1). */
  function hsb(h, s, v) {
    const channel = (n) => {
      const k = (n + h / 60) % 6;
      return Math.round(255 * (v - v * s * Math.max(0, Math.min(k, 4 - k, 1))));
    };
    return `rgb(${channel(5)}, ${channel(3)}, ${channel(1)})`;
  }

  function mix(a, b, t) {
    const c = (i) => Math.round(a[i] + (b[i] - a[i]) * t);
    return `rgb(${c(0)}, ${c(1)}, ${c(2)})`;
  }

  /** First index whose value is >= target in an ascending array, clamped to the array. */
  function indexAtOrAfter(values, target) {
    let lo = 0;
    let hi = values.length - 1;
    while (lo < hi) {
      const mid = (lo + hi) >>> 1;
      if (values[mid] < target) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return Math.max(0, lo);
  }

  function percentileAbs(values, q) {
    const sorted = Array.from(values, (v) => Math.abs(v)).sort((a, b) => a - b);
    if (!sorted.length) {
      return 1;
    }
    const p = sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))];
    return p > 0 ? p : 1;
  }

  function minMax(values) {
    let min = Infinity;
    let max = -Infinity;
    for (const v of values) {
      if (v < min) {
        min = v;
      }
      if (v > max) {
        max = v;
      }
    }
    return [min, max];
  }

  class TrackMap {
    /**
     * @param {HTMLElement} root empty container; the map takes its width and sets its own height
     * @param {string} emptyMessage shown until a track is set
     */
    constructor(root, emptyMessage) {
      this.root = root;
      root.classList.add("track-map");
      this.canvas = document.createElement("canvas");
      this.canvas.setAttribute("role", "img");
      this.status = document.createElement("p");
      this.status.className = "track-map-status";
      this.caption = document.createElement("p");
      this.caption.className = "track-map-caption";
      root.append(this.canvas, this.status, this.caption);
      /** Outline, legend and markers; the cursor is composited on top of it. */
      this.base = document.createElement("canvas");

      this.outline = null;
      this.bounds = null;
      this.values = [];
      this.range = [0, 1];
      this.scale = "speed";
      this.lowLabel = "slow";
      this.highLabel = "fast";
      this.markers = [];
      this.cursorIndex = -1;
      this.loading = false;
      this.view = null;
      this.size = { w: 0, h: 0, dpr: 1 };
      this.frame = 0;

      if (typeof ResizeObserver === "function") {
        new ResizeObserver(() => this.requestRender()).observe(root);
      }
      // Browser zoom changes devicePixelRatio without necessarily resizing the container.
      window.addEventListener("resize", () => this.requestRender());
      document.fonts?.ready.then(() => this.requestRender());
      this.showMessage(emptyMessage);
    }

    /** Clears the map and shows a centred message; loading adds a progress indicator. */
    showMessage(text, loading = false) {
      this.outline = null;
      this.bounds = null;
      this.values = [];
      this.markers = [];
      this.cursorIndex = -1;
      this.caption.textContent = "";
      this.status.textContent = text;
      this.status.hidden = false;
      this.loading = loading;
      this.root.classList.toggle("is-loading", loading);
      this.canvas.setAttribute("aria-label", text);
      this.requestRender();
    }

    setTrack(outline) {
      this.values = [];
      this.markers = [];
      this.cursorIndex = -1;
      this.loading = false;
      this.root.classList.remove("is-loading");
      const usable = outline && outline.source !== "NONE" && Array.isArray(outline.x) && outline.x.length > 1
        && Array.isArray(outline.y) && outline.y.length === outline.x.length;
      if (!usable) {
        this.showMessage(NO_TRACK);
        return;
      }
      this.outline = outline;
      const [minX, maxX] = minMax(outline.x);
      const [minY, maxY] = minMax(outline.y);
      this.bounds = { minX, maxX, minY, maxY };
      this.status.hidden = true;
      this.caption.textContent = outline.source === "GPS"
        ? "GPS projection"
        : `Reconstructed from yaw rate · closure ${outline.headingClosureDeg.toFixed(1)}° / `
          + `${outline.positionClosureM.toFixed(0)} m corrected`;
      this.canvas.setAttribute("aria-label", `Track map (${this.caption.textContent})`);
      this.requestRender();
    }

    /**
     * Values aligned with the outline samples.
     * @param {"speed" | "delta"} scale speed: blue → red over min…max; delta: green (negative) →
     *   grey → red (positive), saturating at the 95th percentile of |value|
     */
    setValues(values, scale, lowLabel, highLabel) {
      this.values = Array.isArray(values) ? values : [];
      this.scale = scale;
      this.lowLabel = lowLabel;
      this.highLabel = highLabel;
      if (this.values.length) {
        this.range = scale === "speed" ? minMax(this.values) : [0, percentileAbs(this.values, 0.95)];
      }
      this.requestRender();
    }

    /** @param {{ distance: number, label: string, color: string }[]} markers */
    setMarkers(markers) {
      this.markers = Array.isArray(markers) ? markers : [];
      this.requestRender();
    }

    /** Outline sample index of the cursor, or -1 to hide it. */
    setCursorIndex(index) {
      const n = this.outline ? this.outline.x.length : 0;
      const next = n && Number.isInteger(index) && index >= 0 ? Math.min(index, n - 1) : -1;
      if (next !== this.cursorIndex) {
        this.cursorIndex = next;
        this.composite();
      }
    }

    /** Cursor at a lap distance in metres; NaN hides it. */
    setCursorDistance(distance) {
      this.setCursorIndex(Number.isFinite(distance) ? this.indexOfDistance(distance) : -1);
    }

    /** Outline sample index at or after a lap distance (metres), or -1 without a track. */
    indexOfDistance(distance) {
      return this.outline ? Math.min(indexAtOrAfter(this.outline.distance, distance), this.outline.x.length - 1) : -1;
    }

    requestRender() {
      if (!this.frame) {
        this.frame = requestAnimationFrame(() => {
          this.frame = 0;
          this.render();
        });
      }
    }

    colored() {
      return this.outline !== null && this.values.length === this.outline.x.length;
    }

    color(v) {
      const [lo, hi] = this.range;
      if (this.scale === "speed") {
        const f = hi > lo ? clamp((v - lo) / (hi - lo), 0, 1) : 0.5;
        return hsb(240 * (1 - f), 0.85, 0.95);
      }
      const f = hi > 0 ? clamp(v / hi, -1, 1) : 0;
      return f < 0 ? mix(NEUTRAL, GAIN, -f) : mix(NEUTRAL, LOSS, f);
    }

    /**
     * Canvas height that fits the outline's aspect ratio into the available width, within the
     * CSS max-height of the canvas (the pinned map of narrow layouts sets one).
     */
    preferredHeight(w) {
      const cap = parseFloat(getComputedStyle(this.canvas).maxHeight) || Infinity;
      if (!this.bounds) {
        // While loading keep the previous height, so the page does not jump twice.
        const h = this.loading && w === this.size.w && this.size.h ? this.size.h : clamp(Math.round(w * 0.55), 200, 300);
        return Math.min(h, cap);
      }
      const { minX, maxX, minY, maxY } = this.bounds;
      const aspect = (maxY - minY) / Math.max(1e-9, maxX - minX);
      const maxH = Math.max(MIN_HEIGHT, Math.min(window.innerHeight * 0.75, w * 1.2));
      return Math.round(Math.min(cap, clamp((w - 2 * PAD) * aspect + 2 * PAD + BOTTOM_RESERVE, MIN_HEIGHT, maxH)));
    }

    render() {
      const w = Math.floor(this.root.clientWidth);
      if (w < 10) {
        return;
      }
      const h = this.preferredHeight(w);
      const dpr = window.devicePixelRatio || 1;
      if (w !== this.size.w || h !== this.size.h || dpr !== this.size.dpr) {
        this.size = { w, h, dpr };
        for (const c of [this.canvas, this.base]) {
          c.width = Math.round(w * dpr);
          c.height = Math.round(h * dpr);
        }
        this.canvas.style.width = `${w}px`;
        this.canvas.style.height = `${h}px`;
      }
      const g = this.base.getContext("2d");
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.fillStyle = BACKGROUND;
      g.fillRect(0, 0, w, h);
      this.view = null;
      if (this.outline) {
        this.drawOutline(g, w, h);
      }
      this.composite();
    }

    drawOutline(g, w, h) {
      const { x, y } = this.outline;
      const n = x.length;
      const { minX, maxX, minY, maxY } = this.bounds;
      const innerH = h - 2 * PAD - BOTTOM_RESERVE;
      const sc = Math.min((w - 2 * PAD) / Math.max(1e-9, maxX - minX), innerH / Math.max(1e-9, maxY - minY));
      const ox = PAD + ((w - 2 * PAD) - sc * (maxX - minX)) / 2;
      const oy = PAD + (innerH - sc * (maxY - minY)) / 2;
      const px = (i) => ox + (x[i] - minX) * sc;
      const py = (i) => oy + (maxY - y[i]) * sc;
      this.view = { px, py };

      const step = Math.max(1, Math.floor(n / MAX_SEGMENTS));
      const colored = this.colored();
      g.lineCap = "round";
      g.lineJoin = "round";
      g.lineWidth = 6;
      g.strokeStyle = CASING;
      g.beginPath();
      g.moveTo(px(0), py(0));
      for (let i = step; i < n; i += step) {
        g.lineTo(px(i), py(i));
      }
      g.lineTo(px(n - 1), py(n - 1));
      g.stroke();

      g.lineWidth = 3;
      if (colored) {
        for (let i = step, prev = 0; prev < n - 1; prev = i, i = Math.min(n - 1, i + step)) {
          g.strokeStyle = this.color(this.values[i]);
          g.beginPath();
          g.moveTo(px(prev), py(prev));
          g.lineTo(px(i), py(i));
          g.stroke();
        }
      } else {
        g.strokeStyle = PLAIN_LINE;
        g.stroke();
      }

      g.textAlign = "left";
      g.textBaseline = "middle";
      // On short laps (out-lap fragments) markers bunch up: keep their dots, but drop labels
      // that would overprint S/F or an earlier label. S/F is drawn last so it stays on top.
      const sx = px(0);
      const sy = py(0);
      const labelled = [[sx, sy]];
      for (const m of this.markers) {
        const i = this.indexOfDistance(m.distance);
        const mx = px(i);
        const my = py(i);
        g.fillStyle = m.color;
        dot(g, mx, my, 4);
        if (labelled.every(([lx, ly]) => Math.hypot(mx - lx, my - ly) >= MIN_LABEL_GAP)) {
          labelled.push([mx, my]);
          label(g, m.label, mx + 6, my - 6, `600 10px ${FONT}`, LABEL);
        }
      }
      g.fillStyle = "#ffffff";
      dot(g, sx, sy, 5);
      label(g, "S/F", sx + 8, sy - 8, `600 11px ${FONT}`, "#ffffff");

      if (colored) {
        this.drawLegend(g, w, h);
      }
    }

    drawLegend(g, w, h) {
      g.font = `11px ${FONT}`;
      // Wide enough for both end labels on narrow maps.
      const labelsW = g.measureText(this.lowLabel).width + g.measureText(this.highLabel).width + 12;
      const barW = Math.round(Math.min(w - 28, Math.max(Math.min(180, w * 0.4), labelsW)));
      const bx = w - barW - 14;
      const by = h - 40;
      const [lo, hi] = this.range;
      for (let k = 0; k < barW; k++) {
        const f = k / barW;
        g.fillStyle = this.color(this.scale === "speed" ? lo + f * (hi - lo) : (-1 + 2 * f) * hi);
        g.fillRect(bx + k, by, 1.5, 8);
      }
      g.fillStyle = MUTED;
      g.textBaseline = "top";
      g.textAlign = "left";
      g.fillText(this.lowLabel, bx, by + 10);
      g.textAlign = "right";
      g.fillText(this.highLabel, bx + barW, by + 10);
    }

    /** Copies the cached base layer and draws the cursor over it. */
    composite() {
      const { w, h, dpr } = this.size;
      if (!w || !h) {
        return;
      }
      const g = this.canvas.getContext("2d");
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.drawImage(this.base, 0, 0);
      if (this.view && this.cursorIndex >= 0) {
        g.setTransform(dpr, 0, 0, dpr, 0, 0);
        const cx = this.view.px(this.cursorIndex);
        const cy = this.view.py(this.cursorIndex);
        g.fillStyle = CURSOR;
        dot(g, cx, cy, 6);
        g.strokeStyle = "#000000";
        g.lineWidth = 1.5;
        g.stroke();
      }
    }
  }

  function dot(g, x, y, r) {
    g.beginPath();
    g.arc(x, y, r, 0, 2 * Math.PI);
    g.fill();
  }

  /** Text with a dark halo so labels stay legible where they cross the line. */
  function label(g, text, x, y, font, color) {
    g.font = font;
    g.lineWidth = 3;
    g.strokeStyle = "rgba(13, 17, 24, 0.85)";
    g.strokeText(text, x, y);
    g.fillStyle = color;
    g.fillText(text, x, y);
  }

  TrackMap.GAIN_COLOR = "#22c55e";
  TrackMap.LOSS_COLOR = "#ef4444";
  TrackMap.BRAKE_COLOR = "#ff3d2e";
  window.TrackMap = TrackMap;
})();
