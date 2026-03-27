// Local: Spring Boot. Deployed: call Render directly (CORS enabled on backend). Vercel /api rewrites are optional.
const RENDER_API_BASE = "https://telemetry-analyzer.onrender.com/api";
const apiBase =
  window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1"
    ? "http://localhost:8080/api"
    : RENDER_API_BASE;

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

async function uploadTelemetry() {
  const fileInput = document.getElementById("fileInput");
  const resultBox = document.getElementById("importResult");
  if (!fileInput.files.length) {
    resultBox.textContent = "Choose a file.";
    return;
  }

  const formData = new FormData();
  formData.append("file", fileInput.files[0]);

  const res = await fetch(`${apiBase}/telemetry/import`, { method: "POST", body: formData });
  const data = await parseJsonResponse(res);
  if (!res.ok) {
    resultBox.textContent = JSON.stringify(data ?? { error: res.status }, null, 2);
    return;
  }
  resultBox.textContent = JSON.stringify(data, null, 2);
  await loadSessions();
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

function drawOrUpdateScatter(chartRef, canvasId, points) {
  const dataset = [{
    label: "Track map",
    data: points,
    showLine: true,
    borderColor: "#ff3d2e",
    backgroundColor: "rgba(255,61,46,0.5)",
    pointRadius: 1,
    borderWidth: 2
  }];

  if (chartRef) {
    chartRef.data.datasets = dataset;
    chartRef.update();
    return chartRef;
  }

  return new Chart(document.getElementById(canvasId), {
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
          title: { display: true, text: "Longitude", color: "#8b9cb3" },
          ticks: { color: "#8b9cb3" },
          grid: { color: "rgba(255,255,255,0.06)" }
        },
        y: {
          title: { display: true, text: "Latitude", color: "#8b9cb3" },
          ticks: { color: "#8b9cb3" },
          grid: { color: "rgba(255,255,255,0.06)" }
        }
      }
    }
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
  if (!lap || !lap.points || lap.points.length === 0) {
    const trackInfo = document.getElementById("trackInfo");
    if (trackInfo) {
      trackInfo.textContent = "No lap data in this session.";
    }
    return;
  }

  const labels = lap.points.map((p) => p.timestamp);
  const speed = lap.points.map((p) => p.speed);
  const throttle = lap.points.map((p) => p.throttle);
  const brake = lap.points.map((p) => p.brake);
  const gear = lap.points.map((p) => p.gear);
  const gpsPoints = lap.points
    .filter((p) => Number.isFinite(p.latitude) && Number.isFinite(p.longitude))
    .map((p) => ({ x: p.longitude, y: p.latitude }));

  speedChart = drawOrUpdateChart(speedChart, "speedChart", labels, [
    { label: "Speed", data: speed, borderColor: "#ff3d2e", backgroundColor: "rgba(255,61,46,0.12)", fill: false, tension: 0.15, pointRadius: 0, borderWidth: 2 }
  ]);

  throttleBrakeChart = drawOrUpdateChart(throttleBrakeChart, "throttleBrakeChart", labels, [
    { label: "Throttle", data: throttle, borderColor: "#22c55e", tension: 0.15, pointRadius: 0, borderWidth: 2 },
    { label: "Brake", data: brake, borderColor: "#ef4444", tension: 0.15, pointRadius: 0, borderWidth: 2 }
  ]);

  gearChart = drawOrUpdateChart(gearChart, "gearChart", labels, [
    { label: "Gear", data: gear, borderColor: "#c084fc", tension: 0.1, pointRadius: 0, borderWidth: 2 }
  ]);

  const trackInfo = document.getElementById("trackInfo");
  if (gpsPoints.length > 1) {
    trackInfo.textContent = `GPS points: ${gpsPoints.length}`;
    trackMapChart = drawOrUpdateScatter(trackMapChart, "trackMapChart", gpsPoints);
  } else {
    trackInfo.textContent = "No GPS data found in this lap.";
  }
}

async function compareSessions() {
  const ref = document.getElementById("refSession").value;
  const cmp = document.getElementById("cmpSession").value;
  const out = document.getElementById("compareResult");

  if (!ref || !cmp) {
    out.textContent = "Select two sessions.";
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
