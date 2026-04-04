// Local: Spring Boot. Deployed: call Render directly (CORS enabled on backend). Vercel /api rewrites are optional.
const RENDER_API_BASE = "https://telemetry-analyzer.onrender.com/api";
const apiBase =
  window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1"
    ? "http://localhost:8080/api"
    : RENDER_API_BASE;

const TRACK_CHART_MAX_POINTS = 12_000;

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

let speedChart;
let throttleBrakeChart;
let gearChart;
let trackMapChart;

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

async function uploadTelemetry() {
  const fileInput = document.getElementById("fileInput");
  const resultBox = document.getElementById("importResult");
  const uploadBtn = document.getElementById("uploadBtn");
  if (!fileInput.files.length) {
    resultBox.textContent = "Válassz fájlt.";
    return;
  }

  const file = fileInput.files[0];
  resultBox.textContent = `Feltöltés: ${file.name} (${(file.size / 1024 / 1024).toFixed(2)} MiB)…`;
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
    resultBox.textContent = `Feltöltés sikertelen: ${err.message}`;
    console.error(err);
  } finally {
    if (uploadBtn) {
      uploadBtn.disabled = false;
    }
  }
}

function sessionHasLapData(s) {
  return Array.isArray(s.laps) && s.laps.some((lap) => lap.points && lap.points.length > 0);
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
      opt.textContent = `${s.sessionId} (${s.sourceFile})`;
      select.appendChild(opt);
    });
  };

  fillSelect("sessionSelect", sessions);
  const comparable = sessions.filter(sessionHasLapData);
  fillSelect("refSession", comparable);
  fillSelect("cmpSession", comparable);
}

function drawOrUpdateChart(chartRef, canvasId, labels, datasets) {
  if (chartRef) {
    chartRef.data.labels = labels;
    chartRef.data.datasets = datasets;
    chartRef.update();
    return chartRef;
  }

  return new Chart(document.getElementById(canvasId), {
    type: "line",
    data: { labels, datasets },
    options: chartTheme
  });
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
  const lap = session.laps[0];
  const trackInfo = document.getElementById("trackInfo");

  if (!lap || !lap.points || lap.points.length === 0) {
    destroyTrackMapChart();
    if (trackInfo) {
      trackInfo.textContent = "Nincs köradat ebben a sessionben.";
    }
    return;
  }

  const labels = lap.points.map((p) => p.timestamp);
  const speed = lap.points.map((p) => p.speed);
  const throttle = lap.points.map((p) => p.throttle);
  const brake = lap.points.map((p) => p.brake);
  const gear = lap.points.map((p) => p.gear);

  const gpsRaw = lap.points
    .filter((p) => isPlausibleGps(p.latitude, p.longitude))
    .map((p) => ({ x: p.longitude, y: p.latitude }));
  const gpsPoints = downsamplePoints(gpsRaw, TRACK_CHART_MAX_POINTS);

  const distRaw = lap.points
    .filter((p) => Number.isFinite(p.distanceMeters))
    .map((p) => ({ x: p.distanceMeters, y: p.speed }))
    .sort((a, b) => a.x - b.x);
  const distPoints = downsamplePoints(distRaw, TRACK_CHART_MAX_POINTS);

  speedChart = drawOrUpdateChart(speedChart, "speedChart", labels, [
    { label: "Sebesség (km/h)", data: speed, borderColor: "#ff3d2e", backgroundColor: "rgba(255,61,46,0.12)", fill: false, tension: 0.15, pointRadius: 0, borderWidth: 2 }
  ]);

  throttleBrakeChart = drawOrUpdateChart(throttleBrakeChart, "throttleBrakeChart", labels, [
    { label: "Gáz", data: throttle, borderColor: "#22c55e", tension: 0.15, pointRadius: 0, borderWidth: 2 },
    { label: "Fék", data: brake, borderColor: "#ef4444", tension: 0.15, pointRadius: 0, borderWidth: 2 }
  ]);

  gearChart = drawOrUpdateChart(gearChart, "gearChart", labels, [
    { label: "Váltó", data: gear, borderColor: "#c084fc", tension: 0.1, pointRadius: 0, borderWidth: 2 }
  ]);

  if (!trackInfo) {
    return;
  }

  if (gpsPoints.length > 1) {
    drawTrackScatter(gpsPoints, "Hosszúság (°)", "Szélesség (°)", "GPS pálya");
    trackInfo.textContent = `GPS térkép: ${gpsPoints.length} pont a diagramon${gpsRaw.length > gpsPoints.length ? " (ritkítva a gyorsabb rajzolás miatt)" : ""}.`;
  } else if (distPoints.length > 1) {
    drawTrackScatter(distPoints, "Megtett út (m)", "Sebesség (km/h)", "Sebesség az út mentén");
    trackInfo.textContent = `Nincs (vagy nem használható) GPS; a fájl „Distance” (m) adata alapján: ${distPoints.length} pont${distRaw.length > distPoints.length ? " (ritkítva)" : ""}. Ez nem földrajzi térkép, csak a tempó változása a pályán megtett távolság szerint.`;
  } else {
    destroyTrackMapChart();
    trackInfo.textContent = "Ehhez az adathoz nincs GPS koordináta, és nincs „Distance” (m) oszlop sem — így nincs mit térképként kirajzolni. Az OpenF1 laps fájl tipikusan csak körszintű számokat tartalmaz. Valódi pályavonalhoz exportálj GPS csatornákat (pl. MoTeC-ben), vagy olyan CSV-t, ahol szélesség/hosszúság fokokban van.";
  }
}

async function compareSessions() {
  const ref = document.getElementById("refSession").value;
  const cmp = document.getElementById("cmpSession").value;
  const out = document.getElementById("compareResult");

  if (!ref || !cmp) {
    out.textContent = "Válassz két sessiont.";
    return;
  }

  const res = await fetch(`${apiBase}/compare?referenceSessionId=${encodeURIComponent(ref)}&compareSessionId=${encodeURIComponent(cmp)}`);
  const data = await parseJsonResponse(res);
  if (!res.ok) {
    out.textContent = JSON.stringify(data ?? { error: res.status }, null, 2);
    return;
  }
  out.textContent = JSON.stringify(data, null, 2);
}

document.getElementById("uploadBtn").addEventListener("click", uploadTelemetry);
document.getElementById("refreshSessions").addEventListener("click", loadSessions);
document.getElementById("loadSession").addEventListener("click", loadSelectedSession);
document.getElementById("runCompare").addEventListener("click", compareSessions);

loadSessions();
