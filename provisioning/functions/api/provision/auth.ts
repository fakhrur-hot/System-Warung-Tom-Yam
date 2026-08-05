// Configures the Supabase Auth service for a new café project.
//
// Sets the Site URL and allowed redirect URLs to the café's website so sign-up / password-reset
// confirmation links land back on the ordering site. Leaves the default email confirmation
// behaviour in place (confirmation required) by only patching the URL fields.
//
// Uses the Supabase Management API:
//   GET /v1/projects/{ref}/config/auth
//   PATCH /v1/projects/{ref}/config/auth
//
// The management auth-config shape is not exhaustively documented in public; this endpoint reads the
// current config first, then writes a merged patch so only the URL fields change. Verify against a
// real Supabase project before pointing at a production café (Requirement R8).

import type { PagesContext, ProvisionResponse, StepResult } from '../../_shared-ts/types'

interface ProvisionAuthRequest {
  /** Supabase Personal Access Token (account.supabase.com/tokens) */
  personalAccessToken: string
  /** Project reference ID, e.g. jxxzdmbvazxfbhkittlm */
  projectRef: string
  /** The café's public website URL, e.g. https://cafe-slug.pages.dev */
  websiteUrl: string
}

export async function onRequestPost(context: PagesContext): Promise<Response> {
  const body = (await context.request.json()) as ProvisionAuthRequest
  const { personalAccessToken, projectRef, websiteUrl } = body

  const results: StepResult[] = []
  const baseUrl = `https://api.supabase.com/v1/projects/${encodeURIComponent(projectRef)}/config/auth`

  try {
    const getResponse = await fetch(baseUrl, {
      method: 'GET',
      headers: { Authorization: `Bearer ${personalAccessToken}` },
    })

    if (!getResponse.ok) {
      const detail = await getResponse.text().catch(() => `HTTP ${getResponse.status}`)
      results.push({ step: 'auth-config-read', status: 'error', detail })
      return jsonResponse(results)
    }

    const currentConfig = (await getResponse.json()) as Record<string, unknown>

    // Merge the new site URL into the existing redirect list without clobbering other auth settings.
    const additionalRedirects = Array.isArray(currentConfig.additional_redirect_urls)
      ? [...(currentConfig.additional_redirect_urls as string[])]
      : []
    if (!additionalRedirects.includes(websiteUrl)) {
      additionalRedirects.push(websiteUrl)
    }

    const patch = {
      site_url: websiteUrl,
      additional_redirect_urls: additionalRedirects,
    }

    const patchResponse = await fetch(baseUrl, {
      method: 'PATCH',
      headers: {
        Authorization: `Bearer ${personalAccessToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(patch),
    })

    if (!patchResponse.ok) {
      const detail = await patchResponse.text().catch(() => `HTTP ${patchResponse.status}`)
      results.push({ step: 'auth-config-write', status: 'error', detail })
    } else {
      results.push({ step: 'auth-config', status: 'ok', detail: `site_url + ${websiteUrl} redirect` })
    }
  } catch (e) {
    results.push({ step: 'auth-config', status: 'error', detail: String(e) })
  }

  return jsonResponse(results)
}

function jsonResponse(results: StepResult[]): Response {
  return new Response(JSON.stringify({ results } satisfies ProvisionResponse), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
