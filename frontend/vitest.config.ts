import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Vitest reads this file instead of vite.config.ts, because Vitest resolves
// the `vite` package to its own copy and that copy does not export every Vite
// helper.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    include: ['src/**/*.test.{ts,tsx}', 'functions/**/*.test.ts'],
  },
})
