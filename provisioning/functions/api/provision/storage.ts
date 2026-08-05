// Creates public Storage buckets on a new Supabase project.
//
// Uses the Storage Admin API with the service role key. Buckets are created with `public: true`,
// which is the same as ticking "Public bucket" in the Supabase dashboard and is sufficient for
// customer-facing reads of logos and menu images. The café uploads images through the admin website
// using the authenticated publishable key, so no additional policies are needed for this template.
//
// Supabase docs: POST /storage/v1/bucket

import type { PagesContext, ProvisionResponse, StepResult } from '../../_shared-ts/types'

interface ProvisionStorageRequest {
  /** Supabase project reference, e.g. jxxzdmbvazxfbhkittlm */
  projectRef: string
  /** Service role key (needed for admin Storage API) */
  serviceRoleKey: string
  /** Bucket IDs to create. Defaults to logos + menu-images. */
  buckets?: string[]
}

export async function onRequestPost(context: PagesContext): Promise<Response> {
  const body = (await context.request.json()) as ProvisionStorageRequest
  const { projectRef, serviceRoleKey, buckets = ['logos', 'menu-images'] } = body

  const results: StepResult[] = []
  const storageUrl = `https://${projectRef}.supabase.co/storage/v1`

  for (const id of buckets) {
    try {
      const response = await fetch(`${storageUrl}/bucket`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${serviceRoleKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          id,
          name: id,
          public: true,
          file_size_limit: 5 * 1024 * 1024, // 5 MB — generous for café logos and menu photos
          allowed_mime_types: ['image/jpeg', 'image/png', 'image/webp', 'image/gif'],
        }),
      })

      if (!response.ok) {
        const detail = await response.text().catch(() => `HTTP ${response.status}`)
        results.push({ step: `bucket:${id}`, status: 'error', detail })
      } else {
        results.push({ step: `bucket:${id}`, status: 'ok', detail: 'public, 5 MB, images only' })
      }
    } catch (e) {
      results.push({ step: `bucket:${id}`, status: 'error', detail: String(e) })
    }
  }

  return new Response(JSON.stringify({ results } satisfies ProvisionResponse), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
