# Motorsport Telemetry Analyzer

Bachelor thesis project for motorsport telemetry analysis and visualization.

## Modules
- `core`: framework-free Java library – parsers (generic CSV, MoTeC CSV, OpenF1, LDX), lap splitting,
  distance-aligned lap comparison (delta time, sectors), driving event detection (braking zones,
  corners, pedal metrics), track reconstruction from GPS or yaw rate
- `backend`: Spring Boot REST API on top of `core`
- `web`: HTML/CSS/JS frontend using Chart.js
- `desktop`: offline JavaFX client on top of `core` (lap analysis, lap comparison, track map, PNG export)

## Build and test
Requires JDK 21+ and Maven 3.9 (on Windows the root `mvnw.cmd` wrapper can be used instead of `mvn`). From the repository root:
```bash
mvn install               # builds core, backend, desktop and runs all tests
```
The `test_data*.csv` files in the repository root are sample MoTeC CSV exports for trying the clients.

## REST API (additions)
- `GET /api/sessions/{id}/overview` – complete laps, fastest lap, theoretical best lap
- `GET /api/sessions/{id}/laps/{lapId}/events` – braking zones, corners, driving metrics
- `GET /api/sessions/{id}/laps/{lapId}/track` – track outline (GPS or dead reckoning)
- `GET /api/compare/aligned?referenceSessionId=&referenceLapId=&compareSessionId=&compareLapId=` – distance-aligned comparison

The original endpoints (`/telemetry/import`, `/sessions`, `/compare`, `/compare/segments`, …) are unchanged.

## Public deployment (Render + Vercel)

1. Deploy backend to Render (Docker: `backend/Dockerfile`, `render.yaml`). The backend depends on
   `core`, so the Docker **build context must be the repository root** (`dockerContext: .`,
   `dockerfilePath: ./backend/Dockerfile`). If the Render service was created in the dashboard
   rather than from the blueprint, set *Root Directory* to empty and *Dockerfile Path* to
   `backend/Dockerfile` there. The image sizes the JVM for the free plan (512 MB, 0.1 CPU) through
   `JAVA_TOOL_OPTIONS`; on a larger instance, override that environment variable in Render.
2. In `web/app.js`, set `RENDER_API_BASE` to your Render URL, e.g. `https://telemetry-analyzer.onrender.com/api`.
   - The browser calls Render **directly** (backend has `@CrossOrigin(origins = "*")`), so you do not depend on Vercel `/api` rewrites.
3. Optional: `vercel.json` and `web/vercel.json` contain `rewrites` that proxy `/api/*` to Render if you prefer same-origin `/api` URLs later.
4. Deploy on Vercel (choose one):
   - **Recommended:** Project Settings → **Root Directory** = `web`. Then the app is served at `/` and you can remove or simplify the repo-root `vercel.json` rewrites.
   - **Repo root deploy:** Keep `index.html` at repo root (redirects to `/web/index.html`) and `vercel.json` rewrites. If you still see **404 NOT_FOUND**, set Root Directory to `web` or open `https://YOUR_PROJECT.vercel.app/web/index.html` to verify files are deployed.

## Backend run
```bash
mvn -pl core install
mvn -pl backend spring-boot:run
```

## Web run
Serve the `web` directory with any static server and open `index.html`.

The frontend uses:
- local dev: `http://localhost:8080/api`
- deployed: `RENDER_API_BASE` (Render public API URL)

## Desktop run
```bash
mvn -pl core install
mvn -pl desktop javafx:run
```
Files can be opened from the toolbar or dropped onto the window. Command line arguments are files
to open; `--screenshots=<dir>` renders the main views to PNG and exits.

## Windows commands
- Build: `mvnw.cmd install`
- Backend: `mvnw.cmd -pl backend spring-boot:run`
- Desktop: `mvnw.cmd -pl desktop javafx:run`

## LD/LDX support notes
- `.ldx`: XML-based parsing added for common row/sample/point structures.
- `.ld`: binary files are detected and rejected with a clear error message unless converted/exported to text/XML format.

## Track map
- Web UI now shows a GPS track map chart (`longitude` vs `latitude`) when telemetry points include location fields.
