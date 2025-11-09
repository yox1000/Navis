# Environment Setup

1. Copy .env.example to .env and fill in any missing secrets.
2. Frontend (Vite) now reads from the repo-root .env thanks to envDir in rontend/vite.config.js.
3. Backend already loads the same .env via ackend/src/config.js, so both layers stay in sync.
4. Only keys prefixed with VITE_ will be exposed to the browser build, so keep sensitive tokens unprefixed if they should remain server-only.

Current values checked in:
- MAPTILER_API_KEY / VITE_MAPTILER_API_KEY: qRoabl1Sk209dUYRU4L3
- VITE_MAP_STYLE_URL: Bright style URL for MapTiler
- NEURALSEEK_*: left blank until provided.
