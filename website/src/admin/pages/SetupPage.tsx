import { useCallback, useEffect, useState } from 'react'
import { getSupabase } from '../../lib/supabase'

interface RotatingKeyData {
  key: string
  expiresInSeconds: number
}

export default function SetupPage() {
  const [keyData, setKeyData] = useState<RotatingKeyData | null>(null)
  const [countdown, setCountdown] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetchKey = useCallback(async () => {
    setError(null)
    try {
      const supabase = getSupabase()
      const { data, error: fetchError } = await supabase.functions.invoke('rotating-key', {
        method: 'GET',
      })

      if (fetchError) {
        setError(fetchError.message || 'Failed to fetch key')
        return
      }

      setKeyData(data as RotatingKeyData)
      setCountdown(data.expiresInSeconds)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to fetch key')
    } finally {
      setLoading(false)
    }
  }, [])

  // Initial fetch
  useEffect(() => {
    fetchKey()
  }, [fetchKey])

  // Countdown timer
  useEffect(() => {
    if (countdown <= 0) return

    const timer = setInterval(() => {
      setCountdown((prev) => {
        if (prev <= 1) {
          // Auto-refresh key when countdown hits zero
          fetchKey()
          return 0
        }
        return prev - 1
      })
    }, 1000)

    return () => clearInterval(timer)
  }, [countdown, fetchKey])

  // Poll for device connection status every 5 seconds
  useEffect(() => {
    const pollInterval = setInterval(async () => {
      try {
        const supabase = getSupabase()
        const { data } = await supabase.functions.invoke('devices', {
          method: 'GET',
        })

        if (Array.isArray(data) && data.some((d: { role: string; status: string }) => d.role === 'ADMIN' && d.status === 'APPROVED')) {
          // Admin phone connected — reload to transition to dashboard
          window.location.reload()
        }
      } catch {
        // Silently ignore polling errors
      }
    }, 5000)

    return () => clearInterval(pollInterval)
  }, [])

  const progressPercentage = keyData ? ((30 - countdown) / 30) * 100 : 0

  return (
    <div className="flex min-h-[60vh] items-center justify-center px-4">
      <div className="w-full max-w-lg rounded-lg bg-white p-8 shadow-md">
        <div className="mb-6 text-center">
          <span className="mb-3 block text-4xl" aria-hidden="true">📱</span>
          <h1 className="text-xl font-bold text-emerald-800">Connect Your Admin Phone</h1>
          <p className="mt-2 text-sm text-gray-600">
            Open the Admin APK → Settings → Backend Connection and enter this key.
          </p>
        </div>

        {error && (
          <div className="mb-4 rounded-md bg-red-50 p-3 text-sm text-red-700" role="alert">
            {error}
            <button onClick={fetchKey} className="ml-2 underline">Retry</button>
          </div>
        )}

        {loading ? (
          <div className="flex justify-center py-8">
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-emerald-200 border-t-emerald-600" role="status" aria-label="Loading key" />
          </div>
        ) : keyData ? (
          <div className="space-y-4">
            {/* Key display */}
            <div className="rounded-lg bg-gray-50 p-6 text-center">
              <p className="mb-2 text-xs font-medium uppercase tracking-wider text-gray-500">Current Key</p>
              <p className="font-mono text-3xl font-bold tracking-[0.3em] text-emerald-700" aria-live="polite" aria-atomic="true">
                {keyData.key}
              </p>
            </div>

            {/* Countdown bar */}
            <div className="space-y-2">
              <div className="flex items-center justify-between text-xs text-gray-500">
                <span>Key expires in</span>
                <span className="font-mono font-medium">{countdown}s</span>
              </div>
              <div className="h-2 w-full overflow-hidden rounded-full bg-gray-200" role="progressbar" aria-valuenow={countdown} aria-valuemin={0} aria-valuemax={30} aria-label="Key expiry countdown">
                <div
                  className="h-full rounded-full bg-emerald-500 transition-all duration-1000 ease-linear"
                  style={{ width: `${100 - progressPercentage}%` }}
                />
              </div>
            </div>

            {/* Instructions */}
            <div className="rounded-md bg-emerald-50 p-4">
              <h2 className="mb-2 text-sm font-semibold text-emerald-800">Instructions:</h2>
              <ol className="list-inside list-decimal space-y-1 text-sm text-emerald-700">
                <li>Download and install the Admin APK on your phone</li>
                <li>Open the app and tap "Connect as Admin"</li>
                <li>Enter the backend URL and the key shown above</li>
                <li>Once connected, this page will update automatically</li>
              </ol>
            </div>
          </div>
        ) : null}
      </div>
    </div>
  )
}
