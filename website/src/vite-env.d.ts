/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SUPABASE_URL: string
  readonly VITE_SUPABASE_PUBLISHABLE_KEY: string
  /** Display name for the café. Defaults to "RAZ POS" when unset. */
  readonly VITE_CAFE_NAME: string
  /** Android app package name for .well-known/assetlinks.json. Defaults to com.razstudio.pos. */
  readonly VITE_APP_PACKAGE_NAME: string
  /** SHA-256 certificate fingerprint for .well-known/assetlinks.json (AA:BB:CC:… format). */
  readonly VITE_APP_SHA256_FINGERPRINT: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
