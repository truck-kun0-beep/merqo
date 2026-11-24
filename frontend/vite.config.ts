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
          // The browser must not be able to say where the request came from.
          // The backend reads the address of the caller from the forwarded
          // header and counts it in the login throttle, so a header the
          // browser chose would let anybody lock every account. The dev proxy
          // drops it the same way the Cloudflare proxy does, and the backend
          // falls back to the address of the socket.
          configure: (proxy) => {
            proxy.on('proxyReq', (proxyRequest) => {
              proxyRequest.removeHeader('x-forwarded-for')
              proxyRequest.removeHeader('x-real-ip')
              proxyRequest.removeHeader('forwarded')
              proxyRequest.removeHeader('client-ip')
            })
          },
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
