// Applies supabase/migrations/*.sql to the café's new Supabase project over a direct Postgres
// connection — the SAME approach supabase/apply-migration.mjs already uses, chosen because the
// obvious Management API endpoint (POST /v1/projects/{ref}/database/migrations) is access-gated
// to "select customers" per Supabase's own docs and would silently fail for most accounts.
//
// UNVERIFIED (Requirement R8 / tasks.md 2.2): this endpoint's code follows Cloudflare's own
// official tutorial pattern (developers.cloudflare.com/workers/tutorials/postgres/ — plain `pg`
// package + the `nodejs_compat` compatibility flag in wrangler.toml), but has NOT been run against
// a real Supabase project from inside an actual deployed Cloudflare Pages Function. Do not treat
// this as production-ready until that live check passes — see design.md's Testing Strategy.

import { Client } from 'pg'
import { MIGRATIONS } from '../../_generated/migrations'
import type { PagesContext, ProvisionResponse, StepResult } from '../../_shared-ts/types'

interface ProvisionSchemaRequest {
  connectionString: string
}

export async function onRequestPost(context: PagesContext): Promise<Response> {
  const { connectionString } = (await context.request.json()) as ProvisionSchemaRequest
  const results = await applyMigrations(connectionString)
  return new Response(JSON.stringify({ results } satisfies ProvisionResponse), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

/**
 * Applies each bundled migration file in order, reporting one result per file (Requirement R2.3)
 * rather than a single pass/fail for the whole batch — these migrations are first-run-only
 * (`create table`/`create type` errors if the object already exists, same caveat
 * apply-migration.mjs documents), so a failure partway through must be diagnosable per-statement.
 */
async function applyMigrations(connectionString: string): Promise<StepResult[]> {
  const client = new Client({ connectionString })
  const results: StepResult[] = []

  try {
    await client.connect()
  } catch (e) {
    return [{ step: 'connect', status: 'error', detail: String(e) }]
  }

  try {
    for (const { file, sql } of MIGRATIONS) {
      try {
        await client.query(sql)
        results.push({ step: file, status: 'ok' })
      } catch (e) {
        // Do NOT abort the loop — a later migration might not depend on this one, and per-file
        // reporting (rather than throwing) is what lets the operator see exactly which statement
        // failed instead of the whole run going dark after the first error.
        results.push({ step: file, status: 'error', detail: String(e) })
      }
    }
  } finally {
    await client.end().catch(() => {})
  }

  return results
}
