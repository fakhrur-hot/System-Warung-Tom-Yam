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
      // Adsterra ids are published here rather than inlined from `VITE_*` so that ONE build can
      // serve many cafés: this is a plain static file, so a deployment can swap it (by hand, from
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
            adsterraSrc: process.env.VITE_ADSTERRA_NATIVE_SRC?.trim() ?? '',
            adsterraContainer: process.env.VITE_ADSTERRA_NATIVE_CONTAINER?.trim() ?? '',
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
