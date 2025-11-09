import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'node:path'

// https://vite.dev/config/
export default defineConfig({
  envDir: path.resolve(__dirname, '..'), // load shared .env at repo root
  plugins: [react()],
})
