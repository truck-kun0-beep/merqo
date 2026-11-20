/// <reference types="vite/client" />

/**
 * Ambient declarations for the frontend.
 *
 * `vite/client` teaches TypeScript about static asset imports, `import.meta.env`
 * and the CSS modules used by the application.
 */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_API_PROXY_TARGET?: string
  readonly VITE_PORT?: string
  readonly VITE_PREVIEW_PORT?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
