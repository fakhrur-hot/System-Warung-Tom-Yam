// Live verification of the Supabase-side provisioner endpoints against a real project.
//
// This imports the actual endpoint modules so the code under test is exactly what the Wizard
// deploys, but runs them inside a plain Node process instead of inside a Cloudflare Pages Function.
// The endpoints only use fetch/FormData/Blob/Request (all native in Node 20+) and pg (installed), so
// this is a faithful offline-to-live bridge.
//
// Required env vars:
//   SUPABASE_PROJECT_REF        e.g. jxxzdmbvazxfbhkittlm
//   SUPABASE_PAT                account.supabase.com account token
//   SUPABASE_SERVICE_ROLE_KEY   project service-role key
//   SUPABASE_CONNECTION_STRING  URI from Project Settings → Database → Connection string
// Optional:
//   BREVO_API_KEY               any non-empty string (defaults to test-key)
//   WEBSITE_URL                 defaults to https://test-<ref>.pages.dev
//   SINGLE_FUNCTION             if set, only deploy one function instead of all 26
//
// Usage:
//   cd provisioning
//   npm run generate
//   npx tsx scripts/live-verify.mjs

import { execSync } from 'node:child_process'
import process from 'node:process'

// Make sure generated migrations/functions are fresh before importing endpoints.
execSync('npm run generate', { cwd: new URL('..', import.meta.url), stdio: 'inherit' })

const { onRequestPost: schema } = await import('../functions/api/provision/schema.ts')
const { onRequestPost: functions } = await import('../functions/api/provision/functions.ts')
const { onRequestPost: secrets } = await import('../functions/api/provision/secrets.ts')
const { onRequestPost: storage } = await import('../functions/api/provision/storage.ts')
const { onRequestPost: auth } = await import('../functions/api/provision/auth.ts')

const ref = requireEnv('SUPABASE_PROJECT_REF')
const pat = requireEnv('SUPABASE_PAT')
const serviceRoleKey = requireEnv('SUPABASE_SERVICE_ROLE_KEY')
const brevoApiKey = process.env.BREVO_API_KEY || 'test-key'
// No placeholder default. This value becomes the project's WEBSITE_ORIGIN secret, which is both
// the origin CORS allows and the origin minted into every owner-key/invite QR. Running this
// harness against a REAL café with the old `https://test-${ref}.pages.dev` fallback silently
// pointed all of that at a site that does not exist (it happened — Sri Pantai Timur, Aug 2026),
// blocking the real site's browser calls and minting dead QR links.
const websiteUrl = requireEnv('WEBSITE_URL')

function requireEnv(name) {
  const value = process.env[name]
  if (!value) {
    console.error(`Missing env var: ${name}`)
    process.exit(1)
  }
  return value
}

function makeContext(body) {
  return {
    request: new Request('http://localhost/api/provision/x', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
    env: {},
  }
}

async function call(name, endpoint, body) {
  console.log(`\n→ ${name}`)
  const response = await endpoint(makeContext(body))
  const text = await response.text()
  const data = text ? JSON.parse(text) : {}
  console.log(JSON.stringify(data, null, 2))
  const ok = data.results?.every((r) => r.status === 'ok')
  if (!ok) {
    console.error(`✗ ${name} failed`)
    process.exitCode = 1
  } else {
    console.log(`✓ ${name} ok`)
  }
  return data
}

console.log(`Live verification against Supabase project ${ref}`)
console.log(`Website URL: ${websiteUrl}`)

await call('1. Schema', schema, { personalAccessToken: pat, projectRef: ref })

const functionBody = { personalAccessToken: pat, projectRef: ref }
if (process.env.SINGLE_FUNCTION) {
  const { EDGE_FUNCTIONS } = await import('../functions/_generated/edge-functions.ts')
  const single = EDGE_FUNCTIONS[0]
  functionBody.only = single.name
  console.log(`(SINGLE_FUNCTION mode: only deploying ${single.name})`)
}
await call('2. Functions', functions, functionBody)

await call('3. Secrets', secrets, {
  personalAccessToken: pat,
  projectRef: ref,
  secrets: { BREVO_API_KEY: brevoApiKey, WEBSITE_ORIGIN: websiteUrl },
})

await call('4. Storage', storage, { projectRef: ref, serviceRoleKey })

await call('5. Auth', auth, {
  personalAccessToken: pat,
  projectRef: ref,
  websiteUrl,
})

console.log('\nVerification complete. Check the Supabase dashboard for each step.')
