/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string
  readonly VITE_PROXY_TARGET?: string
  /** 智能助手（drone-agent）的代理目标 */
  readonly VITE_AGENT_PROXY_TARGET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
