// Deploys a café's Edge Functions from a laptop, without the Wizard.
//
// ### Why this exists alongside /api/provision/functions
//
// The Wizard endpoint is the normal path, and the APK's "Deploy Edge Functions to this project"
// button calls it. Both are unreachable in one specific situation, and it is the situation that
// matters most: the Wizard itself is not correctly deployed. Deployed as a Worker instead of a Pages
// project it serves its UI perfectly and 404s every /api/provision/* route, so the tool you would
// use to fix a café cannot be reached to fix anything — including itself.
//
// This is the same operation, byte for byte: same generated bundle, same Management API endpoint,
// same multipart shape as functions.ts. It is not a second implementation of the deploy contract,
// and it must not become one — if the endpoint's shape changes, change it there and mirror it here.
//
// Also useful for a café provisioned before this Wizard existed, or one whose run failed partway.
// Idempotent: the Management API replaces a slug in place, so re-running is safe.
//
// Usage:
//   cd provisioning
//   npm run generate                     # refresh the bundle from supabase/functions
//   SUPA_PAT=sbp_... SUPA_REF=<projectref> node scripts/deploy-edge-functions.mjs
//
//   # optional: deploy a single function, for the R8 live check
//   SUPA_PAT=... SUPA_REF=... ONLY=settings node scripts/deploy-edge-functions.mjs
//
// SUPA_PAT is a Supabase Personal Access Token (account.supabase.com/tokens). Passed by environment,
// never as an argument — arguments land in shell history and process listings.

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const PAT = process.env.SUPA_PAT
const REF = process.env.SUPA_REF
const ONLY = process.env.ONLY?.trim()

if (!PAT || !REF) {
  console.error(
    'SUPA_PAT and SUPA_REF are required.\n' +
      '  SUPA_PAT=sbp_...  Supabase personal access token\n' +
      '  SUPA_REF=...      project ref, e.g. the subdomain of <ref>.supabase.co',
  )
  process.exit(1)
}

const here = path.dirname(fileURLToPath(import.meta.url))
const bundlePath = path.join(here, '..', 'functions', '_generated', 'edge-functions.ts')

let src
try {
  src = readFileSync(bundlePath, 'utf-8')
} catch {
  console.error(`Bundle not found at ${bundlePath}\nRun \`npm run generate\` first.`)
  process.exit(1)
}

// The bundle is generated with JSON.stringify, so the array literal parses as JSON directly. Reading
// it as text avoids needing a TypeScript loader for what is, in substance, a data file.
const start = src.indexOf('[', src.indexOf('EDGE_FUNCTIONS'))
const end = src.lastIndexOf(']')
if (start === -1 || end === -1) {
  console.error('Could not parse EDGE_FUNCTIONS out of the generated bundle.')
  process.exit(1)
}

const all = JSON.parse(src.slice(start, end + 1))
const targets = ONLY ? all.filter((fn) => fn.name === ONLY) : all

if (targets.length === 0) {
  console.error(ONLY ? `No function named "${ONLY}" in the bundle.` : 'Bundle is empty.')
  process.exit(1)
}

console.log(`Deploying ${targets.length} function(s) to ${REF}`)

let ok = 0
const failed = []

// Sequential, matching functions.ts: a predictable order keeps the progress list meaningful, and 33
// concurrent Management API calls risk rate-limiting partway through an operation being watched live.
for (const fn of targets) {
  const form = new FormData()
  form.append('metadata', JSON.stringify({ entrypoint_path: 'index.ts', name: fn.name }))
  form.append('file', new Blob([fn.content], { type: 'application/typescript' }), 'index.ts')

  try {
    const res = await fetch(
      `https://api.supabase.com/v1/projects/${REF}/functions/deploy?slug=${encodeURIComponent(fn.name)}`,
      {
        method: 'POST',
        headers: { Authorization: `Bearer ${PAT}` },
        // No Content-Type: fetch computes the multipart boundary from the FormData body. Setting it
        // by hand sends a header with no boundary and breaks the parse on Supabase's end.
        body: form,
      },
    )
    if (res.ok) {
      ok++
      console.log(`  ok    ${fn.name}`)
    } else {
      const detail = (await res.text().catch(() => `HTTP ${res.status}`)).slice(0, 300)
      failed.push({ name: fn.name, detail })
      console.log(`  FAIL  ${fn.name}  ${res.status}  ${detail.slice(0, 160)}`)
    }
  } catch (e) {
    failed.push({ name: fn.name, detail: String(e) })
    console.log(`  ERROR ${fn.name}  ${e}`)
  }
}

console.log(`\nDeployed ${ok}/${targets.length}`)

// A partial deploy is the dangerous outcome — the café looks alive and fails on whichever endpoint
// did not land — so it exits non-zero and names every failure rather than reporting a tidy summary.
if (failed.length) {
  console.log('\nFailed:')
  for (const f of failed) console.log(`  ${f.name}: ${f.detail}`)
  process.exit(1)
}
