import { useEffect, useState } from 'react'
import { provisionApi } from './api-client'
import type { StepResult, WizardState } from './types'
import { EMPTY_WIZARD_STATE } from './types'

type StepKey = 'schema' | 'functions' | 'secrets' | 'storage' | 'auth' | 'pages' | 'dns'

interface StepDef {
  key: StepKey
  title: string
  description: string
  /** Whether this step's own inputs are filled enough to attempt it. */
  ready: (s: WizardState) => boolean
  verified: boolean
}

const STEPS: StepDef[] = [
  {
    key: 'schema',
    title: '1. Apply database schema',
    description: 'Runs supabase/migrations/*.sql against your Postgres connection string.',
    ready: (s) => !!s.supabaseConnectionString,
    verified: false,
  },
  {
    key: 'functions',
    title: '2. Deploy Edge Functions',
    description: 'Deploys all 26 backend functions using your Supabase Personal Access Token.',
    ready: (s) => !!s.supabasePersonalAccessToken && !!s.supabaseProjectRef,
    verified: false,
  },
  {
    key: 'secrets',
    title: '3. Set Edge Function secrets',
    description: 'Writes BREVO_API_KEY and WEBSITE_ORIGIN so the deployed functions can send email and build redirects.',
    ready: (s) =>
      !!s.supabasePersonalAccessToken && !!s.supabaseProjectRef && !!s.brevoApiKey && !!s.websiteUrl,
    verified: false,
  },
  {
    key: 'storage',
    title: '4. Create public Storage buckets',
    description: 'Creates logos and menu-images buckets using the service role key.',
    ready: (s) => !!s.supabaseProjectRef && !!s.supabaseServiceRoleKey,
    verified: false,
  },
  {
    key: 'auth',
    title: '5. Configure Auth URLs',
    description: 'Sets the Supabase Auth site URL and redirect URLs to the café website.',
    ready: (s) => !!s.supabasePersonalAccessToken && !!s.supabaseProjectRef && !!s.websiteUrl,
    verified: false,
  },
  {
    key: 'pages',
    title: '6. Create the ordering website',
    description: 'Creates a Cloudflare Pages project for your café — no GitHub account needed.',
    ready: (s) =>
      !!s.cloudflareAccountId && !!s.cloudflareApiToken && !!s.cafeSlug && !!s.supabaseAnonKey,
    verified: true,
  },
  {
    key: 'dns',
    title: '7. Point your domain (optional)',
    description: 'Only needed if you want a custom domain instead of <slug>.pages.dev.',
    ready: (s) => !!s.cloudflareZoneId && !!s.customDomain && !!s.cloudflareApiToken,
    verified: true,
  },
]

export default function App() {
  // Everything lives in plain component state — nothing here is ever written to
  // localStorage/sessionStorage/cookies. Reloading this page starts over with a blank form by
  // design (see design.md Correctness Property 1).
  const [state, setState] = useState<WizardState>(EMPTY_WIZARD_STATE)
  const [results, setResults] = useState<Partial<Record<StepKey, StepResult[]>>>({})
  const [running, setRunning] = useState<StepKey | null>(null)
  const [websiteUrlTouched, setWebsiteUrlTouched] = useState(false)

  // Derive the public website URL from the slug or custom domain. It can be overridden until the
  // user edits it, after which we leave their value alone so a custom Pages URL can be entered.
  useEffect(() => {
    if (!websiteUrlTouched) {
      setState((prev) => ({
        ...prev,
        websiteUrl:
          prev.customDomain || (prev.cafeSlug ? `https://${prev.cafeSlug}.pages.dev` : ''),
      }))
    }
  }, [state.cafeSlug, state.customDomain, websiteUrlTouched])

  function set<K extends keyof WizardState>(key: K, value: WizardState[K]) {
    if (key === 'websiteUrl' && value !== state.websiteUrl) {
      setWebsiteUrlTouched(true)
    }
    setState((prev) => ({ ...prev, [key]: value }))
  }

  async function runStep(key: StepKey) {
    setRunning(key)
    try {
      const response = await provisionApi[key](state)
      setResults((prev) => ({ ...prev, [key]: response.results }))
    } finally {
      setRunning(null)
    }
  }

  const pagesSucceeded = results.pages?.every((r) => r.status === 'ok') ?? false

  return (
    <div className="wizard">
      <header>
        <h1>Café Setup Wizard</h1>
        <p className="muted">
          Provision your own Supabase + Cloudflare accounts for this POS. Nothing you enter below
          is ever saved anywhere — it lives only in this browser tab and is discarded the moment
          each step's request completes.
        </p>
      </header>

      <fieldset>
        <legend>Supabase</legend>
        <Field
          label="Project reference"
          hint="Project Settings → General → Reference ID"
          value={state.supabaseProjectRef}
          onChange={(v) => set('supabaseProjectRef', v)}
        />
        <Field
          label="Anon (public) key"
          hint="Project Settings → API → anon public"
          secret
          value={state.supabaseAnonKey}
          onChange={(v) => set('supabaseAnonKey', v)}
        />
        <Field
          label="Service role key"
          hint="Project Settings → API → service role secret — used once to create Storage buckets"
          secret
          value={state.supabaseServiceRoleKey}
          onChange={(v) => set('supabaseServiceRoleKey', v)}
        />
        <Field
          label="Postgres connection string"
          hint="Project Settings → Database → Connection string (URI) — used once, then discarded"
          secret
          value={state.supabaseConnectionString}
          onChange={(v) => set('supabaseConnectionString', v)}
        />
        <Field
          label="Personal Access Token"
          hint="account.supabase.com/account/tokens — used once, then discarded"
          secret
          value={state.supabasePersonalAccessToken}
          onChange={(v) => set('supabasePersonalAccessToken', v)}
        />
        <Field
          label="Brevo API key"
          hint=" brevo.com → SMTP & API → API keys — stored as BREVO_API_KEY in Edge Function secrets"
          secret
          value={state.brevoApiKey}
          onChange={(v) => set('brevoApiKey', v)}
        />
      </fieldset>

      <fieldset>
        <legend>Cloudflare</legend>
        <Field
          label="Account ID"
          hint="Any Cloudflare dashboard page's right sidebar"
          value={state.cloudflareAccountId}
          onChange={(v) => set('cloudflareAccountId', v)}
        />
        <Field
          label="API token"
          hint="My Profile → API Tokens — needs Pages:Edit (+ DNS:Edit if using a custom domain)"
          secret
          value={state.cloudflareApiToken}
          onChange={(v) => set('cloudflareApiToken', v)}
        />
        <Field
          label="Zone ID (optional)"
          hint="Only needed for a custom domain — shown on that domain's Overview page"
          value={state.cloudflareZoneId}
          onChange={(v) => set('cloudflareZoneId', v)}
        />
        <Field
          label="Custom domain (optional)"
          hint="e.g. order.yourcafe.com — leave blank to use <slug>.pages.dev"
          value={state.customDomain}
          onChange={(v) => set('customDomain', v)}
        />
      </fieldset>

      <fieldset>
        <legend>Your café</legend>
        <Field
          label="Café slug"
          hint="Becomes the Pages project name → <slug>.pages.dev"
          value={state.cafeSlug}
          onChange={(v) => set('cafeSlug', v)}
        />
        <Field label="Café name" value={state.cafeName} onChange={(v) => set('cafeName', v)} />
        <Field
          label="Website URL"
          hint="Used for Auth redirects and Edge Function secrets. Fills in from the slug or custom domain."
          value={state.websiteUrl}
          onChange={(v) => set('websiteUrl', v)}
        />
      </fieldset>

      <section className="checklist">
        <h2>Provisioning steps</h2>
        {STEPS.map((step) => (
          <ChecklistRow
            key={step.key}
            step={step}
            state={state}
            running={running === step.key}
            results={results[step.key]}
            onRun={() => runStep(step.key)}
          />
        ))}
      </section>

      {pagesSucceeded && (
        <section className="handoff">
          <h2>Done — set up the tablet</h2>
          <p className="muted">
            Open the Setup screen on the POS tablet (three-dots menu on the login page) and enter:
          </p>
          <dl>
            <dt>Supabase URL</dt>
            <dd>https://{state.supabaseProjectRef}.supabase.co</dd>
            <dt>Supabase anon key</dt>
            <dd>{state.supabaseAnonKey}</dd>
            <dt>Website URL</dt>
            <dd>{state.websiteUrl}</dd>
            <dt>Café name</dt>
            <dd>{state.cafeName}</dd>
          </dl>
        </section>
      )}
    </div>
  )
}

function Field(props: {
  label: string
  hint?: string
  secret?: boolean
  value: string
  onChange: (value: string) => void
}) {
  return (
    <label className="field">
      <span className="field-label">{props.label}</span>
      <input
        type={props.secret ? 'password' : 'text'}
        autoComplete="off"
        value={props.value}
        onChange={(e) => props.onChange(e.target.value)}
      />
      {props.hint && <span className="field-hint">{props.hint}</span>}
    </label>
  )
}

function ChecklistRow(props: {
  step: StepDef
  state: WizardState
  running: boolean
  results?: StepResult[]
  onRun: () => void
}) {
  const { step, state, running, results, onRun } = props
  const ready = step.ready(state)
  return (
    <div className="checklist-row">
      <div className="checklist-row-header">
        <div>
          <strong>{step.title}</strong>
          {!step.verified && <span className="badge-unverified">Unverified — see README</span>}
          <p className="muted">{step.description}</p>
        </div>
        <button disabled={!ready || running} onClick={onRun}>
          {running ? 'Running…' : 'Run'}
        </button>
      </div>
      {results && (
        <ul className="results">
          {results.map((r, i) => (
            <li key={i} className={`result-${r.status}`}>
              <span className="result-step">{r.step}</span>
              {r.detail && <span className="result-detail">{r.detail}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
