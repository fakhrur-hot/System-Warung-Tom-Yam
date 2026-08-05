// Live verification of the /api/provision/run orchestrator endpoint against a real Supabase +
// Cloudflare setup. This imports the actual endpoint module so the code under test is exactly what
// the Wizard deploys.
//
// Required env vars depend on which mode you are testing:
//   SUPABASE_PAT                  account.supabase.com account token (always needed)
//
// For new Supabase project:
//   SUPABASE_ORG_ID               organization id from the Supabase dashboard
//   SUPABASE_REGION               e.g. ap-southeast-1
//   SUPABASE_PROJECT_NAME         desired project name (must be globally unique-ish)
//
// For existing Supabase project:
//   SUPABASE_PROJECT_REF
//   SUPABASE_ANON_KEY
//   SUPABASE_SERVICE_ROLE_KEY
//
// For new Cloudflare Pages project:
//   CLOUDFLARE_ACCOUNT_ID
//   CLOUDFLARE_API_TOKEN
//   CLOUDFLARE_CAFE_SLUG          becomes the Pages project name
//   RAZSTUDIO_GITHUB_OWNER
//   RAZBASE_GITHUB_REPO
//
// For existing Cloudflare Pages project:
//   CLOUDFLARE_ACCOUNT_ID
//   CLOUDFLARE_API_TOKEN
//   CLOUDFLARE_PROJECT_NAME
//
// Optional:
//   BREVO_API_KEY
//   CLOUDFLARE_ZONE_ID
//   CLOUDFLARE_CUSTOM_DOMAIN
//   CAFE_NAME

import process from 'node:process'
import { execSync } from 'node:child_process'

// Make sure generated migrations/functions are fresh before importing the endpoint.
execSync('npm run generate', { cwd: new URL('..', import.meta.url), stdio: 'inherit' })

const { onRequestPost: run } = await import('../functions/api/provision/run.ts')

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
    request: new Request('http://localhost/api/provision/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
    env: {
      RAZSTUDIO_GITHUB_OWNER: process.env.RAZSTUDIO_GITHUB_OWNER || '',
      RAZSTUDIO_GITHUB_REPO: process.env.RAZSTUDIO_GITHUB_REPO || '',
    },
  }
}

const pat = requireEnv('SUPABASE_PAT')
const supabaseMode = process.env.SUPABASE_PROJECT_REF ? 'existing' : 'new'
const cloudflareMode = process.env.CLOUDFLARE_PROJECT_NAME ? 'existing' : 'new'

const supabase =
  supabaseMode === 'existing'
    ? {
        mode: 'existing',
        personalAccessToken: pat,
        projectRef: requireEnv('SUPABASE_PROJECT_REF'),
        anonKey: requireEnv('SUPABASE_ANON_KEY'),
        serviceRoleKey: requireEnv('SUPABASE_SERVICE_ROLE_KEY'),
      }
    : {
        mode: 'new',
        personalAccessToken: pat,
        orgId: requireEnv('SUPABASE_ORG_ID'),
        region: requireEnv('SUPABASE_REGION'),
        projectName: requireEnv('SUPABASE_PROJECT_NAME'),
      }

const cloudflare =
  cloudflareMode === 'existing'
    ? {
        mode: 'existing',
        accountId: requireEnv('CLOUDFLARE_ACCOUNT_ID'),
        apiToken: requireEnv('CLOUDFLARE_API_TOKEN'),
        projectName: requireEnv('CLOUDFLARE_PROJECT_NAME'),
        zoneId: process.env.CLOUDFLARE_ZONE_ID,
        customDomain: process.env.CLOUDFLARE_CUSTOM_DOMAIN,
      }
    : {
        mode: 'new',
        accountId: requireEnv('CLOUDFLARE_ACCOUNT_ID'),
        apiToken: requireEnv('CLOUDFLARE_API_TOKEN'),
        cafeSlug: requireEnv('CLOUDFLARE_CAFE_SLUG'),
        zoneId: process.env.CLOUDFLARE_ZONE_ID,
        customDomain: process.env.CLOUDFLARE_CUSTOM_DOMAIN,
      }

const body = {
  supabase,
  cloudflare,
  cafe: {
    cafeName: process.env.CAFE_NAME || 'Test Café',
    brevoApiKey: process.env.BREVO_API_KEY,
  },
}

console.log('\nLive verification of /api/provision/run')
console.log(`Supabase mode: ${supabaseMode}`)
console.log(`Cloudflare mode: ${cloudflareMode}`)
console.log('This may take a few minutes if a new Supabase project is being created...\n')

const response = await run(makeContext(body))
const data = await response.json()
console.log(JSON.stringify(data, null, 2))

const allOk = data.results?.every((r) => r.status === 'ok')
process.exitCode = allOk ? 0 : 1
