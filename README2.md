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

## Desktop download
The desktop client is also released as a self-contained app with its own trimmed Java runtime: it
needs no Java installation and works fully offline.

1. Download the archive for your system from the latest `desktop-v…` release on the
   [Releases page](https://github.com/Arpi4/Telemetry-Analyzer/releases):
   - Windows (x64): `telemetry-analyzer-<version>-windows-x64.zip`
   - macOS (Apple silicon): `telemetry-analyzer-<version>-macos-aarch64.zip`
   - Linux (x64): `telemetry-analyzer-<version>-linux-x64.tar.gz` (needs GTK 3, as on any common desktop)
2. Extract it anywhere and start the app:
   - Windows: `Telemetry Analyzer\Telemetry Analyzer.exe`
   - macOS: `Telemetry Analyzer.app` (can be moved to *Applications*)
   - Linux: `telemetry-analyzer/bin/telemetry-analyzer`

The builds are not code-signed, so the first start shows a warning:
- **Windows SmartScreen** ("Windows protected your PC"): click *More info* → *Run anyway*. Unblocking the
  zip before extracting (*Properties* → *Unblock*) avoids the prompt. Windows 11 *Smart App Control*, when
  turned on, blocks unsigned apps without such an option.
- **macOS Gatekeeper** ("Apple could not verify …"): close the dialog, then allow the app under
  *System Settings* → *Privacy & Security* → *Open Anyway* (on macOS 14 and older, Control-click the app →
  *Open* also works). Alternatively run `xattr -dr com.apple.quarantine "Telemetry Analyzer.app"`.

If Windows reports "Failed to launch JVM", the folder path is too long; extract to a shorter one such as
`C:\Tools`. On first start JavaFX unpacks its native libraries to `~/.openjfx/cache`; to remove the app,
delete the extracted folder and that cache.

### Releasing the desktop app
Pushing a `desktop-vX.Y.Z` tag runs `.github/workflows/desktop-release.yml`: it builds and tests on
Windows, macOS and Linux, packages each with `desktop/package-desktop.sh` and creates (or updates) the
GitHub Release of the tag with the three archives. The tag must match the Maven project version in
`pom.xml`:
```bash
git tag desktop-v0.1.0
git push origin desktop-v0.1.0
```
Started manually (*Actions* → *Desktop release* → *Run workflow*) on a branch, the workflow only builds
the archives and keeps them as workflow artifacts.

### Packaging locally
Requires a full JDK 21+ with `jpackage` (e.g. Temurin; the JetBrains runtime bundled with IntelliJ has
none). In Git Bash on Windows, or on macOS / Linux:
```bash
JAVA_HOME=/path/to/jdk-21 bash desktop/package-desktop.sh    # extra arguments go to Maven, e.g. -DskipTests
```
The archive for the current OS is written to `desktop/target/dist`, the unpacked app to
`desktop/target/jpackage/image`. jpackage cannot cross-build, so every archive is built on its own OS;
an Intel Mac build, for example, has to be packaged on an Intel Mac.

## Windows commands
- Build: `mvnw.cmd install`
- Backend: `mvnw.cmd -pl backend spring-boot:run`
- Desktop: `mvnw.cmd -pl desktop javafx:run`

## LD/LDX support notes
- `.ldx`: XML-based parsing added for common row/sample/point structures.
- `.ld`: binary files are detected and rejected with a clear error message unless converted/exported to text/XML format.

## Track map
- Web UI now shows a GPS track map chart (`longitude` vs `latitude`) when telemetry points include location fields.
