// Smoke test for /api/provision/run: exercises request parsing and the first live API call,
// expecting a controlled failure (fake PAT). Proves the orchestrator wires up without disposable
// real credentials being needed.

import { onRequestPost } from '../functions/api/provision/run.ts'

const body = {
  supabase: {
    mode: 'new',
    personalAccessToken: 'sbp_fake_test_token_that_will_fail',
    orgId: 'fake-org',
    region: 'ap-southeast-1',
    projectName: `smoke-test-${Date.now()}`,
  },
  cloudflare: {
    mode: 'new',
    accountId: 'fake-account',
    apiToken: 'fake-token',
    cafeSlug: `smoke-test-${Date.now()}`,
  },
  cafe: {
    cafeName: 'Smoke Test Café',
  },
}

const response = await onRequestPost({
  request: new Request('http://localhost/api/provision/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }),
  env: {
    RAZSTUDIO_GITHUB_OWNER: 'fake-owner',
    RAZSTUDIO_GITHUB_REPO: 'fake-repo',
  },
})

const data = await response.json()
console.log(JSON.stringify(data, null, 2))

// We expect the first step to fail because the Supabase PAT is fake. The orchestrator must catch it
// and return a structured response with an error result.
const firstError = data.results?.find((r) => r.status === 'error')
if (!firstError) {
  console.error('Expected at least one error result due to fake PAT, but none found.')
  process.exit(1)
}

if (!firstError.step.includes('supabase') && !firstError.step.includes('create-supabase-project')) {
  console.error(`Expected failure at Supabase project creation, but got step: ${firstError.step}`)
  process.exit(1)
}

console.log('\nSmoke test passed: orchestrator parses request and reports structured errors.')
