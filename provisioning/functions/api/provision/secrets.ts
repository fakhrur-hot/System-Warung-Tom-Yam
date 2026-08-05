// Sets Edge Function secrets on a new Supabase project via the Management API.
//
// Supabase Management API docs:
//   POST /v1/projects/{ref}/secrets
//   Body: { secrets: [{ name, value }] }
//
// Each secret becomes one row in the step results so a failure in one value does not hide the
// state of the others. Re-running this endpoint with the same name updates the value in place.

import type { PagesContext, ProvisionResponse, StepResult } from '../../_shared-ts/types'

interface ProvisionSecretsRequest {
  /** Supabase Personal Access Token (account.supabase.com/tokens) */
  personalAccessToken: string
  /** Project reference ID, e.g. jxxzdmbvazxfbhkittlm */
  projectRef: string
  secrets: Record<string, string | undefined>
}

export async function onRequestPost(context: PagesContext): Promise<Response> {
  const body = (await context.request.json()) as ProvisionSecretsRequest
  const { personalAccessToken, projectRef, secrets } = body

  const results: StepResult[] = []
  const entries = Object.entries(secrets).filter(([, value]) => value !== undefined && value !== '')

  if (entries.length === 0) {
    results.push({ step: 'set-secrets', status: 'skipped', detail: 'No secrets provided' })
    return new Response(JSON.stringify({ results } satisfies ProvisionResponse), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  try {
    const response = await fetch(
      `https://api.supabase.com/v1/projects/${encodeURIComponent(projectRef)}/secrets`,
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${personalAccessToken}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          secrets: entries.map(([name, value]) => ({ name, value })),
        }),
      },
    )

    if (!response.ok) {
      const detail = await response.text().catch(() => `HTTP ${response.status}`)
      results.push({ step: 'set-secrets', status: 'error', detail })
    } else {
      for (const [name] of entries) {
        results.push({ step: `secret:${name}`, status: 'ok' })
      }
    }
  } catch (e) {
    results.push({ step: 'set-secrets', status: 'error', detail: String(e) })
  }

  return new Response(JSON.stringify({ results } satisfies ProvisionResponse), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
