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
let compareSpeedChart;

/** @type {{ points: any[], labels: any[], speed: number[], throttle: number[], brake: number[], gear: number[], lapBoundaries?: any[] } | null} */
let fullLapSnapshot = null;
/** @type {string | null} */
let currentSessionId = null;
/** @type {any[]} */
let currentSessionLaps = [];
let currentLapIndex = 0;
const sessionDetailsCache = new Map();
// Outlines, events and aligned comparisons of an imported session never change: fetch each once.
const lapTrackCache = new Map();
const lapEventsCache = new Map();
const alignedCompareCache = new Map();

const lapTrackMap = new TrackMap(document.getElementById("lapTrackMap"), "Load a session to draw the track map.");
const compareTrackMap = new TrackMap(document.getElementById("compareTrackMap"), "Compare two laps to colour the track by time delta.");
/** Incremented per map update so a slow response cannot overwrite a newer selection. */
let lapMapRequest = 0;
let compareMapRequest = 0;
/** Reference samples per point of the downsampled compare chart (chart index to outline index). */
let compareChartStep = 1;

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

/** Fetches JSON once per key; a failed request is dropped from the cache so it can be retried. */
function fetchJsonCached(cache, key, url) {
  if (!cache.has(key)) {
    const request = fetch(url).then(async (res) => {
      const data = await parseJsonResponse(res);
      if (!res.ok) {
        throw new Error(data?.error || `HTTP ${res.status}`);
      }
      return data;
    });
    request.catch(() => cache.delete(key));
    cache.set(key, request);
  }
  return cache.get(key);
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
async function updateCompareTrackMap(refSessionId, refLapId, cmpSessionId, cmpLapId) {
  const request = ++compareMapRequest;
  compareTrackMap.showMessage("Loading track map…", true);
  renderSectorStrip(null);
  const [track, aligned] = await Promise.allSettled([
    fetchLapTrack(refSessionId, refLapId),
    fetchAlignedComparison(refSessionId, refLapId, cmpSessionId, cmpLapId)
  ]);
  if (request !== compareMapRequest) {
    return;
  }
  if (aligned.status === "rejected") {
    compareTrackMap.showMessage(`Cannot align the laps: ${aligned.reason.message}`);
    return;
  }
  const { comparison, sectors, finalDelta } = aligned.value;
  renderSectorStrip(sectors, finalDelta);
  if (track.status === "rejected") {
    compareTrackMap.showMessage(`Could not load the track map: ${track.reason.message}`);
    return;
  }
  const outline = track.value;
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

/** Follows the pointer over a chart with the map cursor; toSample maps a chart index to an outline sample. */
function linkChartToMap(canvasId, map, toSample) {
  const canvas = document.getElementById(canvasId);
  if (!canvas) {
    return;
  }
  const follow = (event) => {
    const chart = Chart.getChart(canvas);
    if (!chart) {
      return;
    }
    const hit = chart.getElementsAtEventForMode(event, "index", { intersect: false }, false);
    map.setCursorIndex(hit.length ? toSample(hit[0].index) : -1);
  };
  canvas.addEventListener("pointermove", follow);
  canvas.addEventListener("pointerdown", follow);
  canvas.addEventListener("pointerleave", (event) => {
    if (event.pointerType !== "touch") {
      map.setCursorIndex(-1);
    }
  });
}

function redrawFullLapCharts() {
  if (!fullLapSnapshot) {
    return;
  }
  const { labels, speed, throttle, brake, gear } = fullLapSnapshot;
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
  const res = await fetch(`${apiBase}/sessions`);
  const sessions = await parseJsonResponse(res);
  if (!res.ok || !Array.isArray(sessions)) {
    console.error("loadSessions failed", res.status, sessions);
    return;
  }

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
  if (showLapBoundaries) {
    options.plugins.lapBoundaries = { boundaries };
  }
  return options;
}

function drawOrUpdateChart(chartRef, canvasId, labels, datasets, showLapBoundaries = true) {
  const boundaries = showLapBoundaries ? (fullLapSnapshot?.lapBoundaries || []) : [];
  if (chartRef) {
    chartRef.data.labels = labels;
    chartRef.data.datasets = datasets;
    chartRef.options = buildChartOptions(showLapBoundaries, boundaries);
    chartRef.update();
    return chartRef;
  }

  return new Chart(document.getElementById(canvasId), {
    type: "line",
    data: { labels, datasets },
    options: buildChartOptions(showLapBoundaries, boundaries)
  });
}

function buildLapSnapshot(lap) {
  const points = Array.isArray(lap?.points) ? lap.points : [];
  const labels = points.map((p, idx) => (Number.isFinite(p.timestamp) ? p.timestamp : idx));
  const speed = points.map((p) => p.speed);
  const throttle = points.map((p) => p.throttle);
  const brake = points.map((p) => p.brake);
  const gear = points.map((p) => p.gear);
  return { points, labels, speed, throttle, brake, gear, lapBoundaries: [] };
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
  currentSessionLaps.forEach((lap, idx) => {
    const opt = document.createElement("option");
    opt.value = String(idx);
    const lapId = lap.lapId || `lap-${idx + 1}`;
    opt.textContent = `${lapId} (${idx + 1}/${currentSessionLaps.length})`;
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

  lapTrackMap.showMessage("Loading session…", true);
  const session = await getSessionDetails(sessionId);
  if (!session) {
    lapTrackMap.showMessage("Could not load the session.");
    return;
  }
  currentSessionId = sessionId;
  currentSessionLaps = Array.isArray(session?.laps)
    ? session.laps.filter((lap) => Array.isArray(lap?.points) && lap.points.length > 0)
    : [];
  currentLapIndex = 0;
  if (!currentSessionLaps.length) {
    fullLapSnapshot = null;
    updateLapToolbar();
    updateLapTrackMap();
    return;
  }
  renderCurrentLap();
}

async function getSessionDetails(sessionId) {
  if (!sessionId) {
    return null;
  }
  if (sessionDetailsCache.has(sessionId)) {
    return sessionDetailsCache.get(sessionId);
  }
  const res = await fetch(`${apiBase}/sessions/${encodeURIComponent(sessionId)}`);
  const session = await parseJsonResponse(res);
  if (!res.ok || !session) {
    return null;
  }
  sessionDetailsCache.set(sessionId, session);
  return session;
}

function fillLapSelect(selectId, laps) {
  const select = document.getElementById(selectId);
  if (!select) {
    return;
  }
  select.innerHTML = "";
  laps.forEach((lap, idx) => {
    const lapId = lap?.lapId || `lap-${idx + 1}`;
    const opt = document.createElement("option");
    opt.value = lapId;
    opt.textContent = lapId;
    select.appendChild(opt);
  });
}

async function refreshCompareLapSelectors() {
  const refSessionId = document.getElementById("refSession")?.value;
  const cmpSessionId = document.getElementById("cmpSession")?.value;

  const refSession = await getSessionDetails(refSessionId);
  const cmpSession = await getSessionDetails(cmpSessionId);
  const refLaps = Array.isArray(refSession?.laps) ? refSession.laps.filter((l) => Array.isArray(l?.points) && l.points.length > 0) : [];
  const cmpLaps = Array.isArray(cmpSession?.laps) ? cmpSession.laps.filter((l) => Array.isArray(l?.points) && l.points.length > 0) : [];
  fillLapSelect("refLapSelect", refLaps);
  fillLapSelect("cmpLapSelect", cmpLaps);
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

function renderCompareSpeedChart(refLap, cmpLap) {
  const refSpeed = downsampleSeries(refLap.points.map((p) => Number(p.speed) || 0), TRACK_CHART_MAX_POINTS);
  const cmpSpeed = downsampleSeries(cmpLap.points.map((p) => Number(p.speed) || 0), TRACK_CHART_MAX_POINTS);
  compareChartStep = Math.max(1, Math.ceil(refLap.points.length / TRACK_CHART_MAX_POINTS));
  const n = Math.min(refSpeed.length, cmpSpeed.length);
  const labels = Array.from({ length: n }, (_, i) => String(i));
  const refSeries = refSpeed.slice(0, n);
  const cmpSeries = cmpSpeed.slice(0, n);
  compareSpeedChart = drawOrUpdateChart(compareSpeedChart, "compareSpeedChart", labels, [
    {
      label: `Reference speed (${refLap.lapId})`,
      data: refSeries,
      borderColor: "#ff3d2e",
      backgroundColor: "rgba(255,61,46,0.12)",
      fill: false,
      tension: 0.15,
      pointRadius: 0,
      borderWidth: 2
    },
    {
      label: `Compare speed (${cmpLap.lapId})`,
      data: cmpSeries,
      borderColor: "#22d3ee",
      backgroundColor: "rgba(34,211,238,0.10)",
      fill: false,
      tension: 0.15,
      pointRadius: 0,
      borderWidth: 2
    }
  ], false);
}

async function compareSessions() {
  const ref = document.getElementById("refSession").value;
  const cmp = document.getElementById("cmpSession").value;
  const refLapId = document.getElementById("refLapSelect")?.value || "lap-1";
  const cmpLapId = document.getElementById("cmpLapSelect")?.value || "lap-1";
  const out = document.getElementById("compareResult");

  if (!ref || !cmp) {
    out.textContent = "Select both sessions.";
    return;
  }

  const refSession = await getSessionDetails(ref);
  const cmpSession = await getSessionDetails(cmp);
  const refLap = Array.isArray(refSession?.laps) ? refSession.laps.find((lap) => lap.lapId === refLapId) : null;
  const cmpLap = Array.isArray(cmpSession?.laps) ? cmpSession.laps.find((lap) => lap.lapId === cmpLapId) : null;
  if (!refLap || !cmpLap) {
    out.textContent = "Selected lap not found in one or both sessions.";
    return;
  }
  updateCompareTrackMap(ref, refLapId, cmp, cmpLapId);

  const res = await fetch(
    `${apiBase}/compare?referenceSessionId=${encodeURIComponent(ref)}&compareSessionId=${encodeURIComponent(cmp)}&referenceLapId=${encodeURIComponent(refLapId)}&compareLapId=${encodeURIComponent(cmpLapId)}`
  );
  const data = await parseJsonResponse(res);
  if (!res.ok) {
    out.textContent = JSON.stringify(data ?? { error: res.status }, null, 2);
    return;
  }
  renderCompareSpeedChart(refLap, cmpLap);
  const avgDelta = Number(data?.delta?.avgSpeedDelta);
  const comparedSamples = Number(data?.delta?.comparedSamples);
  const refDuration = computeLapDurationSeconds(refLap);
  const cmpDuration = computeLapDurationSeconds(cmpLap);
  const timeDelta = Number.isFinite(refDuration) && Number.isFinite(cmpDuration)
    ? refDuration - cmpDuration
    : NaN;
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
  out.textContent =
    `Reference lap: ${data?.referenceLapId || refLapId}\n` +
    `Compare lap: ${data?.compareLapId || cmpLapId}\n` +
    `Average speed delta: ${Number.isFinite(avgDelta) ? avgDelta.toFixed(3) : "n/a"} km/h\n` +
    `Compared samples: ${Number.isFinite(comparedSamples) ? comparedSamples : "n/a"}\n` +
    `${direction}\n` +
    `${referenceTimeSummary}`;
}

document.getElementById("uploadBtn").addEventListener("click", uploadTelemetry);
document.getElementById("refreshSessions").addEventListener("click", loadSessions);
document.getElementById("loadSession").addEventListener("click", loadSelectedSession);
document.getElementById("runCompare").addEventListener("click", compareSessions);
const refSessionSelect = document.getElementById("refSession");
if (refSessionSelect) {
  refSessionSelect.addEventListener("change", refreshCompareLapSelectors);
}
const cmpSessionSelect = document.getElementById("cmpSession");
if (cmpSessionSelect) {
  cmpSessionSelect.addEventListener("change", refreshCompareLapSelectors);
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
for (const id of ["speedChart", "throttleChart", "brakeChart", "gearChart"]) {
  linkChartToMap(id, lapTrackMap, (i) => i);
}
linkChartToMap("compareSpeedChart", compareTrackMap, (i) => i * compareChartStep);

loadSessions();
updateLapToolbar();
