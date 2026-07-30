// Flattens each supabase/functions/<name>/index.ts into one self-contained file with its
// `_shared/*` imports inlined, because the Supabase Management API's function-deploy endpoint's
// only documented example is a SINGLE file — whether its multipart body supports multiple file
// parts is undocumented, so this avoids relying on that entirely (see design.md's Function
// Deployment section).
//
// _shared files import from EACH OTHER (auth.ts -> supabase.ts, errors.ts -> cors.ts), so this
// resolves the transitive closure per function and inlines each shared module EXACTLY ONCE, in
// dependency order, then self-checks the result for duplicate top-level declarations — the
// concrete failure mode a naive per-import inliner would hit silently.
//
// Run via `npm run generate` (part of `npm run build`), NOT committed — see .gitignore.

import { readFileSync, readdirSync, writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const here = path.dirname(fileURLToPath(import.meta.url))
const functionsDir = path.join(here, '..', '..', 'supabase', 'functions')
const sharedDir = path.join(functionsDir, '_shared')
const outDir = path.join(here, '..', 'functions', '_generated')

// Matches `import { A, B } from "../_shared/name.ts";` (function files) or
// `import { A, B } from "./name.ts";` (inside _shared files themselves), including the
// multi-line `{ ... }` form some functions use — hence the `s` (dotAll) flag.
const SHARED_IMPORT_RE = /import\s*\{[^}]*\}\s*from\s*["'](?:\.\.\/_shared\/|\.\/)([\w-]+)\.ts["'];?\n?/gs

function removeSharedImports(code) {
  return code.replace(SHARED_IMPORT_RE, '')
}

// `export const X` / `export function X` / `export async function X` -> the same without
// `export `, since the merged file declares everything at its own top level.
function stripExportKeyword(code) {
  return code.replace(/^export\s+(const|function|async function|interface|type|class)\s/gm, '$1 ')
}

function loadSharedFiles() {
  const files = {}
  for (const f of readdirSync(sharedDir).filter((f) => f.endsWith('.ts'))) {
    files[f.replace(/\.ts$/, '')] = readFileSync(path.join(sharedDir, f), 'utf-8')
  }
  return files
}

/** Topologically resolves a shared module's transitive `_shared`-internal dependencies. */
function resolveClosure(directNames, sharedFiles) {
  const order = []
  const visited = new Set()
  function visit(name) {
    if (visited.has(name)) return
    visited.add(name)
    const content = sharedFiles[name]
    if (content === undefined) throw new Error(`Unknown shared module referenced: ${name}.ts`)
    const deps = [...content.matchAll(/from\s*["']\.\/([\w-]+)\.ts["']/g)].map((m) => m[1])
    for (const dep of deps) visit(dep)
    order.push(name)
  }
  for (const name of directNames) visit(name)
  return order
}

function findDuplicateTopLevelDeclarations(mergedCode) {
  const decls = [
    ...mergedCode.matchAll(/^(?:const|function|async function|interface|type|class)\s+(\w+)/gm),
  ].map((m) => m[1])
  // Also count imported bindings — `import { createClient } ...` declares `createClient` in the
  // same top-level scope as a `const`/`function`, so a leftover duplicate import is just as much
  // a collision as a duplicate const would be (this is the exact bug dedupeExternalImports fixes;
  // this check is what catches it if a future change reintroduces it).
  const imported = [...mergedCode.matchAll(/^import\s*\{([^}]*)\}/gm)]
    .flatMap((m) => m[1].split(','))
    .map((s) => s.split(/\s+as\s+/)[0].trim())
    .filter(Boolean)
  const seen = new Set()
  const dupes = new Set()
  for (const d of [...decls, ...imported]) {
    if (seen.has(d)) dupes.add(d)
    seen.add(d)
  }
  return [...dupes]
}

// Single-line `import { A, B } from "url";` — matches what's left after SHARED_IMPORT_RE has
// already stripped every `_shared`/`./`-relative import, i.e. only EXTERNAL (esm.sh, deno.land)
// imports remain at this point.
const EXTERNAL_IMPORT_LINE_RE = /^import\s*\{[^}]*\}\s*from\s*["'][^"']+["'];?\s*$/gm

/**
 * Two different _shared modules can import the SAME external symbol from the SAME URL (e.g. both
 * auth.ts and supabase.ts import `createClient` from the same esm.sh URL) — inlining both leaves
 * that import declared twice, which Deno/TS rejects as a duplicate top-level binding. Dedupes by
 * exact line text (keeping the first occurrence, hoisted to the top) and, as a safety net, throws
 * if the same imported NAME ever appears with two DIFFERENT source URLs, since silently picking
 * one would be a correctness bug, not a formatting nicety.
 */
function dedupeExternalImports(mergedCode) {
  const lines = [...mergedCode.matchAll(EXTERNAL_IMPORT_LINE_RE)].map((m) => m[0].trim())
  const uniqueLines = [...new Set(lines)]

  const nameToSource = new Map()
  for (const line of uniqueLines) {
    const m = line.match(/\{([^}]*)\}\s*from\s*["']([^"']+)["']/)
    if (!m) continue
    const names = m[1].split(',').map((s) => s.trim()).filter(Boolean)
    const source = m[2]
    for (const name of names) {
      const bare = name.split(/\s+as\s+/)[0].trim()
      const existing = nameToSource.get(bare)
      if (existing && existing !== source) {
        throw new Error(
          `Import collision: "${bare}" is imported from both "${existing}" and "${source}" after ` +
            `inlining — cannot safely dedupe automatically.`,
        )
      }
      nameToSource.set(bare, source)
    }
  }

  let body = mergedCode.replace(EXTERNAL_IMPORT_LINE_RE, '')
  // Collapse the blank-line gaps left behind so removed import lines don't leave visual holes.
  body = body.replace(/\n{3,}/g, '\n\n')
  const hoisted = uniqueLines.length ? uniqueLines.join('\n') + '\n\n' : ''
  return hoisted + body.trimStart()
}

function hasIndexTs(dirName) {
  try {
    readFileSync(path.join(functionsDir, dirName, 'index.ts'), 'utf-8')
    return true
  } catch {
    return false
  }
}

const sharedFiles = loadSharedFiles()
const functionNames = readdirSync(functionsDir, { withFileTypes: true })
  // "_shared" is imports, not a function; "tests" holds Deno test files with no index.ts
  // (not a deployable function) — skip anything without one rather than assuming every
  // directory is deployable.
  .filter((d) => d.isDirectory() && d.name !== '_shared' && hasIndexTs(d.name))
  .map((d) => d.name)
  .sort()

const results = []

for (const fnName of functionNames) {
  const indexPath = path.join(functionsDir, fnName, 'index.ts')
  const code = readFileSync(indexPath, 'utf-8')

  const directNames = [
    ...new Set([...code.matchAll(/from\s*["']\.\.\/_shared\/([\w-]+)\.ts["']/g)].map((m) => m[1])),
  ]
  const closureOrder = resolveClosure(directNames, sharedFiles)

  const inlinedBlocks = closureOrder.map((name) => {
    const raw = sharedFiles[name]
    const flattened = stripExportKeyword(removeSharedImports(raw))
    return `// ---- inlined from _shared/${name}.ts ----\n${flattened.trim()}\n`
  })

  const functionBody = removeSharedImports(code)

  const header =
    `// GENERATED by scripts/inline-functions.mjs from supabase/functions/${fnName}/index.ts\n` +
    `// _shared modules inlined (${closureOrder.join(', ') || 'none'}) — see design.md's Function\n` +
    `// Deployment section for why.\n\n`
  const body =
    (inlinedBlocks.length ? inlinedBlocks.join('\n') + '\n' : '') +
    `// ---- ${fnName}/index.ts (own body; _shared imports removed, inlined above) ----\n` +
    functionBody

  // Two different _shared modules can import the same external symbol (createClient, in this
  // codebase) — dedupe those external imports to one hoisted copy before the correctness check.
  const merged = header + dedupeExternalImports(body)

  const dupes = findDuplicateTopLevelDeclarations(merged)
  if (dupes.length > 0) {
    throw new Error(
      `Function "${fnName}": duplicate top-level declaration(s) after inlining: ${dupes.join(', ')}. ` +
        `This is the exact failure mode design.md flags as needing a live-deploy check before trusting ` +
        `the inliner across all functions — fix the collision before proceeding.`,
    )
  }

  results.push({ name: fnName, content: merged })
}

mkdirSync(outDir, { recursive: true })
writeFileSync(
  path.join(outDir, 'edge-functions.ts'),
  `// GENERATED by scripts/inline-functions.mjs — do not edit by hand.\n\n` +
    `export interface InlinedFunction {\n  name: string\n  content: string\n}\n\n` +
    `export const EDGE_FUNCTIONS: InlinedFunction[] = ${JSON.stringify(results, null, 2)}\n`,
  'utf-8',
)

console.log(`Inlined ${results.length} function(s) -> functions/_generated/edge-functions.ts`)
for (const r of results) console.log(`  - ${r.name} (${r.content.length} bytes)`)
