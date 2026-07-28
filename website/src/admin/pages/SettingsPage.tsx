import { FormEvent, useCallback, useEffect, useState } from 'react'
import { getSupabase } from '../../lib/supabase'

interface Settings {
  printLanguage: string
  timezone: string
  topN: number
  staffCanSendKitchen: boolean
  staffCanTakePayment: boolean
  reportEmail: string
  autoSendClosingReport: boolean
}

export default function SettingsPage() {
  const [settings, setSettings] = useState<Settings | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveSuccess, setSaveSuccess] = useState(false)

  // Deregister state
  const [showDeregister, setShowDeregister] = useState(false)
  const [deregPassword, setDeregPassword] = useState('')
  const [deregLoading, setDeregLoading] = useState(false)
  const [deregError, setDeregError] = useState<string | null>(null)

  // Monthly report state
  const [reportMonth, setReportMonth] = useState(() => {
    const now = new Date()
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
  })
  const [reportLoading, setReportLoading] = useState(false)
  const [reportResult, setReportResult] = useState<string | null>(null)

  const fetchSettings = useCallback(async () => {
    setError(null)
    try {
      const supabase = getSupabase()
      const { data, error: fetchError } = await supabase.functions.invoke('settings', {
        method: 'GET',
      })

      if (fetchError) {
        setError(fetchError.message || 'Failed to load settings')
        return
      }

      setSettings(data as Settings)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to load settings')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchSettings()
  }, [fetchSettings])

  const handleSave = async (e: FormEvent) => {
    e.preventDefault()
    if (!settings) return

    setSaving(true)
    setSaveSuccess(false)
    setError(null)

    try {
      const supabase = getSupabase()
      const { error: saveError } = await supabase.functions.invoke('settings', {
        method: 'PUT',
        body: {
          topN: settings.topN,
          autoSendClosingReport: settings.autoSendClosingReport,
          reportEmail: settings.reportEmail,
        },
      })

      if (saveError) {
        setError(saveError.message || 'Failed to save settings')
      } else {
        setSaveSuccess(true)
        setTimeout(() => setSaveSuccess(false), 3000)
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to save settings')
    } finally {
      setSaving(false)
    }
  }

  const handleDeregister = async () => {
    setDeregError(null)
    setDeregLoading(true)

    try {
      const supabase = getSupabase()
      // Re-authenticate to confirm password
      const { data: { user } } = await supabase.auth.getUser()
      if (!user?.email) {
        setDeregError('Could not verify user.')
        return
      }

      const { error: authError } = await supabase.auth.signInWithPassword({
        email: user.email,
        password: deregPassword,
      })

      if (authError) {
        setDeregError('Incorrect password.')
        return
      }

      // Find and deregister the admin device
      const { data: devices } = await supabase.functions.invoke('devices', { method: 'GET' })
      const adminDevice = Array.isArray(devices)
        ? devices.find((d: { role: string; status: string }) => d.role === 'ADMIN' && d.status === 'APPROVED')
        : null

      if (adminDevice) {
        await supabase.functions.invoke(`devices/${adminDevice.id}`, {
          method: 'PATCH',
          body: { action: 'REVOKE' },
        })
      }

      // Reload to show setup page
      window.location.reload()
    } catch (err: unknown) {
      setDeregError(err instanceof Error ? err.message : 'Failed to deregister')
    } finally {
      setDeregLoading(false)
    }
  }

  const handleGenerateReport = async () => {
    setReportLoading(true)
    setReportResult(null)

    try {
      const supabase = getSupabase()
      const { data, error: reportError } = await supabase.functions.invoke(
        `reports/monthly?month=${reportMonth}`,
        { method: 'GET' }
      )

      if (reportError) {
        setReportResult(`Error: ${reportError.message}`)
      } else if (data?.reportUrl) {
        setReportResult('Report generated successfully. Check your email.')
        window.open(data.reportUrl, '_blank')
      } else {
        setReportResult('Report generated.')
      }
    } catch (err: unknown) {
      setReportResult(`Error: ${err instanceof Error ? err.message : 'Failed to generate report'}`)
    } finally {
      setReportLoading(false)
    }
  }

  if (loading) {
    return (
      <div className="flex min-h-[40vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-emerald-200 border-t-emerald-600" role="status" aria-label="Loading settings" />
      </div>
    )
  }

  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-bold text-gray-900">Settings</h1>

      {error && (
        <div className="rounded-md bg-red-50 p-3 text-sm text-red-700" role="alert">
          {error}
        </div>
      )}

      {/* Download APK */}
      <section className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
        <h2 className="mb-2 text-lg font-semibold text-gray-900">Download APK</h2>
        <p className="mb-4 text-sm text-gray-500">
          Download and install the admin APK on your Android phone.
        </p>
        <a
          href="https://github.com/nicholasgasior/warung-tom-yam/releases/latest"
          target="_blank"
          rel="noopener noreferrer"
          className="inline-block rounded-md bg-emerald-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-emerald-700"
        >
          Download Latest APK ↗
        </a>
      </section>

      {/* Deregister Admin Phone */}
      <section className="rounded-lg border border-red-200 bg-white p-6 shadow-sm">
        <h2 className="mb-2 text-lg font-semibold text-gray-900">Deregister Admin Phone</h2>
        <p className="mb-4 text-sm text-gray-500">
          Revoke the admin phone's session and allow a new phone to connect. Use this if your phone is lost or broken.
        </p>

        {!showDeregister ? (
          <button
            onClick={() => setShowDeregister(true)}
            className="rounded-md border border-red-300 px-4 py-2 text-sm font-medium text-red-700 transition-colors hover:bg-red-50"
          >
            Deregister Admin Phone
          </button>
        ) : (
          <div className="space-y-3">
            {deregError && (
              <div className="rounded-md bg-red-50 p-2 text-sm text-red-700" role="alert">
                {deregError}
              </div>
            )}
            <div>
              <label htmlFor="deregPassword" className="mb-1 block text-sm font-medium text-gray-700">
                Confirm your password
              </label>
              <input
                id="deregPassword"
                type="password"
                value={deregPassword}
                onChange={(e) => setDeregPassword(e.target.value)}
                className="w-full max-w-xs rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-red-500 focus:outline-none focus:ring-1 focus:ring-red-500"
                placeholder="Enter your password"
              />
            </div>
            <div className="flex gap-2">
              <button
                onClick={handleDeregister}
                disabled={!deregPassword || deregLoading}
                className="rounded-md bg-red-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {deregLoading ? 'Deregistering...' : 'Confirm Deregister'}
              </button>
              <button
                onClick={() => { setShowDeregister(false); setDeregPassword(''); setDeregError(null) }}
                className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
              >
                Cancel
              </button>
            </div>
          </div>
        )}
      </section>

      {/* Closing Report & Settings */}
      {settings && (
        <section className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
          <h2 className="mb-4 text-lg font-semibold text-gray-900">Report Settings</h2>
          <form onSubmit={handleSave} className="space-y-4">
            {/* Auto-send closing report toggle */}
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm font-medium text-gray-900">Auto-send Closing Report</p>
                <p className="text-xs text-gray-500">Automatically email the closing report on Sign Out with Closing</p>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={settings.autoSendClosingReport}
                onClick={() => setSettings({ ...settings, autoSendClosingReport: !settings.autoSendClosingReport })}
                className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
                  settings.autoSendClosingReport ? 'bg-emerald-600' : 'bg-gray-200'
                }`}
              >
                <span
                  className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                    settings.autoSendClosingReport ? 'translate-x-6' : 'translate-x-1'
                  }`}
                />
              </button>
            </div>

            {/* Report email */}
            <div>
              <label htmlFor="reportEmail" className="mb-1 block text-sm font-medium text-gray-700">
                Report Email
              </label>
              <input
                id="reportEmail"
                type="email"
                value={settings.reportEmail}
                onChange={(e) => setSettings({ ...settings, reportEmail: e.target.value })}
                className="w-full max-w-sm rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                placeholder="owner@example.com"
              />
            </div>

            {/* Top-N input */}
            <div>
              <label htmlFor="topN" className="mb-1 block text-sm font-medium text-gray-700">
                Top Items Count (1–20)
              </label>
              <input
                id="topN"
                type="number"
                min={1}
                max={20}
                value={settings.topN}
                onChange={(e) => {
                  const val = Math.max(1, Math.min(20, Number(e.target.value) || 5))
                  setSettings({ ...settings, topN: val })
                }}
                className="w-24 rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
              />
              <p className="mt-1 text-xs text-gray-500">
                Number of top items shown per category in reports.
              </p>
            </div>

            {/* Save button */}
            <div className="flex items-center gap-3">
              <button
                type="submit"
                disabled={saving}
                className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {saving ? 'Saving...' : 'Save Settings'}
              </button>
              {saveSuccess && (
                <span className="text-sm text-emerald-600">✓ Saved</span>
              )}
            </div>
          </form>
        </section>
      )}

      {/* Monthly Report */}
      <section className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
        <h2 className="mb-2 text-lg font-semibold text-gray-900">Monthly Report</h2>
        <p className="mb-4 text-sm text-gray-500">
          Generate and download/email a monthly report.
        </p>
        <div className="flex items-end gap-3">
          <div>
            <label htmlFor="reportMonth" className="mb-1 block text-sm font-medium text-gray-700">
              Month
            </label>
            <input
              id="reportMonth"
              type="month"
              value={reportMonth}
              onChange={(e) => setReportMonth(e.target.value)}
              className="rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
            />
          </div>
          <button
            onClick={handleGenerateReport}
            disabled={reportLoading}
            className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {reportLoading ? 'Generating...' : 'Generate Report'}
          </button>
        </div>
        {reportResult && (
          <p className={`mt-3 text-sm ${reportResult.startsWith('Error') ? 'text-red-600' : 'text-emerald-600'}`}>
            {reportResult}
          </p>
        )}
      </section>
    </div>
  )
}
