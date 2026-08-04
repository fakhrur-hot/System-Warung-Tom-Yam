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
  /** Full RollerAds tag `src`, verbatim from the dashboard. See lib/rollerads.ts. */
  readonly VITE_ROLLERADS_TAG_SRC: string
  /** RollerAds site id. Informational — the tag URL already embeds it. */
  readonly VITE_ROLLERADS_SITE_ID: string
  /** Overrides where the central affiliate catalog is fetched from. See lib/partnerCatalog.ts. */
  readonly VITE_PARTNER_CATALOG_URL: string
  /** This café's OWN Shopee products as a JSON array, overriding the central catalog. */
  readonly VITE_SHOPEE_PRODUCTS: string
  /** Shopee `sub_id` for this café's own product list. */
  readonly VITE_SHOPEE_SUB_ID: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
