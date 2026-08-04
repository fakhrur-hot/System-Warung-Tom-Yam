import { defineConfig, type Plugin, type ResolvedConfig } from 'vite'
import react from '@vitejs/plugin-react'
import fs from 'node:fs'
import path from 'node:path'

/**
 * Generates two static JSON files into the build output directory:
 *
 *   .well-known/assetlinks.json  — Android deep-link verification, populated from
 *                                  VITE_APP_PACKAGE_NAME and VITE_APP_SHA256_FINGERPRINT.
 *
 *   app-config.json              — Self-configuration endpoint consumed by the Android app
 *                                  on first launch (Requirement 3.1 / 3.3). Populated from
 *                                  VITE_SUPABASE_URL, VITE_SUPABASE_PUBLISHABLE_KEY, and
 *                                  VITE_CAFE_NAME. A service-role key is rejected outright.
 *
 * Both files are written in the `closeBundle` hook so they overwrite the static copies that
 * Vite copies from `public/` earlier in the build. The static copies serve as dev-server
 * placeholders and carry no real café data.
 */
/**
 * Where a café fetches the central affiliate catalog from when nothing overrides it.
 *
 * GitHub raw on `main`: public, sends `Access-Control-Allow-Origin: *`, and caches for 5 minutes —
 * so a daily edit to `promos/partners.json` reaches every café within minutes with no rebuild. It
 * is not a CDN with an SLA and anonymous raw is rate-limited, which is exactly why the URL is
 * overridable per café (`partnerCatalogUrl`, or `VITE_PARTNER_CATALOG_URL`): moving to a Cloudflare
 * Pages project or R2 later is a config edit, not a code change in every café branch.
 */
const DEFAULT_CATALOG_URL =
  'https://raw.githubusercontent.com/fakhrur-hot/System-Warung-Tom-Yam/main/promos/partners.json'

/**
 * The café's OWN Shopee placements, for `app-config.json` — an override of the central catalog.
 *
 * Normally empty: a café inherits the catalog. Set `VITE_SHOPEE_PRODUCTS` only for a café that
 * curates its own list.
 *
 * A link SET does not fit the one-env-var-per-field shape the other values use, so the products
 * arrive as a single JSON array in `VITE_SHOPEE_PRODUCTS`, e.g.
 *
 *   [{"href":"https://s.shopee.com.my/AbCdEf","img":"","alt":"Shopee pick"}]
 *
 * Emitting this from the generator is the whole point: `closeBundle` OVERWRITES
 * `dist/app-config.json`, so a `shopeeAffiliate` block added to the deployed file by hand survives
 * only until the next build. Anything expected to outlive a deploy has to be produced here.
 *
 * Malformed JSON yields an empty product list rather than failing the build — a bad ad config must
 * not be able to stop a café's ordering page from shipping. The page drops invalid links again on
 * its own side (`lib/shopee.ts`).
 */
function shopeeAffiliate(): { subId: string; products: unknown[] } | null {
  const subId = process.env.VITE_SHOPEE_SUB_ID?.trim() ?? ''
  const raw = process.env.VITE_SHOPEE_PRODUCTS?.trim()

  // `null`, NOT an empty block. The page treats ANY present block as "this café overrides the
  // catalog" -- that is what makes opting out possible -- so emitting `{products: []}` by default
  // would have every café override the catalog with nothing and the central list would never be
  // read by anyone. The key stays present (the app-config contract test requires it); its value
  // says "no local override".
  if (!raw && !subId) return null

  if (!raw) return { subId, products: [] }
  try {
    const parsed = JSON.parse(raw)
    return { subId, products: Array.isArray(parsed) ? parsed : [] }
  } catch {
    console.warn('[generate-static-json] VITE_SHOPEE_PRODUCTS is not valid JSON — ignoring it')
    return { subId, products: [] }
  }
}

function generateStaticJsonPlugin(): Plugin {
  let resolvedConfig: ResolvedConfig

  return {
    name: 'generate-static-json',
    configResolved(cfg) {
      resolvedConfig = cfg
    },
    closeBundle() {
      const outDir = path.resolve(resolvedConfig.root, resolvedConfig.build.outDir)

      // ── env vars ────────────────────────────────────────────────────────────────
      const packageName =
        process.env.VITE_APP_PACKAGE_NAME?.trim() || 'com.razstudio.pos'

      const rawFingerprint = process.env.VITE_APP_SHA256_FINGERPRINT?.trim() ?? ''
      const fingerprints = rawFingerprint ? [rawFingerprint] : []

      const supabaseUrl = process.env.VITE_SUPABASE_URL?.trim() ?? ''
      const supabaseKey = process.env.VITE_SUPABASE_PUBLISHABLE_KEY?.trim() ?? ''
      const cafeName = process.env.VITE_CAFE_NAME?.trim() || 'RAZ POS'

      // ── security guard (Requirement 3.3) ────────────────────────────────────────
      // A service-role key must never appear in app-config.json.
      if (supabaseKey.startsWith('sb_secret') || supabaseKey.includes('service_role')) {
        throw new Error(
          '[generate-static-json] VITE_SUPABASE_PUBLISHABLE_KEY appears to be a ' +
          'service-role key. A service-role key must never be published in app-config.json. ' +
          'Use the publishable (anon) key instead.'
        )
      }

      // ── .well-known/assetlinks.json ──────────────────────────────────────────────
      const wellKnownDir = path.join(outDir, '.well-known')
      fs.mkdirSync(wellKnownDir, { recursive: true })
      fs.writeFileSync(
        path.join(wellKnownDir, 'assetlinks.json'),
        JSON.stringify(
          [
            {
              relation: ['delegate_permission/common.handle_all_urls'],
              target: {
                namespace: 'android_app',
                package_name: packageName,
                sha256_cert_fingerprints: fingerprints,
              },
            },
          ],
          null,
          2
        ) + '\n'
      )

      // ── app-config.json ──────────────────────────────────────────────────────────
      // Ad ids are published here rather than inlined from `VITE_*` so that ONE build can serve
      // many cafés: this is a plain static file, so a deployment can swap it (by hand, from
      // Supabase, or from a Worker keyed on hostname) without rebuilding. They are safe to publish
      // — an ad unit id appears in every visitor's page source by definition — and the
      // service-role guard above still governs what may never appear in this file.
      fs.writeFileSync(
        path.join(outDir, 'app-config.json'),
        JSON.stringify(
          {
            supabaseUrl,
            supabaseAnonKey: supabaseKey,
            cafeName,
            rolleradsTagSrc: process.env.VITE_ROLLERADS_TAG_SRC?.trim() ?? '',
            rolleradsSiteId: process.env.VITE_ROLLERADS_SITE_ID?.trim() ?? '',
            partnerCatalogUrl: process.env.VITE_PARTNER_CATALOG_URL?.trim() || DEFAULT_CATALOG_URL,
            shopeeAffiliate: shopeeAffiliate(),
          },
          null,
          2
        ) + '\n'
      )
    },
  }
}

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react(), generateStaticJsonPlugin()],
  server: { port: 5173 },
})
