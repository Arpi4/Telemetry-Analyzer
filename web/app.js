// Local: Spring Boot. Deployed: Render (CORS on backend). Set RENDER_API_BASE for production.
const RENDER_API_BASE = "https://telemetry-analyzer.onrender.com/api";
const apiBase =
  window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1"
    ? "http://localhost:8080/api"
    : RENDER_API_BASE;

const TRACK_CHART_MAX_POINTS = 12_000;
const SECTOR_COUNT = 10;
/** Half window of the local time-loss rate that colours the comparison map (as on the desktop). */
const RATE_HALF_WINDOW_M = 25;

let speedChart;
let throttleChart;
let brakeChart;
let gearChart;
let steeringChart;
let accelChart;
let rpmChart;
let compareSpeedChart;
let compareDeltaChart;

/**
 * Chart series of the selected lap. The optional channels (steeringAngle, lateralAcceleration,
 * longitudinalAcceleration, engineRpm) are null when no sample of the lap has them.
 * @type {{ points: any[], labels: any[], speed: number[], throttle: number[], brake: number[], gear: number[],
 *   steering: (number|null)[] | null, lateralG: (number|null)[] | null, longitudinalG: (number|null)[] | null,
 *   rpm: (number|null)[] | null, lapBoundaries?: any[] } | null}
 */
let fullLapSnapshot = null;
/** @type {string | null} */
let currentSessionId = null;
/** @type {any[]} */
let currentSessionLaps = [];
let currentLapIndex = 0;
/** Overview of the loaded session (lap times, complete laps, fastest lap), or null. */
let currentSessionOverview = null;
// Imported sessions and everything derived from them never change: fetch each once.
const sessionDetailsCache = new Map();
const sessionOverviewCache = new Map();
const lapTrackCache = new Map();
const lapEventsCache = new Map();
const alignedCompareCache = new Map();

const lapTrackMap = new TrackMap(document.getElementById("lapTrackMap"), "Load a session to draw the track map.");
const compareTrackMap = new TrackMap(document.getElementById("compareTrackMap"), "Compare two laps to colour the track by time delta.");
/** Incremented per update so a slow response cannot overwrite a newer selection. */
let sessionRequest = 0;
let lapMapRequest = 0;
let compareRequest = 0;
const compareLapRequests = { ref: 0, cmp: 0 };
/** Reference lap distance (m) of each point of the compare speed and time delta charts. */
let compareChartDistance = [];
/** Chart -> data index that mirrorChartHover shows on the next frame. */
const mirroredHover = new Map();

async function parseJsonResponse(res) {
  const text = await res.text();
  const trimmed = text.trim();
  if (!trimmed) {
    return null;
  }
  try {
    return JSON.parse(trimmed);
  } catch {
    throw new Error(`HTTP ${res.status}: ${trimmed.slice(0, 200)}`);
  }
}

const chartTheme = {
  responsive: true,
  maintainAspectRatio: true,
  interaction: { mode: "index", intersect: false },
  plugins: {
    legend: {
      display: true,
      labels: { color: "#e8edf4", font: { size: 12 } }
    }
  },
  scales: {
    x: {
      ticks: { color: "#8b9cb3", maxTicksLimit: 12 },
      grid: { color: "rgba(255,255,255,0.06)" }
    },
    y: {
      ticks: { color: "#8b9cb3" },
      grid: { color: "rgba(255,255,255,0.06)" }
    }
  }
};

const lapBoundariesPlugin = {
  id: "lapBoundaries",
  afterDraw(chart, _args, pluginOptions) {
    const boundaries = pluginOptions?.boundaries;
    if (!Array.isArray(boundaries) || boundaries.length === 0) {
      return;
    }
    const xScale = chart.scales?.x;
    const yScale = chart.scales?.y;
    if (!xScale || !yScale) {
      return;
    }
    const ctx = chart.ctx;
    ctx.save();
    ctx.strokeStyle = "rgba(255,255,255,0.35)";
    ctx.setLineDash([5, 4]);
    ctx.lineWidth = 1;
    for (const boundary of boundaries) {
      if (!Number.isFinite(boundary.startIndex)) {
        continue;
      }
      const x = xScale.getPixelForValue(boundary.startIndex);
      ctx.beginPath();
      ctx.moveTo(x, yScale.top);
      ctx.lineTo(x, yScale.bottom);
      ctx.stroke();
    }
    ctx.restore();
  }
};

Chart.register(lapBoundariesPlugin);

/** Track-map cursor callbacks of the charts linked by linkChartToMap, by canvas id. */
const chartCursorLinks = new Map();

/**
 * Moves the track-map cursor of a linked chart to the sample its tooltip shows, once Chart.js has
 * handled the pointer event, so the tooltip, a mirrored tooltip and the map cursor share one
 * index. (A hit test of our own could land a pixel, and so a sample or more, away from the one
 * Chart.js picks.) Replayed events (a chart update) are left alone: they move no pointer.
 */
const cursorLinkPlugin = {
  id: "cursorLink",
  afterEvent(chart, args) {
    const moveCursor = chartCursorLinks.get(chart.canvas?.id);
    if (!moveCursor || args.replay) {
      return;
    }
    // The pointer on this chart wins over a hover mirrored into it that has not been drawn yet
    // (the chart the pointer just left clears its partner: that must not clear this one).
    mirroredHover.delete(chart);
    const active = chart.tooltip ? chart.tooltip.getActiveElements() : chart.getActiveElements();
    moveCursor(active.length ? active[0].index : -1);
  }
};

Chart.register(cursorLinkPlugin);

/** Grid line colours that pick out y = 0 on charts with signed values. */
const zeroLineGrid = {
  color: (ctx) => (ctx.tick?.value === 0 ? "rgba(255,255,255,0.35)" : "rgba(255,255,255,0.06)")
};

/**
 * Y axis of a chart with signed values: the zero line stands out, and every tick is kept, as
 * on short phone charts auto-skipping drops every other one, 0 among them (3, 1, -1, -3).
 */
function signedYAxis(options) {
  options.scales.y.grid = zeroLineGrid;
  options.scales.y.ticks = { ...options.scales.y.ticks, autoSkip: false };
}

/**
 * Fetches JSON once per key; a failed request is dropped from the cache so it can be retried.
 * Rejects with an Error carrying the HTTP status (0 when the server could not be reached).
 */
function fetchJsonCached(cache, key, url) {
  if (!cache.has(key)) {
    const request = fetch(url).then(async (res) => {
      const data = await parseJsonResponse(res).catch(() => null);
      if (!res.ok || data === null) {
        throw Object.assign(new Error(data?.error || `HTTP ${res.status}`), { status: res.status });
      }
      return data;
    }, (err) => {
      throw Object.assign(new Error(`server not reachable (${err.message})`), { status: 0 });
    });
    request.catch(() => cache.delete(key));
    cache.set(key, request);
  }
  return cache.get(key);
}

/** Short reason for a failed session request; the backend keeps sessions in memory only. */
function sessionErrorText(err) {
  return err?.status === 404 ? "it is no longer on the server (it may have restarted). Press Refresh." : err?.message;
}

function setSessionStatus(text, isError = false) {
  const el = document.getElementById("sessionStatus");
  if (!el) {
    return;
  }
  el.textContent = text || "";
  el.hidden = !text;
  el.classList.toggle("is-error", isError);
}

function fetchSessionDetails(sessionId) {
  return fetchJsonCached(sessionDetailsCache, sessionId, `${apiBase}/sessions/${encodeURIComponent(sessionId)}`);
}

/** Lap times and lengths, complete laps and the fastest lap of a session. */
function fetchSessionOverview(sessionId) {
  return fetchJsonCached(sessionOverviewCache, sessionId,
    `${apiBase}/sessions/${encodeURIComponent(sessionId)}/overview?sectorCount=${SECTOR_COUNT}`);
}

/** "01:47.123 ★ fastest" / "(partial)" after the lap id, as in the desktop lap lists. */
function lapOptionSuffix(info, fastestLapId, shortFastest) {
  if (!info) {
    return "";
  }
  const flag = info.lapId === fastestLapId ? (shortFastest ? " ★" : " ★ fastest") : info.complete === false ? " (partial)" : "";
  return ` · ${formatLapTime(info.lapTime)}${flag}`;
}

function lapResourceUrl(sessionId, lapId, resource) {
  return `${apiBase}/sessions/${encodeURIComponent(sessionId)}/laps/${encodeURIComponent(lapId)}/${resource}`;
}

function fetchLapTrack(sessionId, lapId) {
  return fetchJsonCached(lapTrackCache, `${sessionId}/${lapId}`, lapResourceUrl(sessionId, lapId, "track"));
}

function fetchLapEvents(sessionId, lapId) {
  return fetchJsonCached(lapEventsCache, `${sessionId}/${lapId}`, lapResourceUrl(sessionId, lapId, "events"));
}

function fetchAlignedComparison(refSessionId, refLapId, cmpSessionId, cmpLapId) {
  const query = new URLSearchParams({
    referenceSessionId: refSessionId,
    referenceLapId: refLapId,
    compareSessionId: cmpSessionId,
    compareLapId: cmpLapId,
    sectorCount: String(SECTOR_COUNT)
  });
  return fetchJsonCached(alignedCompareCache, query.toString(), `${apiBase}/compare/aligned?${query}`);
}

/** Speed-coloured map of the selected lap with its braking points. */
async function updateLapTrackMap() {
  const request = ++lapMapRequest;
  const lap = currentSessionLaps[currentLapIndex];
  if (!currentSessionId || !lap) {
    lapTrackMap.showMessage(currentSessionId ? "This session has no lap data." : "Load a session to draw the track map.");
    return;
  }
  lapTrackMap.showMessage("Loading track map…", true);
  const [track, events] = await Promise.allSettled([
    fetchLapTrack(currentSessionId, lap.lapId),
    fetchLapEvents(currentSessionId, lap.lapId)
  ]);
  if (request !== lapMapRequest) {
    return;
  }
  if (track.status === "rejected") {
    lapTrackMap.showMessage(`Could not load the track map: ${track.reason.message}`);
    return;
  }
  lapTrackMap.setTrack(track.value);
  // The outline has one sample per lap sample, so speed colours it one to one.
  lapTrackMap.setValues(lap.points.map((p) => Number(p.speed) || 0), "speed", "slow", "fast");
  const zones = events.status === "fulfilled" && Array.isArray(events.value?.brakingZones) ? events.value.brakingZones : [];
  lapTrackMap.setMarkers(zones.map((z) => ({ distance: z.startDistance, label: `B${z.index}`, color: TrackMap.BRAKE_COLOR })));
}

/** d(Δt)/ds over a ±25 m window, in seconds per 100 m (positive = compare loses time). */
function lossRate(distance, deltaT) {
  const n = distance.length;
  const rate = new Array(n).fill(0);
  let lo = 0;
  let hi = 0;
  for (let k = 0; k < n; k++) {
    while (distance[lo] < distance[k] - RATE_HALF_WINDOW_M) {
      lo++;
    }
    while (hi < n - 1 && distance[hi + 1] <= distance[k] + RATE_HALF_WINDOW_M) {
      hi++;
    }
    const ds = distance[hi] - distance[lo];
    rate[k] = ds > 0 ? 100 * (deltaT[hi] - deltaT[lo]) / ds : 0;
  }
  return rate;
}

/** Piecewise linear y(x) at every grid position; x and grid ascending (as core Interpolation.resample). */
function resample(x, y, grid) {
  const n = x.length;
  const out = new Array(grid.length).fill(n ? y[0] : 0);
  if (n < 2) {
    return out;
  }
  let j = 0;
  for (let k = 0; k < grid.length; k++) {
    const s = grid[k];
    while (j < n - 2 && x[j + 1] < s) {
      j++;
    }
    if (s <= x[j]) {
      out[k] = y[j];
    } else if (s >= x[j + 1]) {
      out[k] = y[j + 1];
    } else {
      out[k] = y[j] + (s - x[j]) / (x[j + 1] - x[j]) * (y[j + 1] - y[j]);
    }
  }
  return out;
}

/** "+1.234" / "-1.234"; values that round to zero print as "0.000". */
function formatSigned(value, digits) {
  const rounded = Number(value.toFixed(digits));
  return `${rounded > 0 ? "+" : ""}${rounded.toFixed(digits)}`;
}

function renderSectorStrip(sectors, lapDelta) {
  const strip = document.getElementById("compareSectors");
  if (!strip) {
    return;
  }
  strip.innerHTML = "";
  strip.hidden = !Array.isArray(sectors) || sectors.length === 0;
  if (strip.hidden) {
    return;
  }
  const chip = (name, delta, title) => {
    const el = document.createElement("span");
    const text = formatSigned(delta, 3);
    el.className = `sector-chip ${text.startsWith("+") ? "loss" : text.startsWith("-") ? "gain" : ""}`;
    el.title = title;
    const label = document.createElement("b");
    label.textContent = name;
    el.append(label, text);
    return el;
  };
  const heading = document.createElement("span");
  heading.className = "sector-strip-title";
  heading.textContent = "Δt (s)";
  strip.append(heading, chip("Lap", lapDelta, "Compare minus reference lap time on the aligned distance axis"));
  for (const s of sectors) {
    strip.append(chip(`S${s.index}`, s.deltaSeconds,
      `${s.distanceStartM.toFixed(0)}–${s.distanceEndM.toFixed(0)} m · reference ${s.referenceAvgSpeedKmh.toFixed(1)} km/h · compare ${s.compareAvgSpeedKmh.toFixed(1)} km/h`));
  }
}

/** Reference lap outline coloured by where the compare lap gains or loses time, with sector starts. */
function drawCompareTrackMap(outline, comparison, sectors) {
  compareTrackMap.setTrack(outline);
  if (outline?.source !== "NONE" && comparison.distance.length > 1) {
    const rate = resample(comparison.distance, lossRate(comparison.distance, comparison.deltaTime), outline.distance);
    compareTrackMap.setValues(rate, "delta", "compare gains", "compare loses");
  }
  compareTrackMap.setMarkers(sectors
    .filter((s) => s.index !== 1) // starts at S/F, which is already marked
    .map((s) => ({
      distance: s.distanceStartM,
      label: `S${s.index}`,
      color: s.deltaSeconds > 0 ? TrackMap.LOSS_COLOR : TrackMap.GAIN_COLOR
    })));
}

/**
 * Calls moveCursor with the data index the chart's tooltip shows while the chart is hovered or
 * tapped (cursorLinkPlugin), or -1 when the tooltip goes or the mouse leaves the chart.
 */
function linkChartToMap(canvasId, moveCursor) {
  chartCursorLinks.set(canvasId, moveCursor);
  document.getElementById(canvasId)?.addEventListener("pointerleave", (event) => {
    if (event.pointerType !== "touch") {
      moveCursor(-1);
    }
  });
}

function redrawFullLapCharts() {
  if (!fullLapSnapshot) {
    return;
  }
  const { labels, speed, throttle, brake, gear, steering, lateralG, longitudinalG, rpm } = fullLapSnapshot;
  speedChart = drawOrUpdateChart(speedChart, "speedChart", labels, [
    { label: "Speed (km/h)", data: speed, borderColor: "#ff3d2e", backgroundColor: "rgba(255,61,46,0.12)", fill: false, tension: 0.15, pointRadius: 0, borderWidth: 2 }
  ]);
  throttleChart = drawOrUpdateChart(throttleChart, "throttleChart", labels, [
    { label: "Throttle", data: throttle, borderColor: "#22c55e", tension: 0.15, pointRadius: 0, borderWidth: 2 }
  ]);
  brakeChart = drawOrUpdateChart(brakeChart, "brakeChart", labels, [
    { label: "Brake", data: brake, borderColor: "#ef4444", tension: 0.15, pointRadius: 0, borderWidth: 2 }
  ]);
  gearChart = drawOrUpdateChart(gearChart, "gearChart", labels, [
    { label: "Gear", data: gear, borderColor: "#c084fc", tension: 0.1, pointRadius: 0, borderWidth: 2 }
  ]);
  steeringChart = drawOptionalChart(steeringChart, "steeringChart", labels, [
    steering && { label: "Steering angle (deg)", data: steering, borderColor: "#14a394", tension: 0.15, pointRadius: 0, borderWidth: 2 }
  ], signedYAxis);
  accelChart = drawOptionalChart(accelChart, "accelChart", labels, [
    lateralG && { label: "Lateral (g)", data: lateralG, borderColor: "#2a94da", tension: 0.15, pointRadius: 0, borderWidth: 1.5 },
    longitudinalG && { label: "Longitudinal (g)", data: longitudinalG, borderColor: "#c9800a", tension: 0.15, pointRadius: 0, borderWidth: 1.5 }
  ], signedYAxis);
  rpmChart = drawOptionalChart(rpmChart, "rpmChart", labels, [
    rpm && { label: "Engine speed (1/min)", data: rpm, borderColor: "#d0609a", tension: 0.1, pointRadius: 0, borderWidth: 2 }
  ]);
}

/**
 * A chart of channels the lap may lack: datasets left out (falsy) are dropped, and without any
 * the chart is removed and its box hidden, so files without the channel show no empty chart.
 */
function drawOptionalChart(chartRef, canvasId, labels, datasets, customize) {
  const canvas = document.getElementById(canvasId);
  const present = datasets.filter(Boolean);
  const wrap = canvas?.closest(".chart-wrap");
  if (wrap) {
    // Shown before drawing, so Chart.js measures the visible box.
    wrap.hidden = present.length === 0;
  }
  if (!present.length) {
    chartRef?.destroy();
    return undefined;
  }
  return drawOrUpdateChart(chartRef, canvasId, labels, present, true, customize);
}

async function uploadTelemetry() {
  const fileInput = document.getElementById("fileInput");
  const resultBox = document.getElementById("importResult");
  const uploadBtn = document.getElementById("uploadBtn");
  if (!fileInput.files.length) {
    resultBox.textContent = "Choose a file first.";
    return;
  }

  const file = fileInput.files[0];
  resultBox.textContent = `Uploading ${file.name} (${(file.size / 1024 / 1024).toFixed(2)} MiB)…`;
  if (uploadBtn) {
    uploadBtn.disabled = true;
  }

  try {
    const formData = new FormData();
    formData.append("file", file);

    const res = await fetch(`${apiBase}/telemetry/import`, { method: "POST", body: formData });
    const data = await parseJsonResponse(res);
    if (!res.ok) {
      resultBox.textContent = JSON.stringify(data ?? { error: res.status }, null, 2);
      return;
    }
    resultBox.textContent = JSON.stringify(data, null, 2);
    await loadSessions();
  } catch (err) {
    resultBox.textContent = `Upload failed: ${err.message}`;
    console.error(err);
  } finally {
    if (uploadBtn) {
      uploadBtn.disabled = false;
    }
  }
}

function sessionHasLapData(s) {
  return Number.isFinite(s?.lapCount) ? s.lapCount > 0 : (Array.isArray(s?.lapIds) && s.lapIds.length > 0);
}

async function loadSessions() {
  let sessions = null;
  try {
    const res = await fetch(`${apiBase}/sessions`);
    sessions = res.ok ? await parseJsonResponse(res) : null;
  } catch (err) {
    console.error("loadSessions failed", err);
  }
  if (!Array.isArray(sessions)) {
    setSessionStatus("Could not list the sessions: the server did not answer. Press Refresh to try again.", true);
    return;
  }
  setSessionStatus(sessions.length ? "" : "No sessions yet: upload a telemetry file above.");

  const fillSelect = (id, list) => {
    const select = document.getElementById(id);
    select.innerHTML = "";
    list.forEach((s) => {
      const opt = document.createElement("option");
      opt.value = s.sessionId;
      opt.textContent = `${s.sessionId} (${s.sourceFile}) · laps: ${s.lapCount ?? 0}`;
      select.appendChild(opt);
    });
  };

  fillSelect("sessionSelect", sessions);
  const comparable = sessions.filter(sessionHasLapData);
  fillSelect("refSession", comparable);
  fillSelect("cmpSession", comparable);
  await refreshCompareLapSelectors();
}

function buildChartOptions(showLapBoundaries, boundaries) {
  const options = {
    responsive: true,
    maintainAspectRatio: true,
    // A lap has ~20 000 samples at 200 Hz: animating every point made a lap switch take seconds.
    animation: false,
    interaction: { mode: "index", intersect: false },
    plugins: {
      legend: {
        display: true,
        labels: { color: "#e8edf4", font: { size: 12 } }
      }
    },
    scales: {
      x: {
        // Level labels, thinned out where they would touch: tilted ones took about a third of
        // the height of a phone-sized chart. Wide charts fit all 12 labels level anyway.
        ticks: { color: "#8b9cb3", maxTicksLimit: 12, maxRotation: 0, autoSkipPadding: 16 },
        grid: { color: "rgba(255,255,255,0.06)" }
      },
      y: {
        ticks: { color: "#8b9cb3" },
        grid: { color: "rgba(255,255,255,0.06)" }
      }
    }
  };
  if (showLapBoundaries) {
    options.plugins.lapBoundaries = { boundaries };
  }
  return options;
}

/** @param {(options: any) => void} [customize] adjusts the shared options of this chart */
function drawOrUpdateChart(chartRef, canvasId, labels, datasets, showLapBoundaries = true, customize) {
  const boundaries = showLapBoundaries ? (fullLapSnapshot?.lapBoundaries || []) : [];
  const options = buildChartOptions(showLapBoundaries, boundaries);
  customize?.(options);
  if (chartRef) {
    // A hover kept from the old data (a touch, or a mirrored one) may point past the new data.
    chartRef.setActiveElements([]);
    chartRef.tooltip?.setActiveElements([], { x: 0, y: 0 });
    chartRef.data.labels = labels;
    chartRef.data.datasets = datasets;
    chartRef.options = options;
    chartRef.update();
    return chartRef;
  }

  return new Chart(document.getElementById(canvasId), {
    type: "line",
    data: { labels, datasets },
    options
  });
}

/** Values of an optional channel, missing samples as null (gaps); null when no sample has it. */
function optionalChannel(points, key) {
  let present = false;
  const values = points.map((p) => {
    const v = p[key];
    if (typeof v === "number" && Number.isFinite(v)) {
      present = true;
      return v;
    }
    return null;
  });
  return present ? values : null;
}

function buildLapSnapshot(lap) {
  const points = Array.isArray(lap?.points) ? lap.points : [];
  const labels = points.map((p, idx) => (Number.isFinite(p.timestamp) ? p.timestamp : idx));
  const speed = points.map((p) => p.speed);
  const throttle = points.map((p) => p.throttle);
  const brake = points.map((p) => p.brake);
  const gear = points.map((p) => p.gear);
  // Absent from the JSON when the file has no such channel (the backend leaves nulls out).
  const steering = optionalChannel(points, "steeringAngle");
  const lateralG = optionalChannel(points, "lateralAcceleration");
  const longitudinalG = optionalChannel(points, "longitudinalAcceleration");
  const rpm = optionalChannel(points, "engineRpm");
  return { points, labels, speed, throttle, brake, gear, steering, lateralG, longitudinalG, rpm, lapBoundaries: [] };
}

function formatLapTime(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return "n/a";
  }
  const m = Math.floor(seconds / 60);
  const s = seconds - m * 60;
  return `${String(m).padStart(2, "0")}:${s.toFixed(3).padStart(6, "0")}`;
}

function computeLapDurationSeconds(lap) {
  const points = Array.isArray(lap?.points) ? lap.points : [];
  if (points.length < 2) {
    return NaN;
  }
  const t0 = Number(points[0]?.timestamp);
  const t1 = Number(points[points.length - 1]?.timestamp);
  if (Number.isFinite(t0) && Number.isFinite(t1) && t1 > t0) {
    return t1 - t0;
  }
  return NaN;
}

function updateLapToolbar() {
  const lapSelect = document.getElementById("lapSelect");
  const lapTimeInfo = document.getElementById("lapTimeInfo");
  const prevLapBtn = document.getElementById("prevLapBtn");
  const nextLapBtn = document.getElementById("nextLapBtn");
  if (!lapSelect || !lapTimeInfo || !prevLapBtn || !nextLapBtn) {
    return;
  }

  lapSelect.innerHTML = "";
  const overviewLaps = new Map((currentSessionOverview?.laps ?? []).map((l) => [l.lapId, l]));
  currentSessionLaps.forEach((lap, idx) => {
    const opt = document.createElement("option");
    opt.value = String(idx);
    const lapId = lap.lapId || `lap-${idx + 1}`;
    opt.textContent = `${lapId} (${idx + 1}/${currentSessionLaps.length})`
      + lapOptionSuffix(overviewLaps.get(lapId), currentSessionOverview?.fastestLapId, false);
    lapSelect.appendChild(opt);
  });

  if (currentSessionLaps.length === 0) {
    lapTimeInfo.textContent = "Lap time: n/a";
    prevLapBtn.disabled = true;
    nextLapBtn.disabled = true;
    return;
  }

  lapSelect.value = String(currentLapIndex);
  const lapDuration = computeLapDurationSeconds(currentSessionLaps[currentLapIndex]);
  lapTimeInfo.textContent = `Lap time: ${formatLapTime(lapDuration)}`;
  prevLapBtn.disabled = currentLapIndex <= 0;
  nextLapBtn.disabled = currentLapIndex >= currentSessionLaps.length - 1;
}

function summaryTile(label, value, detail, title) {
  const tile = document.createElement("div");
  tile.className = "summary-tile";
  if (title) {
    tile.title = title;
  }
  const k = document.createElement("span");
  k.className = "summary-label";
  k.textContent = label;
  const v = document.createElement("span");
  v.className = "summary-value";
  v.textContent = value;
  tile.append(k, v);
  if (detail) {
    const d = document.createElement("span");
    d.className = "summary-detail";
    d.textContent = detail;
    tile.append(d);
  }
  return tile;
}

/** Flying laps out of the complete ones, and the laps left out with their reason (speeds on hover). */
function flyingLapsTile(flying, complete, excluded, usedLaps) {
  const speed = (v) => (Number.isFinite(v) ? `${Math.round(v)} km/h` : "n/a");
  return summaryTile("Flying laps", `${flying} / ${complete}`,
    excluded.length ? `${excluded.map((e) => `${e.lapId} (${e.reason})`).join(", ")} left out` : "none left out",
    [...excluded.map((e) => `${e.lapId}: ${e.reason}, ${speed(e.entrySpeed)} at the start, ${speed(e.exitSpeed)} at the end`),
      usedLaps].filter(Boolean).join("\n"));
}

/**
 * Fastest lap, theoretical best lap and lap-time consistency of the loaded session (from
 * /overview). Parts the overview leaves null are left out; without any the box is hidden.
 */
function renderLapSummary(overview) {
  const box = document.getElementById("lapSummary");
  const tiles = document.getElementById("lapSummaryTiles");
  const help = document.getElementById("lapSummaryHelp");
  if (!box || !tiles || !help) {
    return;
  }
  tiles.innerHTML = "";
  const laps = Array.isArray(overview?.laps) ? overview.laps : [];
  const fastest = laps.find((l) => l.lapId === overview?.fastestLapId);
  const completeCount = laps.filter((l) => l.complete).length;
  const notes = [];
  if (laps.length) {
    tiles.append(summaryTile("Complete laps", `${completeCount} / ${laps.length}`));
  }
  if (fastest) {
    tiles.append(summaryTile("Fastest lap", formatLapTime(fastest.lapTime), fastest.lapId));
  }
  const best = overview?.theoreticalBest;
  if (best && Number.isFinite(best.lapTime) && best.lapTime > 0) {
    const sectors = Array.isArray(best.sectorTimes) ? best.sectorTimes : [];
    const gap = fastest ? `${formatSigned(best.lapTime - fastest.lapTime, 3)} s to fastest` : "";
    const perSector = sectors.map((t, i) => `S${i + 1} ${t.toFixed(3)} s (${best.sectorLapIds?.[i] ?? "?"})`).join("\n");
    tiles.append(summaryTile("Theoretical best", formatLapTime(best.lapTime), gap,
      `Best time of each sector, from the lap in brackets:\n${perSector}`));
    notes.push(`Theoretical best: the best time of each of the ${sectors.length || SECTOR_COUNT} equal-length sectors over the complete laps, added up.`);
  }
  const c = overview?.consistency;
  // Out-laps and in-laps left out of the consistency figures: in consistency, and at the top level
  // also when too few flying laps remain for consistency (older backends send neither).
  const excluded = Array.isArray(c?.excludedLaps) ? c.excludedLaps
    : Array.isArray(overview?.excludedLaps) ? overview.excludedLaps : null;
  const flyingLapRule = "Flying laps: the complete laps without out-laps and in-laps. A lap that starts below 0.6 × "
    + "the median start speed of the other complete laps is an out-lap (pit exit); one that ends below 0.6 × their "
    + "median end speed is an in-lap (pit entry). When a slow line crossing ends one lap and starts the next, both "
    + "are left out.";
  if (c && Number.isFinite(c.lapTimeStdDev)) {
    const lapKind = excluded ? "flying" : "complete";
    if (excluded) {
      const usedLaps = Array.isArray(c.lapIds) && c.lapIds.length ? `Figures use ${c.lapIds.join(", ")}.` : "";
      tiles.append(flyingLapsTile(c.lapCount, c.lapCount + excluded.length, excluded, usedLaps));
    }
    tiles.append(summaryTile("Std. deviation", `σ ${c.lapTimeStdDev.toFixed(3)} s`,
      `CV ${c.coefficientOfVariationPercent.toFixed(2)} % · ${c.lapCount} laps`,
      `Mean ${formatLapTime(c.meanLapTime)} · median ${formatLapTime(c.medianLapTime)}`));
    tiles.append(summaryTile("Spread", `${c.spread.toFixed(3)} s`, "slowest − fastest"));
    const k = c.leastConsistentSector;
    const sectorStd = Array.isArray(c.sectorStdDevs) ? c.sectorStdDevs[k - 1] : NaN;
    if (Number.isFinite(sectorStd)) {
      tiles.append(summaryTile("Least consistent", `S${k}`, `σ ${sectorStd.toFixed(3)} s`,
        c.sectorStdDevs.map((s, i) => `S${i + 1} σ ${s.toFixed(3)} s`).join("\n")));
    }
    if (excluded) {
      notes.push(flyingLapRule);
    }
    notes.push(`σ: sample standard deviation of the ${lapKind} lap times; CV: σ as a share of the mean lap time; `
      + `spread: slowest minus fastest ${lapKind} lap; least consistent: the sector whose time varies most from lap to lap.`);
  } else if (completeCount >= 2) {
    // The laps consistency is taken over: complete, with a lap time.
    const timed = laps.filter((l) => l.complete && l.lapTime > 0).length;
    if (excluded?.length && timed >= 2) {
      tiles.append(flyingLapsTile(Math.max(0, timed - excluded.length), timed, excluded, ""));
    }
    notes.push("Consistency figures need at least two flying laps: out-laps and in-laps (pit exit and entry) are left out.");
    if (excluded?.length) {
      notes.push(flyingLapRule);
    }
  } else if (laps.length) {
    notes.push("Consistency figures need at least two complete laps.");
  }
  help.textContent = notes.join(" ");
  help.hidden = notes.length === 0;
  box.hidden = tiles.childElementCount === 0;
}

function renderCurrentLap() {
  const lap = currentSessionLaps[currentLapIndex];
  if (!lap) {
    fullLapSnapshot = null;
    return;
  }
  fullLapSnapshot = buildLapSnapshot(lap);
  redrawFullLapCharts();
  updateLapToolbar();
  updateLapTrackMap();
}

async function loadSelectedSession() {
  const sessionId = document.getElementById("sessionSelect").value;
  if (!sessionId) {
    return;
  }

  const request = ++sessionRequest;
  setSessionStatus(`Loading ${sessionId}…`);
  lapTrackMap.showMessage("Loading session…", true);
  const [details, overview] = await Promise.allSettled([fetchSessionDetails(sessionId), fetchSessionOverview(sessionId)]);
  if (request !== sessionRequest) {
    return;
  }
  if (details.status === "rejected") {
    // Keep the lap that is still on screen, so the charts and the map agree.
    setSessionStatus(`Could not load ${sessionId}: ${sessionErrorText(details.reason)}`, true);
    updateLapTrackMap();
    return;
  }
  setSessionStatus("");
  const session = details.value;
  currentSessionId = sessionId;
  currentSessionOverview = overview.status === "fulfilled" ? overview.value : null;
  renderLapSummary(currentSessionOverview);
  currentSessionLaps = Array.isArray(session?.laps)
    ? session.laps.filter((lap) => Array.isArray(lap?.points) && lap.points.length > 0)
    : [];
  // Start on the fastest complete lap, as the desktop does.
  currentLapIndex = Math.max(0, currentSessionLaps.findIndex((lap) => lap.lapId === currentSessionOverview?.fastestLapId));
  if (!currentSessionLaps.length) {
    fullLapSnapshot = null;
    updateLapToolbar();
    updateLapTrackMap();
    return;
  }
  renderCurrentLap();
}

/**
 * Complete laps fastest first, then the partial ones, as the desktop lap lists; preselects the
 * rank-th fastest lap.
 */
function fillCompareLapSelect(select, overview, rank) {
  select.innerHTML = "";
  const laps = (overview?.laps ?? []).filter((l) => l.lapLength > 0);
  const complete = laps.filter((l) => l.complete && l.lapTime > 0).sort((a, b) => a.lapTime - b.lapTime);
  const ordered = [...complete, ...laps.filter((l) => !complete.includes(l))];
  for (const lap of ordered) {
    const opt = document.createElement("option");
    opt.value = lap.lapId;
    opt.textContent = lap.lapId + lapOptionSuffix(lap, overview.fastestLapId, true);
    select.appendChild(opt);
  }
  if (ordered.length) {
    select.value = ordered[Math.min(rank, ordered.length - 1)].lapId;
  }
}

/** Refills the reference ("ref") or compare ("cmp") lap list from its session's overview. */
async function refreshCompareLapSelect(side) {
  const sessionId = document.getElementById(side === "ref" ? "refSession" : "cmpSession")?.value;
  const select = document.getElementById(side === "ref" ? "refLapSelect" : "cmpLapSelect");
  if (!select) {
    return;
  }
  const request = ++compareLapRequests[side];
  if (!sessionId) {
    select.innerHTML = "";
    return;
  }
  // Same session on both sides: start the compare lap on the second fastest lap.
  const rank = side === "cmp" && sessionId === document.getElementById("refSession")?.value ? 1 : 0;
  let overview = null;
  try {
    overview = await fetchSessionOverview(sessionId);
  } catch (err) {
    document.getElementById("compareResult").textContent = `Could not list the laps of ${sessionId}: ${sessionErrorText(err)}`;
  }
  if (request === compareLapRequests[side]) {
    fillCompareLapSelect(select, overview, rank);
  }
}

async function refreshCompareLapSelectors() {
  await Promise.all([refreshCompareLapSelect("ref"), refreshCompareLapSelect("cmp")]);
}

function downsampleSeries(values, maxLen) {
  if (!Array.isArray(values) || values.length <= maxLen) {
    return values || [];
  }
  const step = Math.ceil(values.length / maxLen);
  const out = [];
  for (let i = 0; i < values.length; i += step) {
    out.push(values[i]);
  }
  return out;
}

/** Both speed traces over the reference lap distance; null clears the chart. */
function renderCompareSpeedChart(comparison) {
  if (!comparison) {
    compareChartDistance = [];
    if (compareSpeedChart) {
      compareSpeedChart.data.labels = [];
      compareSpeedChart.data.datasets = [];
      compareSpeedChart.update();
    }
    return;
  }
  compareChartDistance = downsampleSeries(comparison.distance, TRACK_CHART_MAX_POINTS);
  const labels = compareChartDistance.map((d) => `${Math.round(d)} m`);
  const refSeries = downsampleSeries(comparison.referenceSpeed, TRACK_CHART_MAX_POINTS);
  const cmpSeries = downsampleSeries(comparison.compareSpeed, TRACK_CHART_MAX_POINTS);
  compareSpeedChart = drawOrUpdateChart(compareSpeedChart, "compareSpeedChart", labels, [
    {
      label: `Reference speed (${comparison.referenceLapId})`,
      data: refSeries,
      borderColor: "#ff3d2e",
      backgroundColor: "rgba(255,61,46,0.12)",
      fill: false,
      tension: 0.15,
      pointRadius: 0,
      borderWidth: 2
    },
    {
      label: `Compare speed (${comparison.compareLapId})`,
      data: cmpSeries,
      borderColor: "#22d3ee",
      backgroundColor: "rgba(34,211,238,0.10)",
      fill: false,
      tension: 0.15,
      pointRadius: 0,
      borderWidth: 2
    }
  ], false, (options) => {
    // Sized by its .chart-box (2:1, at least 240 px high), so the legend leaves room on phones.
    options.maintainAspectRatio = false;
  });
}

/**
 * Cumulative time delta (compare minus reference, s) on the same distance points as the speed
 * overlay, red above zero (compare behind) and green below (ahead); null clears the chart.
 */
function renderCompareDeltaChart(comparison) {
  if (!comparison) {
    if (compareDeltaChart) {
      compareDeltaChart.data.labels = [];
      compareDeltaChart.data.datasets = [];
      compareDeltaChart.update();
    }
    return;
  }
  const labels = compareChartDistance.map((d) => `${Math.round(d)} m`);
  compareDeltaChart = drawOrUpdateChart(compareDeltaChart, "compareDeltaChart", labels, [
    {
      label: `Δt (${comparison.compareLapId} − ${comparison.referenceLapId})`,
      data: downsampleSeries(comparison.deltaTime, TRACK_CHART_MAX_POINTS),
      borderColor: "#cbd5e1",
      fill: { target: "origin", above: "rgba(239,68,68,0.32)", below: "rgba(34,197,94,0.32)" },
      tension: 0,
      pointRadius: 0,
      borderWidth: 1.5
    }
  ], false, (options) => {
    // Fixed height from CSS (.chart-box): an aspect ratio would make it too flat on phones.
    options.maintainAspectRatio = false;
    options.plugins.legend.display = false;
    options.plugins.tooltip = {
      callbacks: {
        label: (item) => {
          const v = Number(item.parsed.y.toFixed(3));
          const side = v > 0 ? "compare behind" : v < 0 ? "compare ahead" : "level";
          // Same number format as the speed overlay's tooltip shown next to it (the chart locale).
          const text = new Intl.NumberFormat(item.chart.options.locale,
            { minimumFractionDigits: 3, maximumFractionDigits: 3, signDisplay: "exceptZero" }).format(v);
          return `Δt ${text} s (${side})`;
        }
      }
    };
    signedYAxis(options);
    options.scales.y.title = { display: true, text: "Δt (s)", color: "#8b9cb3" };
  });
}

/**
 * Shows the hover tooltip of a chart at a data index, as if the pointer were there, for charts
 * that share their x axis with the hovered one; -1 clears it. Redraws once per frame.
 */
function mirrorChartHover(chart, index) {
  if (!chart) {
    return;
  }
  const pending = mirroredHover.has(chart);
  mirroredHover.set(chart, index);
  if (pending) {
    return;
  }
  requestAnimationFrame(() => {
    if (!mirroredHover.has(chart)) {
      return; // cancelled by a pointer event on the chart itself (cursorLinkPlugin)
    }
    const i = mirroredHover.get(chart);
    mirroredHover.delete(chart);
    const n = chart.data.labels?.length ?? 0;
    const active = i >= 0 && i < n ? chart.data.datasets.map((_, datasetIndex) => ({ datasetIndex, index: i })) : [];
    const current = chart.getActiveElements();
    if (current.length === active.length && current.every((e, k) => e.index === active[k].index)) {
      return;
    }
    chart.setActiveElements(active);
    chart.tooltip?.setActiveElements(active, { x: 0, y: 0 });
    chart.update("none");
  });
}

/** Text summary of a distance-aligned comparison. */
function compareSummary(comparison) {
  const { distance, referenceSpeed, compareSpeed } = comparison;
  const n = distance.length;
  let sum = 0;
  for (let k = 0; k < n; k++) {
    sum += compareSpeed[k] - referenceSpeed[k];
  }
  // The grid is evenly spaced in distance, so this is the distance-weighted mean.
  const avgDelta = n ? sum / n : NaN;
  const timeDelta = comparison.referenceLapTime - comparison.compareLapTime;
  const direction = Number.isFinite(avgDelta)
    ? (avgDelta > 0 ? "Compare lap is faster on average." : (avgDelta < 0 ? "Compare lap is slower on average." : "Average speeds are effectively equal."))
    : "Average speed delta is not available.";
  const referenceTimeSummary = Number.isFinite(timeDelta)
    ? (timeDelta > 0
      ? `Reference lap was ${timeDelta.toFixed(3)}s slower.`
      : (timeDelta < 0
        ? `Reference lap was ${Math.abs(timeDelta).toFixed(3)}s faster.`
        : "Reference and compare lap times are equal."))
    : "Lap time delta is not available.";
  return `Reference lap: ${comparison.referenceLapId} (${formatLapTime(comparison.referenceLapTime)})\n` +
    `Compare lap: ${comparison.compareLapId} (${formatLapTime(comparison.compareLapTime)})\n` +
    `Average speed delta: ${Number.isFinite(avgDelta) ? avgDelta.toFixed(3) : "n/a"} km/h\n` +
    `Compared points: ${n} over ${n ? Math.round(distance[n - 1]) : 0} m of the reference lap\n` +
    `${direction}\n` +
    `${referenceTimeSummary}`;
}

/** Distance-aligned comparison: delta-coloured map, sector deltas, speed overlay and summary. */
async function compareSessions() {
  const ref = document.getElementById("refSession").value;
  const cmp = document.getElementById("cmpSession").value;
  const refLapId = document.getElementById("refLapSelect")?.value;
  const cmpLapId = document.getElementById("cmpLapSelect")?.value;
  const out = document.getElementById("compareResult");

  if (!ref || !cmp) {
    out.textContent = "Select both sessions.";
    return;
  }
  if (!refLapId || !cmpLapId) {
    out.textContent = "Select a lap in both sessions.";
    return;
  }

  const request = ++compareRequest;
  out.textContent = "Comparing…";
  compareTrackMap.showMessage("Loading track map…", true);
  renderSectorStrip(null);
  const [track, aligned] = await Promise.allSettled([
    fetchLapTrack(ref, refLapId),
    fetchAlignedComparison(ref, refLapId, cmp, cmpLapId)
  ]);
  if (request !== compareRequest) {
    return;
  }
  if (aligned.status === "rejected") {
    const message = `Cannot align the laps: ${aligned.reason.message}`;
    compareTrackMap.showMessage(message);
    renderCompareSpeedChart(null);
    renderCompareDeltaChart(null);
    out.textContent = message;
    return;
  }
  const { comparison, sectors, finalDelta } = aligned.value;
  renderSectorStrip(sectors, finalDelta);
  renderCompareSpeedChart(comparison);
  renderCompareDeltaChart(comparison);
  out.textContent = compareSummary(comparison);
  if (track.status === "rejected") {
    compareTrackMap.showMessage(`Could not load the track map: ${track.reason.message}`);
    return;
  }
  drawCompareTrackMap(track.value, comparison, sectors);
}

document.getElementById("uploadBtn").addEventListener("click", uploadTelemetry);
document.getElementById("refreshSessions").addEventListener("click", loadSessions);
document.getElementById("loadSession").addEventListener("click", loadSelectedSession);
document.getElementById("runCompare").addEventListener("click", compareSessions);
const refSessionSelect = document.getElementById("refSession");
if (refSessionSelect) {
  refSessionSelect.addEventListener("change", () => refreshCompareLapSelect("ref"));
}
const cmpSessionSelect = document.getElementById("cmpSession");
if (cmpSessionSelect) {
  cmpSessionSelect.addEventListener("change", () => refreshCompareLapSelect("cmp"));
}
const lapSelectEl = document.getElementById("lapSelect");
if (lapSelectEl) {
  lapSelectEl.addEventListener("change", (event) => {
    const idx = Number(event.target.value);
    if (!Number.isInteger(idx) || idx < 0 || idx >= currentSessionLaps.length) {
      return;
    }
    currentLapIndex = idx;
    renderCurrentLap();
  });
}
const prevLapBtn = document.getElementById("prevLapBtn");
if (prevLapBtn) {
  prevLapBtn.addEventListener("click", () => {
    if (currentLapIndex <= 0) {
      return;
    }
    currentLapIndex -= 1;
    renderCurrentLap();
  });
}
const nextLapBtn = document.getElementById("nextLapBtn");
if (nextLapBtn) {
  nextLapBtn.addEventListener("click", () => {
    if (currentLapIndex >= currentSessionLaps.length - 1) {
      return;
    }
    currentLapIndex += 1;
    renderCurrentLap();
  });
}
/**
 * Where the pinned track map of a narrow layout lets go (styles.css): the height from the top of
 * the last visible chart, less the gap above it, to the end of the chart column. Charts appear,
 * disappear and change height with the window, so it is measured again on every resize.
 */
function updateMapRelease(layout) {
  const column = layout.querySelector(":scope > .chart-grid, :scope > .chart-stack");
  const charts = column ? Array.from(column.children).filter((el) => el.offsetHeight > 0) : [];
  const last = charts[charts.length - 1];
  if (!last) {
    layout.style.removeProperty("--map-release");
    return;
  }
  const gap = parseFloat(getComputedStyle(layout).rowGap) || 0;
  const release = column.getBoundingClientRect().bottom - last.getBoundingClientRect().top + gap;
  layout.style.setProperty("--map-release", `${Math.max(0, Math.round(release))}px`);
}

if (typeof ResizeObserver === "function") {
  for (const layout of document.querySelectorAll(".map-layout")) {
    // The chart column and its charts, not the layout: the release padding and margin change the
    // layout's boxes but never these, so updating them cannot trigger the observer again.
    const column = layout.querySelector(":scope > .chart-grid, :scope > .chart-stack");
    if (!column) {
      continue;
    }
    const observer = new ResizeObserver(() => updateMapRelease(layout));
    for (const el of [column, ...column.children]) {
      observer.observe(el, { box: "border-box" });
    }
  }
}

// The lap outline has one sample per lap sample; the compare charts run on the reference distance.
for (const id of ["speedChart", "throttleChart", "brakeChart", "gearChart", "steeringChart", "accelChart", "rpmChart"]) {
  linkChartToMap(id, (i) => lapTrackMap.setCursorIndex(i));
}
// The speed overlay and the time delta share their distance points: hovering one also shows the other.
linkChartToMap("compareSpeedChart", (i) => {
  compareTrackMap.setCursorDistance(i >= 0 ? compareChartDistance[i] : NaN);
  mirrorChartHover(compareDeltaChart, i);
});
linkChartToMap("compareDeltaChart", (i) => {
  compareTrackMap.setCursorDistance(i >= 0 ? compareChartDistance[i] : NaN);
  mirrorChartHover(compareSpeedChart, i);
});

loadSessions();
updateLapToolbar();
