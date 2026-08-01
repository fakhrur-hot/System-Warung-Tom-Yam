#!/usr/bin/env node
/**
 * Task 9.4 — prove the guard fails when it should, not merely that it passes.
 *
 * A check that has quietly stopped matching anything looks exactly like a clean tree. That is the
 * failure mode worth guarding against here: this script's whole value is its ability to say "no",
 * and nothing else in CI would notice if it lost that.
 *
 * Each case runs the real guard against a scratch git repository, because the guard reads
 * `git ls-files` — pointing it at a plain directory would exercise a code path production never
 * takes.
 *
 * Usage:  node scripts/check-no-cafe-literals.test.mjs
 */

import { execFileSync, spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, cpSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const GUARD = join(HERE, 'check-no-cafe-literals.mjs')

let failures = 0
function check(name, condition, detail = '') {
  if (condition) {
    console.log(`  ✓ ${name}`)
  } else {
    console.error(`  ✗ ${name}${detail ? `\n      ${detail}` : ''}`)
    failures++
  }
}

/** Build a throwaway repo containing `files`, run the guard in it, return {code, out}. */
function runGuardOn(files) {
  const dir = mkdtempSync(join(tmpdir(), 'cafe-guard-'))
  try {
    execFileSync('git', ['init', '-q'], { cwd: dir })
    execFileSync('git', ['config', 'user.email', 't@t'], { cwd: dir })
    execFileSync('git', ['config', 'user.name', 't'], { cwd: dir })

    for (const [path, body] of Object.entries(files)) {
      const full = join(dir, path)
      mkdirSync(dirname(full), { recursive: true })
      writeFileSync(full, body, 'utf8')
    }
    execFileSync('git', ['add', '-A'], { cwd: dir })

    mkdirSync(join(dir, 'scripts'), { recursive: true })
    cpSync(GUARD, join(dir, 'scripts', 'check-no-cafe-literals.mjs'))

    const r = spawnSync(process.execPath, ['scripts/check-no-cafe-literals.mjs'], {
      cwd: dir,
      encoding: 'utf8',
    })
    return { code: r.status, out: (r.stdout || '') + (r.stderr || '') }
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

console.log('guard self-test\n')

// ── It must FAIL on a known-bad tree ───────────────────────────────────────────────────────────
{
  const { code, out } = runGuardOn({
    'app/src/Notification.kt': 'val title = "Warung Tom Yam"\n',
  })
  check('fails on a café name in app source', code === 1, `exit ${code}`)
  check('names the file', out.includes('app/src/Notification.kt'))
  check('names the offending value', out.includes('Warung Tom Yam'))
  check('gives a line number', /Notification\.kt:1/.test(out))
}

{
  const { code } = runGuardOn({ 'src/config.ts': "const host = 'tani-tom-yam.pages.dev'\n" })
  check('fails on a café domain', code === 1)
}

{
  const { code } = runGuardOn({
    'supabase/functions/x/index.ts': 'const p = "https://jxxzdmbvazxfbhkittlm.supabase.co"\n',
  })
  check('fails on a live Supabase project ref', code === 1)
}

{
  const { code, out } = runGuardOn({
    'src/leak.ts': "const k = 'sb_secret_9aZ4kQ2mX7pL'\n",
  })
  check('fails on a service-role secret', code === 1)
  check('labels it as a service-role key', out.includes('SERVICE-ROLE'))
}

// ── It must PASS on a known-good tree ──────────────────────────────────────────────────────────
{
  const { code, out } = runGuardOn({
    'app/src/Notification.kt': 'val title = appConfig.cafeName()\n',
    'src/config.ts': "const host = import.meta.env.VITE_SITE\n",
  })
  check('passes on a café-agnostic tree', code === 0, out)
}

// ── Scope: it must not cry wolf, in either direction ───────────────────────────────────────────
{
  // Docs describe history; they do not configure a build. Flagging them would train everyone to
  // ignore this check, which is the same as deleting it.
  const { code } = runGuardOn({ 'README.md': 'The tani-tom-yam deployment is described here.\n' })
  check('allows a real café name in documentation', code === 0)
}

{
  // .env.example exists precisely to show where a key goes. A placeholder is not a secret.
  const { code } = runGuardOn({
    '.env.example': 'VITE_SUPABASE_PUBLISHABLE_KEY=sb_publishable_xxxxxxxx\nSUPABASE_SECRET_KEY=sb_secret_xxxxxxxx\n',
  })
  check('allows placeholder keys in .env.example', code === 0)
}

{
  // The *word* service_role names a Postgres role and appears in config, in comments explaining why
  // the server bypasses RLS, and in the code that refuses such a key. Only a real value is a leak.
  const { code } = runGuardOn({
    'supabase/config.toml': '# roles: anon, authenticated, service_role\n',
    'website/vite.config.ts': "if (key.includes('service_role')) throw new Error('refused')\n",
  })
  check('allows the word service_role where it is legitimate', code === 0)
}

{
  // Untracked files are not scanned: local.properties is the *correct* home for these values, and
  // flagging it would be flagging the intended design.
  const dir = mkdtempSync(join(tmpdir(), 'cafe-guard-untracked-'))
  try {
    execFileSync('git', ['init', '-q'], { cwd: dir })
    mkdirSync(join(dir, 'scripts'), { recursive: true })
    cpSync(GUARD, join(dir, 'scripts', 'check-no-cafe-literals.mjs'))
    writeFileSync(join(dir, 'local.properties'), 'CAFE_NAME=Warung Tom Yam\n', 'utf8')
    const r = spawnSync(process.execPath, ['scripts/check-no-cafe-literals.mjs'], {
      cwd: dir, encoding: 'utf8',
    })
    check('ignores untracked local.properties', r.status === 0, r.stdout + r.stderr)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

console.log('')
if (failures > 0) {
  console.error(`${failures} self-test(s) failed — the guard is not trustworthy\n`)
  process.exit(1)
}
console.log('guard self-test passed\n')
