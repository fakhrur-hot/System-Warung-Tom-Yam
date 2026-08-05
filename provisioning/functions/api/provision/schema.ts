// Applies supabase/migrations/*.sql to the café's new Supabase project via the Management API
// SQL query endpoint.
//
// This avoids asking the operator for the Postgres password and connection string. The Supabase
// Management API exposes `POST /v1/projects/{ref}/database/query`, which the Supabase CLI itself
// uses for `supabase db query --linked`. It accepts arbitrary DDL and returns one JSON result row
// per statement. Live verification confirmed it runs the full 0001_initial_schema.sql migration.
//
// The previous approach used a direct Postgres connection string with the `pg` package, but that
// required the operator to reveal and copy a password. The Management API call only needs the
// same Personal Access Token already used for functions/secrets/auth.

import { MIGRATIONS } from '../../_generated/migrations'
import type { PagesContext, ProvisionResponse, StepResult } from '../../_shared-ts/types'

interface ProvisionSchemaRequest {
  /** Supabase Personal Access Token (account.supabase.com/tokens) */
  personalAccessToken: string
  /** Project reference ID, e.g. jxxzdmbvazxfbhkittlm */
  projectRef: string
}

export async function onRequestPost(context: PagesContext): Promise<Response> {
  const body = (await context.request.json()) as ProvisionSchemaRequest
  const { personalAccessToken, projectRef } = body
  const results = await applyMigrations(personalAccessToken, projectRef)
  return new Response(JSON.stringify({ results } satisfies ProvisionResponse), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

/**
 * Applies each bundled migration file in order via the Management API query endpoint, reporting
 * one result per file (Requirement R2.3) rather than a single pass/fail for the whole batch —
 * these migrations are first-run-only, so a failure partway through must be diagnosable per-file.
 */
async function applyMigrations(
  personalAccessToken: string,
  projectRef: string,
): Promise<StepResult[]> {
  const results: StepResult[] = []
  const url = `https://api.supabase.com/v1/projects/${encodeURIComponent(projectRef)}/database/query`

  for (const { file, sql } of MIGRATIONS) {
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${personalAccessToken}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ query: sql }),
      })

      if (!response.ok) {
        const detail = await response.text().catch(() => `HTTP ${response.status}`)
        results.push({ step: file, status: 'error', detail })
      } else {
        results.push({ step: file, status: 'ok' })
      }
    } catch (e) {
      results.push({ step: file, status: 'error', detail: String(e) })
    }
  }

  return results
}
