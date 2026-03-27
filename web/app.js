const apiBase = window.location.hostname === "localhost"
  ? "http://localhost:8080/api"
  : "/api";

let speedChart;
let throttleBrakeChart;
let gearChart;
let trackMapChart;

async function uploadTelemetry() {
  const fileInput = document.getElementById("fileInput");
  const resultBox = document.getElementById("importResult");
  if (!fileInput.files.length) {
    resultBox.textContent = "Valassz fajlt.";
    return;
  }

  const formData = new FormData();
  formData.append("file", fileInput.files[0]);

  const res = await fetch(`${apiBase}/telemetry/import`, { method: "POST", body: formData });
  const data = await res.json();
  resultBox.textContent = JSON.stringify(data, null, 2);
  await loadSessions();
}

async function loadSessions() {
  const res = await fetch(`${apiBase}/sessions`);
  const sessions = await res.json();

  ["sessionSelect", "refSession", "cmpSession"].forEach((id) => {
    const select = document.getElementById(id);
    select.innerHTML = "";
    sessions.forEach((s) => {
      const opt = document.createElement("option");
      opt.value = s.sessionId;
      opt.textContent = `${s.sessionId} (${s.sourceFile})`;
      select.appendChild(opt);
    });
  });
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
    options: {
      responsive: true,
      maintainAspectRatio: true,
      interaction: { mode: "index", intersect: false },
      plugins: { legend: { display: true } }
    }
  });
}

function drawOrUpdateScatter(chartRef, canvasId, points) {
  const dataset = [{
    label: "Track map",
    data: points,
    showLine: true,
    borderColor: "#f59e0b",
    backgroundColor: "#f59e0b",
    pointRadius: 1
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
      scales: {
        x: { title: { display: true, text: "Longitude" } },
        y: { title: { display: true, text: "Latitude" } }
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
  const session = await res.json();
  const lap = session.laps[0];
  if (!lap || !lap.points) {
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
    { label: "Speed", data: speed, borderColor: "#2563eb", pointRadius: 0 }
  ]);

  throttleBrakeChart = drawOrUpdateChart(throttleBrakeChart, "throttleBrakeChart", labels, [
    { label: "Throttle", data: throttle, borderColor: "#16a34a", pointRadius: 0 },
    { label: "Brake", data: brake, borderColor: "#dc2626", pointRadius: 0 }
  ]);

  gearChart = drawOrUpdateChart(gearChart, "gearChart", labels, [
    { label: "Gear", data: gear, borderColor: "#7c3aed", pointRadius: 0 }
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
    out.textContent = "Valassz ket sessiont.";
    return;
  }

  const res = await fetch(`${apiBase}/compare?referenceSessionId=${encodeURIComponent(ref)}&compareSessionId=${encodeURIComponent(cmp)}`);
  const data = await res.json();
  out.textContent = JSON.stringify(data, null, 2);
}

document.getElementById("uploadBtn").addEventListener("click", uploadTelemetry);
document.getElementById("refreshSessions").addEventListener("click", loadSessions);
document.getElementById("loadSession").addEventListener("click", loadSelectedSession);
document.getElementById("runCompare").addEventListener("click", compareSessions);

loadSessions();
