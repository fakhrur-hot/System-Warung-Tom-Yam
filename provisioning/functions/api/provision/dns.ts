// Optional custom-domain DNS record — a single, well-documented Cloudflare API call, independent
// of the Pages git-integration work in pages.ts. Not gated by Requirement R8; safe to trust as-is.

import type { PagesContext, ProvisionResponse, StepResult } from '../../_shared-ts/types'

interface ProvisionDnsRequest {
  zoneId: string
  cloudflareApiToken: string
  recordName: string
  /** The <slug>.pages.dev target this record should point at. */
  target: string
}

export async function onRequestPost(context: PagesContext): Promise<Response> {
  const body = (await context.request.json()) as ProvisionDnsRequest
  const result = await createDnsRecord(body)
  return new Response(JSON.stringify({ results: [result] } satisfies ProvisionResponse), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

async function createDnsRecord(params: ProvisionDnsRequest): Promise<StepResult> {
  const { zoneId, cloudflareApiToken, recordName, target } = params
  try {
    const response = await fetch(`https://api.cloudflare.com/client/v4/zones/${zoneId}/dns_records`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${cloudflareApiToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        type: 'CNAME',
        name: recordName,
        content: target,
        proxied: true,
      }),
    })
    const data = (await response.json()) as { success: boolean; errors?: { message: string }[] }
    if (!response.ok || !data.success) {
      const message = data.errors?.map((e) => e.message).join('; ') || `HTTP ${response.status}`
      return { step: 'create-dns-record', status: 'error', detail: message }
    }
    return { step: 'create-dns-record', status: 'ok', detail: `${recordName} -> ${target}` }
  } catch (e) {
    return { step: 'create-dns-record', status: 'error', detail: String(e) }
  }
}
