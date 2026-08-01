#!/usr/bin/env node
/**
 * Fails the build when a café-specific literal is committed to shared source (Requirement 9).
 *
 * This exists because nothing else in the repo is self-enforcing. The whole single-source-template
 * effort can be undone by one edit putting a real café's name or domain back into a file every café
 * shares, and it would not be noticed — that is precisely how the fork happened the first time, and
 * how `main` ended up with a notification that read "Warung Tom Yam" on every café's staff phone.
 *
 * ### Scope is the hard part, in both directions
 *
 * A check that cries wolf gets disabled, and one with holes gives false confidence. So:
 *
 *  - Only **tracked** files are scanned. `local.properties` is untracked and is the *correct* home
 *    for these values; flagging it would be flagging the intended design.
 *  - Documentation, specs and historical records legitimately name real cafés — a README explaining
 *    the tani deployment is describing history, not configuring a build.
 *  - Test fixtures may need a real-looking name to be meaningful, and `cors_test.ts` deliberately
 *    lists the forbidden strings as data.
 *  - Build output (`dist/`, `build/`) is generated from configuration and is not source.
 *
 * Everything else — app code, resources, manifests, Edge Functions, migrations, CI — is shared
 * source and must stay generic.
 *
 * Usage:  node scripts/check-no-cafe-literals.mjs
 * Exit 0 clean, 1 with a report naming file, line and value.
 */

import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'

/**
 * Café-specific strings that must never appear in shared source.
 *
 * Onboarding a café adds one entry here. That is deliberate: the list is the record of which names
 * are "real", and keeping it manual is what lets the check stay precise instead of guessing with a
 * heuristic that would flag every capitalised word.
 */
const FORBIDDEN = [
  { pattern: /tani[-_ ]?tom[-_ ]?yam/i, label: 'tani-tom-yam (café slug / domain)' },
  { pattern: /Warung\s+Tom\s+Yam/i, label: 'Warung Tom Yam (café name)' },
  { pattern: /Warung\s+POS\s+RAZStudio/i, label: 'Warung POS RAZStudio (café app name)' },
  { pattern: /jxxzdmbvazxfbhkittlm/i, label: 'live Supabase project ref' },
  // A *real* publishable key. Placeholder runs (sb_publishable_xxxxxxxx, ...YOUR_KEY) are what
  // .env.example is supposed to contain, so requiring mixed case and digits keeps the check on
  // actual secrets rather than on the documentation telling people where to put theirs.
  { pattern: /sb_publishable_(?![xX]{4,})(?=[A-Za-z0-9]*[A-Z])(?=[A-Za-z0-9]*[0-9])[A-Za-z0-9]{12,}/,
    label: 'a real publishable key' },
  // Only an actual secret value. The *word* service_role is legitimate and common: it names a
  // Postgres role in config.toml, documents why the server client bypasses RLS, and is what
  // vite.config.ts checks for in order to *refuse* such a key. Flagging the word would train
  // everyone to ignore this check.
  { pattern: /sb_secret_(?![xX]{4,})(?=[A-Za-z0-9]*[A-Z])(?=[A-Za-z0-9]*[0-9])[A-Za-z0-9]{12,}/, label: 'a SERVICE-ROLE key — never commit this' },
]

/** Paths where a real café name is legitimate. Anything not matched here is shared source. */
const ALLOWED = [
  /(^|\/)README\.md$/,                 // describes the product and its history
  /^\.kiro\//,                         // specs quote real values as evidence
  /^shared\/api-contract\.md$/,        // documents an example response
  /^spikes\//,                         // research notes, historical
  /^docs?\//,
  /\.md$/,                             // prose generally: docs describe, they do not configure
  /^scripts\/check-no-cafe-literals\.mjs$/,  // this file lists them by necessity
  /^supabase\/functions\/tests\//,     // cors_test.ts asserts the strings are absent
  /^apk\/app\/src\/test\//,            // fixtures may use a realistic name
  /(^|\/)(dist|build|node_modules)\//, // generated, not source
  /^supabase\/migrations\/.*\.sql$/,   // applied history — editing a shipped migration is worse
  /^apk\/app\/src\/main\/assets\/presets\//, // per-café seed data, see the note below
]

function tracked() {
  return execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
    .split('\0')
    .filter(Boolean)
}

const BINARY = /\.(png|jpe?g|webp|gif|ico|ttf|otf|woff2?|jar|apk|aab|keystore|pdf|zip|mov|mp4|onnx)$/i

const findings = []
for (const file of tracked()) {
  if (ALLOWED.some((re) => re.test(file))) continue
  if (BINARY.test(file)) continue

  let text
  try {
    text = readFileSync(file, 'utf8')
  } catch {
    continue // unreadable or genuinely binary
  }
  if (text.includes('\0')) continue

  text.split(/\r?\n/).forEach((line, i) => {
    for (const { pattern, label } of FORBIDDEN) {
      const m = line.match(pattern)
      if (m) findings.push({ file, line: i + 1, value: m[0], label })
    }
  })
}

if (findings.length === 0) {
  console.log('✓ no café-specific literals in shared source')
  process.exit(0)
}

console.error(`\n✗ ${findings.length} café-specific literal(s) in shared source:\n`)
for (const f of findings) {
  console.error(`  ${f.file}:${f.line}`)
  console.error(`      found: ${f.value}`)
  console.error(`      why:   ${f.label}\n`)
}
console.error('Shared source must stay generic. Move the value into local.properties (build-time),')
console.error('the café\'s own deployment env (website / Edge Functions), or its owner QR.')
console.error('If this file legitimately documents a real café, add it to ALLOWED in this script.\n')
process.exit(1)
