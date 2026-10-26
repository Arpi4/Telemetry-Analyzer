# Motorsport Telemetry Analyzer

A telemetry analysis tool developed as part of a bachelor's thesis project at the University of Szeged.

The goal of this project is to design and implement a software platform capable of analyzing motorsport telemetry data in order to evaluate driver performance and vehicle behavior.

The system processes telemetry datasets (e.g. CSV files) and provides visualization and analytical tools to better understand lap performance.

Public URL: https://telemetry-analyzer.vercel.app/web/index.html 
---

## Features

- Import telemetry from generic CSV, MoTeC i2 CSV exports (e.g. from Assetto Corsa Competizione), OpenF1 lap exports and LDX
- Automatic lap splitting from beacon markers; complete-lap detection, fastest and theoretical best lap
- Channel charts over lap distance (speed, throttle, brake, gear)
- Distance-aligned lap comparison: speed/pedal overlays, cumulative delta time, exact sector deltas, braking point differences
- Driving pattern detection: braking zones, corner apexes, time-weighted pedal metrics
- Track map from GPS or reconstructed from yaw rate (dead reckoning with loop closure), coloured by speed with braking points, or by time gained/lost with sector markers in lap comparison – the same map in both clients
- Web client (Spring Boot REST + Chart.js) and offline JavaFX desktop client sharing one analysis core
- Desktop app downloadable from [GitHub Releases](https://github.com/Arpi4/Telemetry-Analyzer/releases/latest) for Windows, macOS and Linux, with a bundled Java runtime (no installation, works offline)

---

## Technologies

- **Java 21** – shared `core` analysis library (Maven multi-module)
- **Spring Boot** – REST backend
- **JavaFX** – offline desktop application
- **HTML / CSS / JavaScript, Chart.js** – web client
- **Docker, Render, Vercel** – deployment

See `README2.md` for build/run instructions.

---

## Telemetry Data

The software is designed to process typical motorsport telemetry parameters such as:

- Speed
- Throttle
- Brake
- Gear
- Lap time
- Track position

The system aims to support datasets from motorsport telemetry sources or simulated telemetry data.

---

## Project Status

This project is currently under development as part of a university thesis.

Future work will include:

- advanced telemetry analytics
- improved track visualization
- performance comparison tools
- expanded data format support

---

## Author

**Árpád Zsolt Tóth**  
Computer Science BSc  
University of Szeged
