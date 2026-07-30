// Deploys all 26 pre-inlined Edge Functions to the café's new Supabase project via the Management
// API's documented (if minimally-documented) deploy endpoint. Uses EDGE_FUNCTIONS from
// scripts/inline-functions.mjs's output — each entry is already a single self-contained file with
// its `_shared/*` imports flattened in, so every upload matches the ONE shape Supabase's docs
// actually demonstrate (see design.md's Function Deployment section for why that matters: the
// endpoint's only documented example is single-file, and whether multi-file multipart bodies are
// supported at all is undocumented).
//
// UNVERIFIED (Requirement R8 / tasks.md 3.2): deploy ONE function live and confirm it actually
// invokes correctly post-deploy before trusting this loop across all 26 — a subtle inliner bug
// (a name collision between two functions' shared imports) would otherwise ship silently broken
// functions. Do not treat this as production-ready until that live check passes.

import { EDGE_FUNCTIONS } from '../../_generated/edge-functions'
import type { PagesContext, ProvisionResponse, StepResult } from '../../_shared-ts/types'

interface ProvisionFunctionsRequest {
  personalAccessToken: string
  projectRef: string
}

export async function onRequestPost(context: PagesContext): Promise<Response> {
  const { personalAccessToken, projectRef } = (await context.request.json()) as ProvisionFunctionsRequest
  const results: StepResult[] = []

  // Sequential, not parallel: keeps the per-step checklist meaningful (results arrive in a
  // predictable order) and avoids hammering the Management API with 26 concurrent requests, which
  // risks rate-limiting mid-batch on an operation the operator is watching live.
  for (const fn of EDGE_FUNCTIONS) {
    results.push(await deployFunction({ personalAccessToken, projectRef, name: fn.name, content: fn.content }))
  }

  return new Response(JSON.stringify({ results } satisfies ProvisionResponse), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

async function deployFunction(params: {
  personalAccessToken: string
  projectRef: string
  name: string
  content: string
}): Promise<StepResult> {
  const { personalAccessToken, projectRef, name, content } = params

  // Confirmed shape (Supabase Management API docs / community discussion #33720):
  //   POST /v1/projects/{ref}/functions/deploy?slug=<name>
  //   multipart/form-data: metadata={entrypoint_path, name}, file=@<source>
  // Redeploying an existing slug updates it in place, so this call is safe to repeat
  // (Requirement R3.3) — no separate "does it already exist" check needed.
  const form = new FormData()
  form.append('metadata', JSON.stringify({ entrypoint_path: 'index.ts', name }))
  form.append('file', new Blob([content], { type: 'application/typescript' }), 'index.ts')

  try {
    const response = await fetch(
      `https://api.supabase.com/v1/projects/${projectRef}/functions/deploy?slug=${encodeURIComponent(name)}`,
      {
        method: 'POST',
        headers: { Authorization: `Bearer ${personalAccessToken}` },
        // Deliberately NOT setting Content-Type — fetch computes the multipart boundary itself
        // when given a FormData body; overriding it here would send a header with no boundary
        // and break the parse on Supabase's end.
        body: form,
      },
    )
    if (!response.ok) {
      const detail = await response.text().catch(() => `HTTP ${response.status}`)
      return { step: name, status: 'error', detail }
    }
    return { step: name, status: 'ok' }
  } catch (e) {
    return { step: name, status: 'error', detail: String(e) }
  }
}
