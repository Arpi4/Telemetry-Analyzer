// Local: Spring Boot. Deployed: Render (CORS on backend). Set RENDER_API_BASE for production.
const RENDER_API_BASE = "https://telemetry-analyzer.onrender.com/api";
const apiBase =
  window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1"
    ? "http://localhost:8080/api"
    : RENDER_API_BASE;

const TRACK_CHART_MAX_POINTS = 12_000;
const DEFAULT_SEGMENT_COUNT = 10;

let speedChart;
let throttleChart;
let brakeChart;
let gearChart;
let trackMapChart;
let compareSpeedChart;

/** @type {{ points: any[], labels: any[], speed: number[], throttle: number[], brake: number[], gear: number[], lapBoundaries?: any[] } | null} */
let fullLapSnapshot = null;
/** @type {any[]} */
let currentSessionLaps = [];
let currentLapIndex = 0;
/** @type {any[] | null} */
let segmentMetaList = null;
let selectedSegmentIndex = null;
/** "neutral" = equal slices only; "compare" = coloured vs reference lap */
let segmentDisplayMode = "neutral";
const sessionDetailsCache = new Map();

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

function downsamplePoints(arr, maxN) {
  if (arr.length <= maxN) {
    return arr;
  }
  const step = Math.ceil(arr.length / maxN);
  const out = [];
  for (let i = 0; i < arr.length; i += step) {
    out.push(arr[i]);
  }
  return out;
}

function isPlausibleGps(lat, lon) {
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return false;
  }
  if (Math.abs(lat) > 90 || Math.abs(lon) > 180) {
    return false;
  }
  if (Math.abs(lat) + Math.abs(lon) < 0.0005) {
    return false;
  }
  return true;
}

function destroyTrackMapChart() {
  if (trackMapChart) {
    trackMapChart.destroy();
    trackMapChart = null;
  }
}

function drawTrackScatter(points, xTitle, yTitle, datasetLabel) {
  destroyTrackMapChart();
  const dataset = [{
    label: datasetLabel,
    data: points,
    showLine: true,
    borderColor: "#ff3d2e",
    backgroundColor: "rgba(255,61,46,0.45)",
    pointRadius: 1,
    borderWidth: 2
  }];

  trackMapChart = new Chart(document.getElementById("trackMapChart"), {
    type: "scatter",
    data: { datasets: dataset },
    options: {
      responsive: true,
      maintainAspectRatio: true,
      plugins: {
        legend: { labels: { color: "#e8edf4" } }
      },
      scales: {
        x: {
          type: "linear",
          title: { display: true, text: xTitle, color: "#8b9cb3" },
          ticks: { color: "#8b9cb3" },
          grid: { color: "rgba(255,255,255,0.06)" }
        },
        y: {
          type: "linear",
          title: { display: true, text: yTitle, color: "#8b9cb3" },
          ticks: { color: "#8b9cb3" },
          grid: { color: "rgba(255,255,255,0.06)" }
        }
      }
    }
  });
  return trackMapChart;
}

function closedPolylineLength(points) {
  const n = points.length;
  if (n < 2) {
    return 0;
  }
  let L = 0;
  for (let i = 0; i < n; i++) {
    const j = (i + 1) % n;
    L += Math.hypot(points[j][0] - points[i][0], points[j][1] - points[i][1]);
  }
  return L;
}

function pointOnClosedPolyline(points, t) {
  const n = points.length;
  if (n < 2) {
    return [50, 50];
  }
  let tt = ((t % 1) + 1) % 1;
  const total = closedPolylineLength(points);
  let dist = tt * total;
  for (let i = 0; i < n; i++) {
    const j = (i + 1) % n;
    const len = Math.hypot(points[j][0] - points[i][0], points[j][1] - points[i][1]);
    if (dist <= len + 1e-9) {
      const f = len > 0 ? dist / len : 0;
      return [
        points[i][0] + f * (points[j][0] - points[i][0]),
        points[i][1] + f * (points[j][1] - points[i][1])
      ];
    }
    dist -= len;
  }
  return points[0];
}

function pathDFromClosedPolyline(points) {
  if (!points.length) {
    return "";
  }
  const [x0, y0] = points[0];
  let d = `M ${x0} ${y0}`;
  for (let i = 1; i < points.length; i++) {
    d += ` L ${points[i][0]} ${points[i][1]}`;
  }
  d += " Z";
  return d;
}

function buildNeutralSegments(count) {
  const out = [];
  for (let k = 0; k < count; k++) {
    const p0 = k / count;
    const p1 = (k + 1) / count;
    out.push({
      index: k + 1,
      progressStart: p0,
      progressEnd: p1,
      distanceStartM: null,
      distanceEndM: null,
      referenceAvgSpeedKmh: 0,
      compareAvgSpeedKmh: 0,
      deltaSeconds: 0,
      distanceBased: false
    });
  }
  return out;
}

function markerClassForDelta(deltaSeconds, hasCompare) {
  if (!hasCompare) {
    return "neutral";
  }
  if (deltaSeconds > 0.015) {
    return "slow";
  }
  if (deltaSeconds < -0.015) {
    return "fast";
  }
  return "neutral";
}

function formatDeltaLabel(deltaSeconds, hasCompare) {
  if (!hasCompare) {
    return String("");
  }
  const s = deltaSeconds >= 0 ? `+${deltaSeconds.toFixed(3)}` : deltaSeconds.toFixed(3);
  return s;
}

function renderSchematicSegments(segments, hasCompare) {
  const outline = window.TA_MONZA_OUTLINE || [[50, 50]];
  const wrap = document.getElementById("trackSchematicWrap");
  const pathEl = document.getElementById("trackSchematicPath");
  const g = document.getElementById("trackSegmentMarkers");
  const legend = document.getElementById("trackSegmentLegend");
  if (!wrap || !pathEl || !g) {
    return;
  }

  pathEl.setAttribute("d", pathDFromClosedPolyline(outline));
  g.innerHTML = "";

  segments.forEach((seg, idx) => {
    const tMid = (seg.progressStart + seg.progressEnd) / 2;
    const [cx, cy] = pointOnClosedPolyline(outline, tMid);
    const mclass = markerClassForDelta(seg.deltaSeconds, hasCompare);
    const label = formatDeltaLabel(seg.deltaSeconds, hasCompare);

    const hit = document.createElementNS("http://www.w3.org/2000/svg", "circle");
    hit.setAttribute("class", "track-segment-hit");
    hit.setAttribute("cx", String(cx));
    hit.setAttribute("cy", String(cy));
    hit.setAttribute("r", "6");
    hit.dataset.segmentIndex = String(idx);

    const vis = document.createElementNS("http://www.w3.org/2000/svg", "circle");
    vis.setAttribute("class", `track-segment-marker ${mclass}${selectedSegmentIndex === idx ? " selected" : ""}`);
    vis.setAttribute("cx", String(cx));
    vis.setAttribute("cy", String(cy));
    vis.setAttribute("r", "3.2");

    const num = document.createElementNS("http://www.w3.org/2000/svg", "text");
    num.setAttribute("class", "track-segment-label");
    num.setAttribute("x", String(cx + 5));
    num.setAttribute("y", String(cy - 4));
    num.textContent = String(seg.index);

    g.appendChild(hit);
    g.appendChild(vis);
    g.appendChild(num);

    if (label) {
      const tx = document.createElementNS("http://www.w3.org/2000/svg", "text");
      tx.setAttribute("class", "track-segment-label");
      tx.setAttribute("x", String(cx + 5));
      tx.setAttribute("y", String(cy + 3.5));
      tx.setAttribute("fill", mclass === "slow" ? "#fdba74" : mclass === "fast" ? "#86efac" : "#9ca3af");
      tx.textContent = label;
      g.appendChild(tx);
    }

    hit.addEventListener("click", () => {
      selectedSegmentIndex = idx;
      applySegmentFocus(segments[idx]);
      renderSchematicSegments(segments, hasCompare);
    });
  });

  if (legend) {
    legend.textContent = hasCompare
      ? "Schematic track: orange ≈ slower than reference in that segment, green ≈ faster, grey ≈ matched. Click a marker to show only that slice in the charts above."
      : "Schematic track: equal lap slices (by distance if available, else by samples). Click a marker to zoom charts to that part of the lap. Load segment deltas after choosing two sessions to colour markers vs reference.";
  }
}

function hideSchematic() {
  const wrap = document.getElementById("trackSchematicWrap");
  if (wrap) {
    wrap.hidden = true;
  }
  segmentMetaList = null;
  selectedSegmentIndex = null;
  segmentDisplayMode = "neutral";
}

function showSchematicWithSegments(segments, hasCompare) {
  const wrap = document.getElementById("trackSchematicWrap");
  if (wrap) {
    wrap.hidden = false;
  }
  segmentMetaList = segments;
  segmentDisplayMode = hasCompare ? "compare" : "neutral";
  renderSchematicSegments(segments, hasCompare);
}

function slicePointsForSegment(points, seg) {
  const hasDist = points.some((p) => Number.isFinite(p.distanceMeters));
  if (hasDist && seg.distanceStartM != null && seg.distanceEndM != null) {
    const d0 = seg.distanceStartM;
    const d1 = seg.distanceEndM;
    const slice = points.filter((p) => {
      const d = p.distanceMeters;
      return Number.isFinite(d) && d >= d0 && d <= d1 + 1e-6;
    });
    if (slice.length > 1) {
      return slice;
    }
  }
  const n = points.length;
  const i0 = Math.min(n - 1, Math.floor(n * seg.progressStart));
  let i1 = Math.max(i0 + 1, Math.ceil(n * seg.progressEnd));
  if (i1 > n) {
    i1 = n;
  }
  return points.slice(i0, i1);
}

function applySegmentFocus(seg) {
  if (!fullLapSnapshot || !seg) {
    return;
  }
  const slice = slicePointsForSegment(fullLapSnapshot.points, seg);
  if (slice.length < 2) {
    return;
  }
  const labels = slice.map((p) => p.timestamp);
  const speed = slice.map((p) => p.speed);
  const throttle = slice.map((p) => p.throttle);
  const brake = slice.map((p) => p.brake);
  const gear = slice.map((p) => p.gear);
  speedChart = drawOrUpdateChart(speedChart, "speedChart", labels, [
    { label: `Speed (km/h) · segment ${seg.index}`, data: speed, borderColor: "#ff3d2e", backgroundColor: "rgba(255,61,46,0.12)", fill: false, tension: 0.15, pointRadius: 0, borderWidth: 2 }
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
  selectedSegmentIndex = null;
  if (segmentMetaList) {
    const hasCompare = segmentDisplayMode === "compare";
    renderSchematicSegments(segmentMetaList, hasCompare);
  }
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

function drawOrUpdateChart(chartRef, canvasId, labels, datasets) {
  const boundaries = fullLapSnapshot?.lapBoundaries || [];
  if (chartRef) {
    chartRef.data.labels = labels;
    chartRef.data.datasets = datasets;
    chartRef.options.plugins = chartRef.options.plugins || {};
    chartRef.options.plugins.lapBoundaries = { boundaries };
    chartRef.update();
    return chartRef;
  }

  return new Chart(document.getElementById(canvasId), {
    type: "line",
    data: { labels, datasets },
    options: {
      ...chartTheme,
      plugins: {
        ...chartTheme.plugins,
        lapBoundaries: { boundaries }
      }
    }
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
}

async function loadSelectedSession() {
  const sessionId = document.getElementById("sessionSelect").value;
  if (!sessionId) {
    return;
  }

  const res = await fetch(`${apiBase}/sessions/${sessionId}`);
  const session = await parseJsonResponse(res);
  if (!res.ok) {
    return;
  }
  currentSessionLaps = Array.isArray(session?.laps)
    ? session.laps.filter((lap) => Array.isArray(lap?.points) && lap.points.length > 0)
    : [];
  currentLapIndex = 0;
  if (!currentSessionLaps.length) {
    fullLapSnapshot = null;
    updateLapToolbar();
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
  const n = Math.max(refSpeed.length, cmpSpeed.length);
  const labels = Array.from({ length: n }, (_, i) => i);
  compareSpeedChart = drawOrUpdateChart(compareSpeedChart, "compareSpeedChart", labels, [
    {
      label: `Reference speed (${refLap.lapId})`,
      data: refSpeed,
      borderColor: "#ff3d2e",
      backgroundColor: "rgba(255,61,46,0.12)",
      fill: false,
      tension: 0.15,
      pointRadius: 0,
      borderWidth: 2
    },
    {
      label: `Compare speed (${cmpLap.lapId})`,
      data: cmpSpeed,
      borderColor: "#22d3ee",
      backgroundColor: "rgba(34,211,238,0.10)",
      fill: false,
      tension: 0.15,
      pointRadius: 0,
      borderWidth: 2
    }
  ]);
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
  const direction = Number.isFinite(avgDelta)
    ? (avgDelta > 0 ? "A compare kor atlagosan gyorsabb." : (avgDelta < 0 ? "A compare kor atlagosan lassabb." : "A ket kor atlagsebessege megegyezik."))
    : "Atlagsebesseg delta nem szamolhato.";
  out.textContent =
    `Referencia kor: ${data?.referenceLapId || refLapId}\n` +
    `Compare kor: ${data?.compareLapId || cmpLapId}\n` +
    `Atlagsebesseg elteres: ${Number.isFinite(avgDelta) ? avgDelta.toFixed(3) : "n/a"} km/h\n` +
    `Osszehasonlitott mintak: ${Number.isFinite(comparedSamples) ? comparedSamples : "n/a"}\n` +
    `${direction}`;
}

async function loadSegmentDeltasOnMap() {
  const ref = document.getElementById("refSession").value;
  const cmp = document.getElementById("cmpSession").value;
  const lapId = document.getElementById("refLapSelect")?.value || "lap-1";
  const out = document.getElementById("compareResult");

  if (!ref || !cmp) {
    out.textContent = "Select reference and compare sessions first.";
    return;
  }

  const url = `${apiBase}/compare/segments?referenceSessionId=${encodeURIComponent(ref)}&compareSessionId=${encodeURIComponent(cmp)}&lapId=${encodeURIComponent(lapId)}&segmentCount=${DEFAULT_SEGMENT_COUNT}`;
  const res = await fetch(url);
  const data = await parseJsonResponse(res);
  if (!res.ok) {
    out.textContent = JSON.stringify(data ?? { error: res.status }, null, 2);
    return;
  }
  out.textContent = JSON.stringify(data, null, 2);
  const segs = data.segments;
  if (Array.isArray(segs) && segs.length) {
    showSchematicWithSegments(segs, true);
  }
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
const loadSegmentDeltasBtn = document.getElementById("loadSegmentDeltas");
if (loadSegmentDeltasBtn) {
  loadSegmentDeltasBtn.addEventListener("click", loadSegmentDeltasOnMap);
}
const clearSegmentFocusBtn = document.getElementById("clearSegmentFocus");
if (clearSegmentFocusBtn) {
  clearSegmentFocusBtn.addEventListener("click", () => {
    redrawFullLapCharts();
  });
}

loadSessions();
updateLapToolbar();
