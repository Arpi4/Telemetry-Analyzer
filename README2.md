# Motorsport Telemetry Analyzer

Bachelor thesis project for motorsport telemetry analysis and visualization.

## Modules
- `backend`: Spring Boot REST API for ingestion and analytics
- `web`: HTML/CSS/JS frontend using Chart.js
- `desktop`: JavaFX desktop scaffold
- `docs`: thesis technical documentation skeleton

## Public deployment (Render + Vercel)

1. Deploy backend to Render (Docker: `backend/Dockerfile`, optional `render.yaml`).
2. In `web/app.js`, set `RENDER_API_BASE` to your Render URL, e.g. `https://telemetry-analyzer.onrender.com/api`.
   - The browser calls Render **directly** (backend has `@CrossOrigin(origins = "*")`), so you do not depend on Vercel `/api` rewrites.
3. Optional: `vercel.json` and `web/vercel.json` contain `rewrites` that proxy `/api/*` to Render if you prefer same-origin `/api` URLs later.
4. Deploy on Vercel (choose one):
   - **Recommended:** Project Settings → **Root Directory** = `web`. Then the app is served at `/` and you can remove or simplify the repo-root `vercel.json` rewrites.
   - **Repo root deploy:** Keep `index.html` at repo root (redirects to `/web/index.html`) and `vercel.json` rewrites. If you still see **404 NOT_FOUND**, set Root Directory to `web` or open `https://YOUR_PROJECT.vercel.app/web/index.html` to verify files are deployed.

## Backend run
```bash
cd backend
./mvnw spring-boot:run
```

## Web run
Serve the `web` directory with any static server and open `index.html`.

The frontend uses:
- local dev: `http://localhost:8080/api`
- deployed: `RENDER_API_BASE` (Render public API URL)

## Desktop run
```bash
cd desktop
./mvnw javafx:run
```

## Windows commands
- Backend: `cd backend && mvnw.cmd spring-boot:run`
- Desktop: `cd desktop && mvnw.cmd javafx:run`

## LD/LDX support notes
- `.ldx`: XML-based parsing added for common row/sample/point structures.
- `.ld`: binary files are detected and rejected with a clear error message unless converted/exported to text/XML format.

## Track map
- Web UI now shows a GPS track map chart (`longitude` vs `latitude`) when telemetry points include location fields.
