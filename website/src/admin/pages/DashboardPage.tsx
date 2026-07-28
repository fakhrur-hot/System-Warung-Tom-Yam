import { useCallback, useEffect, useState } from 'react'
import { getSupabase } from '../../lib/supabase'
import SetupPage from './SetupPage'

interface MetricsData {
  orders: number
  revenue: number
  openHours: number
}

interface MonthlyMetrics {
  month: string
  orders: number
  revenue: number
  openHours: number
}

type Period = 'today' | 'week' | 'month' | 'last_month' | 'monthly'

interface DeviceInfo {
  id: string
  role: string
  status: string
  lastSeenAt: string | null
}

export default function DashboardPage() {
  const [metrics, setMetrics] = useState<Record<string, MetricsData | null>>({})
  const [monthlyMetrics, setMonthlyMetrics] = useState<MonthlyMetrics[]>([])
  const [loading, setLoading] = useState(true)
  const [adminOnline, setAdminOnline] = useState<boolean | null>(null)
  const [hasAdmin, setHasAdmin] = useState<boolean | null>(null)

  const fetchMetrics = useCallback(async () => {
    const supabase = getSupabase()
    const periods: Period[] = ['today', 'week', 'month', 'last_month', 'monthly']

    try {
      const results = await Promise.allSettled(
        periods.map((period) =>
          supabase.functions.invoke(`metrics?period=${period}`, { method: 'GET' })
        )
      )

      const newMetrics: Record<string, MetricsData | null> = {}
      results.forEach((result, i) => {
        if (result.status === 'fulfilled' && result.value.data) {
          if (periods[i] === 'monthly') {
            setMonthlyMetrics(result.value.data as MonthlyMetrics[])
          } else {
            newMetrics[periods[i]] = result.value.data as MetricsData
          }
        } else {
          if (periods[i] !== 'monthly') {
            newMetrics[periods[i]] = null
          }
        }
      })
      setMetrics(newMetrics)
    } catch {
      // Metrics fetch failure is non-critical
    } finally {
      setLoading(false)
    }
  }, [])

  const checkAdminStatus = useCallback(async () => {
    try {
      const supabase = getSupabase()
      const { data } = await supabase.functions.invoke('devices', { method: 'GET' })

      if (Array.isArray(data)) {
        const adminDevice = data.find((d: DeviceInfo) => d.role === 'ADMIN' && d.status === 'APPROVED')
        if (adminDevice) {
          setHasAdmin(true)
          // Check if admin is "online" (last seen within 30 minutes)
          if (adminDevice.lastSeenAt) {
            const lastSeen = new Date(adminDevice.lastSeenAt).getTime()
            const thirtyMinAgo = Date.now() - 30 * 60 * 1000
            setAdminOnline(lastSeen > thirtyMinAgo)
          } else {
            setAdminOnline(false)
          }
        } else {
          setHasAdmin(false)
        }
      }
    } catch {
      // Silent fail — we'll just hide the status
    }
  }, [])

  useEffect(() => {
    checkAdminStatus()
  }, [checkAdminStatus])

  useEffect(() => {
    if (hasAdmin) {
      fetchMetrics()
    }
  }, [hasAdmin, fetchMetrics])

  // Show setup page if no admin phone connected
  if (hasAdmin === false) {
    return <SetupPage />
  }

  // Still checking admin status
  if (hasAdmin === null) {
    return (
      <div className="flex min-h-[40vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-emerald-200 border-t-emerald-600" role="status" aria-label="Loading" />
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Dashboard</h1>
        <button
          onClick={fetchMetrics}
          className="rounded-md bg-emerald-50 px-3 py-1.5 text-sm font-medium text-emerald-700 transition-colors hover:bg-emerald-100"
          aria-label="Refresh metrics"
        >
          ↻ Refresh
        </button>
      </div>

      {/* Admin offline banner */}
      {adminOnline === false && (
        <div className="rounded-md bg-amber-50 border border-amber-200 p-4" role="alert">
          <div className="flex items-center gap-2">
            <span aria-hidden="true">⚠️</span>
            <p className="text-sm font-medium text-amber-800">
              Admin phone offline — no session activity detected in the last 30 minutes.
            </p>
          </div>
        </div>
      )}

      {loading ? (
        <div className="flex min-h-[30vh] items-center justify-center">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-emerald-200 border-t-emerald-600" role="status" aria-label="Loading metrics" />
        </div>
      ) : (
        <>
          {/* Period metrics grid */}
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <MetricCard title="Today" data={metrics.today ?? null} />
            <MetricCard title="This Week" data={metrics.week ?? null} />
            <MetricCard title="This Month" data={metrics.month ?? null} />
            <MetricCard title="Last Month" data={metrics.last_month ?? null} />
          </div>

          {/* Monthly breakdown */}
          {monthlyMetrics.length > 0 && (
            <div>
              <h2 className="mb-3 text-lg font-semibold text-gray-900">Monthly Breakdown (up to 12 months)</h2>
              <div className="overflow-x-auto rounded-lg border border-gray-200">
                <table className="w-full text-sm">
                  <thead className="bg-gray-50">
                    <tr>
                      <th className="px-4 py-3 text-left font-medium text-gray-600">Month</th>
                      <th className="px-4 py-3 text-right font-medium text-gray-600">Orders</th>
                      <th className="px-4 py-3 text-right font-medium text-gray-600">Revenue (RM)</th>
                      <th className="px-4 py-3 text-right font-medium text-gray-600">Open Hours</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {monthlyMetrics.map((m) => (
                      <tr key={m.month} className="hover:bg-gray-50">
                        <td className="px-4 py-3 font-medium text-gray-900">{m.month}</td>
                        <td className="px-4 py-3 text-right text-gray-700">{m.orders}</td>
                        <td className="px-4 py-3 text-right text-gray-700">{m.revenue.toFixed(2)}</td>
                        <td className="px-4 py-3 text-right text-gray-700">{m.openHours.toFixed(1)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  )
}

function MetricCard({ title, data }: { title: string; data: MetricsData | null }) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white p-5 shadow-sm">
      <h3 className="mb-3 text-sm font-medium text-gray-500">{title}</h3>
      {data ? (
        <dl className="space-y-2">
          <div className="flex items-center justify-between">
            <dt className="text-xs text-gray-500">Orders</dt>
            <dd className="text-lg font-bold text-gray-900">{data.orders}</dd>
          </div>
          <div className="flex items-center justify-between">
            <dt className="text-xs text-gray-500">Revenue</dt>
            <dd className="text-lg font-bold text-emerald-700">RM {data.revenue.toFixed(2)}</dd>
          </div>
          <div className="flex items-center justify-between">
            <dt className="text-xs text-gray-500">Open Hours</dt>
            <dd className="text-lg font-bold text-gray-900">{data.openHours.toFixed(1)}h</dd>
          </div>
        </dl>
      ) : (
        <p className="text-sm text-gray-400">No data available</p>
      )}
    </div>
  )
}
