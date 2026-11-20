import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The frontend always talks to its own origin. In production the Cloudflare
// Pages Function in /functions/api proxies /api/* to the backend, so the
// browser only ever makes same-origin requests and session cookies keep
// working even when third party cookies are blocked.
//
// During local development the Vite dev server proxies /api to the local
// backend instead, so the development code is identical to production code.
//
// The Vitest settings live in vitest.config.ts, which Vitest prefers over this
// file.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  const backendUrl = env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080'

  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: Number(env.VITE_PORT ?? 5173),
      strictPort: true,
      proxy: {
        '/api': {
          target: backendUrl,
          changeOrigin: false,
          secure: false,
        },
      },
    },
    preview: {
      port: Number(env.VITE_PREVIEW_PORT ?? 4173),
      strictPort: true,
    },
    build: {
      outDir: 'dist',
      sourcemap: true,
      chunkSizeWarningLimit: 900,
    },
  }
})
