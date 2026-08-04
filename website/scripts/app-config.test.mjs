#!/usr/bin/env node
/**
 * Task 3.4 — `/app-config.json` publishes exactly seven public fields, and can never carry a secret.
 *
 * The endpoint's whole justification is that it discloses nothing new: the live bundle already
 * serves the project URL and publishable key in plain text, because Vite inlines
 * `VITE_SUPABASE_*` at build time. That argument holds only while the payload stays limited to
 * values the bundle already leaks. A service-role key is the one value whose exposure would be a
 * real breach rather than a restatement, so it must fail the build rather than ship.
 *
 * The generator lives in `vite.config.ts` and runs in `closeBundle`. Rather than booting Vite, these
 * cases exercise the same rules against the built artefact and the config source — enough to catch
 * a field being added, a secret slipping through, or the guard being removed.
 *
 * Usage:  node scripts/app-config.test.mjs      (from website/)
 */

import { readFileSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = join(HERE, '..')

let failures = 0
const check = (name, ok, detail = '') => {
  if (ok) console.log(`  ✓ ${name}`)
  else { console.error(`  ✗ ${name}${detail ? `\n      ${detail}` : ''}`); failures++ }
}

console.log('app-config.json contract\n')

// Widened from three to five when Adsterra ids moved out of build-time `VITE_*` and into this
// runtime file, so one build can serve many cafes. Ad unit ids are public by definition -- they
// appear in every visitor's page source -- so they do not weaken the no-secrets rule below.
// The Adsterra pair was then replaced by the RollerAds tag/site id, and `shopeeAffiliate` plus
// `partnerCatalogUrl` were added -- all public for the same reason: an ad id, a referral link and
// the URL of a public catalog all appear in the rendered page or its network log anyway.
const EXPECTED = [
  'supabaseUrl',
  'supabaseAnonKey',
  'cafeName',
  'rolleradsTagSrc',
  'rolleradsSiteId',
  'partnerCatalogUrl',
  'shopeeAffiliate',
]

// ── The static placeholder shipped in public/ ─────────────────────────────────────────────────
const placeholderPath = join(ROOT, 'public', 'app-config.json')
check('a placeholder exists for the dev server', existsSync(placeholderPath))

if (existsSync(placeholderPath)) {
  const raw = readFileSync(placeholderPath, 'utf8')
  let json
  try { json = JSON.parse(raw) } catch (e) { check('placeholder is valid JSON', false, String(e)) }

  if (json) {
    const keys = Object.keys(json).sort()
    check('exactly the expected fields', JSON.stringify(keys) === JSON.stringify([...EXPECTED].sort()),
      `got: ${keys.join(', ')}`)

    // The placeholder must not carry a real café's values — it is committed, and the whole
    // single-source point is that shared source names no café.
    check('placeholder carries no real project ref', !/[a-z]{20}\.supabase\.co/.test(raw))
    check('placeholder carries no real publishable key',
      !/sb_publishable_(?![xX]{4,})[A-Za-z0-9]{12,}/.test(raw))
    check('placeholder carries no secret whatsoever',
      !raw.includes('sb_secret') && !raw.includes('service_role'))
  }
}

// ── The generator that produces the real one at build time ───────────────────────────────────
const cfgPath = join(ROOT, 'vite.config.ts')
const cfg = readFileSync(cfgPath, 'utf8')

check('generator reads the same env the bundle uses',
  cfg.includes('VITE_SUPABASE_URL') && cfg.includes('VITE_SUPABASE_PUBLISHABLE_KEY'),
  'if it read different variables the endpoint could drift from the bundle it claims to mirror')

check('generator emits exactly the expected fields',
  EXPECTED.every((k) => cfg.includes(k)),
  'an undeclared field would break the "discloses nothing new" argument')

// The failure this catches, from the field's own history: `shopeeAffiliate` was hand-added to the
// DEPLOYED app-config.json while neither the generator nor RuntimeConfig knew about it. Nothing
// broke and nothing rendered -- it just sat there being dropped, and the next build would have
// erased it. A key the page reads must be produced by the generator and declared in the interface.
const runtimeCfg = readFileSync(join(ROOT, 'src', 'lib', 'runtimeConfig.ts'), 'utf8')
check('every published field is declared in RuntimeConfig',
  EXPECTED.filter((k) => k !== 'supabaseAnonKey').every((k) => runtimeCfg.includes(k)),
  'an undeclared field is silently dropped at runtime -- present in the JSON, invisible to the page')

check('a service-role key is refused at build time',
  /sb_secret|service_role/.test(cfg) && /throw\s+new\s+Error/.test(cfg),
  'this guard is the difference between restating public data and leaking a real secret')

check('the file is written after the public/ copy, so the placeholder cannot win',
  cfg.includes('closeBundle'),
  'writing earlier would let Vite overwrite the generated file with the placeholder')

// ── The built output, when present ───────────────────────────────────────────────────────────
const built = join(ROOT, 'dist', 'app-config.json')
if (existsSync(built)) {
  const raw = readFileSync(built, 'utf8')
  const json = JSON.parse(raw)
  const keys = Object.keys(json).sort()
  check('built output has exactly the expected fields',
    JSON.stringify(keys) === JSON.stringify([...EXPECTED].sort()), `got: ${keys.join(', ')}`)
  check('built output carries no secret',
    !raw.includes('sb_secret') && !raw.includes('service_role'))
} else {
  console.log('  – dist/app-config.json absent (no build in this tree) — skipped')
}

console.log('')
if (failures > 0) {
  console.error(`${failures} check(s) failed\n`)
  process.exit(1)
}
console.log('app-config.json contract holds\n')
