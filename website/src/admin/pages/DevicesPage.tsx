import { useCallback, useEffect, useState } from 'react'
import { getSupabase } from '../../lib/supabase'

interface Device {
  id: string
  label: string
  role: 'ADMIN' | 'ADMIN_SECONDARY' | 'ORDERING'
  status: 'PENDING' | 'APPROVED' | 'REVOKED'
  lastSeenAt: string | null
  isCheckedIn: boolean
}

type DeviceAction = 'RENAME' | 'FORCE_SIGNOUT' | 'REVOKE' | 'FORCE_CHECKOUT'

export default function DevicesPage() {
  const [devices, setDevices] = useState<Device[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [actionLoading, setActionLoading] = useState<string | null>(null)
  const [renameId, setRenameId] = useState<string | null>(null)
  const [renameLabel, setRenameLabel] = useState('')

  const fetchDevices = useCallback(async () => {
    setError(null)
    try {
      const supabase = getSupabase()
      const { data, error: fetchError } = await supabase.functions.invoke('devices', {
        method: 'GET',
      })

      if (fetchError) {
        setError(fetchError.message || 'Failed to fetch devices')
        return
      }

      setDevices(data as Device[])
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to fetch devices')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchDevices()
  }, [fetchDevices])

  const handleAction = async (deviceId: string, action: DeviceAction, label?: string) => {
    setActionLoading(deviceId)
    try {
      const supabase = getSupabase()
      const body: { action: string; label?: string } = { action }
      if (label) body.label = label

      const { error: patchError } = await supabase.functions.invoke(`devices/${deviceId}`, {
        method: 'PATCH',
        body,
      })

      if (patchError) {
        setError(patchError.message || `Failed to ${action.toLowerCase()} device`)
      } else {
        setRenameId(null)
        await fetchDevices()
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : `Action failed`)
    } finally {
      setActionLoading(null)
    }
  }

  const startRename = (device: Device) => {
    setRenameId(device.id)
    setRenameLabel(device.label)
  }

  const confirmRename = (deviceId: string) => {
    if (renameLabel.trim()) {
      handleAction(deviceId, 'RENAME', renameLabel.trim())
    }
  }

  const getRoleBadgeClasses = (role: string) => {
    if (role === 'ADMIN') return 'bg-purple-100 text-purple-700'
    if (role === 'ADMIN_SECONDARY') return 'bg-indigo-100 text-indigo-700'
    return 'bg-blue-100 text-blue-700'
  }

  const getRoleLabel = (role: string) => {
    if (role === 'ADMIN') return 'Main Admin'
    if (role === 'ADMIN_SECONDARY') return 'Secondary Admin'
    return role
  }

  const getStatusBadge = (device: Device) => {
    if (device.status === 'REVOKED') {
      return <span className="rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-700">Revoked</span>
    }
    if (device.status === 'PENDING') {
      return <span className="rounded-full bg-yellow-100 px-2 py-0.5 text-xs font-medium text-yellow-700">Pending</span>
    }
    // Determine online/offline by last seen (30 min threshold)
    if (device.lastSeenAt) {
      const lastSeen = new Date(device.lastSeenAt).getTime()
      const isOnline = Date.now() - lastSeen < 30 * 60 * 1000
      if (isOnline) {
        return <span className="rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700">Online</span>
      }
    }
    return <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">Offline</span>
  }

  if (loading) {
    return (
      <div className="flex min-h-[40vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-emerald-200 border-t-emerald-600" role="status" aria-label="Loading devices" />
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Devices</h1>
        <button
          onClick={fetchDevices}
          className="rounded-md bg-emerald-50 px-3 py-1.5 text-sm font-medium text-emerald-700 transition-colors hover:bg-emerald-100"
          aria-label="Refresh device list"
        >
          ↻ Refresh
        </button>
      </div>

      {error && (
        <div className="rounded-md bg-red-50 p-3 text-sm text-red-700" role="alert">
          {error}
        </div>
      )}

      {devices.length === 0 ? (
        <div className="rounded-lg border border-gray-200 bg-white p-8 text-center">
          <span className="mb-3 block text-4xl" aria-hidden="true">📱</span>
          <p className="text-sm text-gray-500">No devices registered yet.</p>
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm">
          <ul className="divide-y divide-gray-100" role="list" aria-label="Registered devices">
            {devices.map((device) => (
              <li key={device.id} className="px-4 py-4 sm:px-6">
                <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                  {/* Device info */}
                  <div className="flex items-center gap-3">
                    <div>
                      {renameId === device.id ? (
                        <div className="flex items-center gap-2">
                          <input
                            type="text"
                            value={renameLabel}
                            onChange={(e) => setRenameLabel(e.target.value)}
                            className="rounded-md border border-gray-300 px-2 py-1 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                            aria-label="New device label"
                            onKeyDown={(e) => {
                              if (e.key === 'Enter') confirmRename(device.id)
                              if (e.key === 'Escape') setRenameId(null)
                            }}
                            autoFocus
                          />
                          <button
                            onClick={() => confirmRename(device.id)}
                            className="rounded bg-emerald-600 px-2 py-1 text-xs text-white hover:bg-emerald-700"
                          >
                            Save
                          </button>
                          <button
                            onClick={() => setRenameId(null)}
                            className="rounded bg-gray-200 px-2 py-1 text-xs text-gray-700 hover:bg-gray-300"
                          >
                            Cancel
                          </button>
                        </div>
                      ) : (
                        <p className="text-sm font-medium text-gray-900">{device.label}</p>
                      )}
                      <div className="mt-1 flex items-center gap-2">
                        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${getRoleBadgeClasses(device.role)}`}>
                          {getRoleLabel(device.role)}
                        </span>
                        {getStatusBadge(device)}
                        {device.isCheckedIn && (
                          <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-700">Checked In</span>
                        )}
                      </div>
                      {device.lastSeenAt && (
                        <p className="mt-1 text-xs text-gray-400">
                          Last seen: {new Date(device.lastSeenAt).toLocaleString()}
                        </p>
                      )}
                    </div>
                  </div>

                  {/* Actions */}
                  {device.status === 'APPROVED' && (
                    <div className="flex flex-wrap gap-2">
                      <button
                        onClick={() => startRename(device)}
                        disabled={actionLoading === device.id}
                        className="rounded-md border border-gray-300 px-3 py-1.5 text-xs font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-50"
                      >
                        Rename
                      </button>
                      <button
                        onClick={() => handleAction(device.id, 'FORCE_SIGNOUT')}
                        disabled={actionLoading === device.id}
                        className="rounded-md border border-amber-300 px-3 py-1.5 text-xs font-medium text-amber-700 transition-colors hover:bg-amber-50 disabled:opacity-50"
                      >
                        Force Sign Out
                      </button>
                      <button
                        onClick={() => {
                          if (window.confirm('Are you sure you want to deregister this device? This action cannot be undone.')) {
                            handleAction(device.id, 'REVOKE')
                          }
                        }}
                        disabled={actionLoading === device.id}
                        className="rounded-md border border-red-300 px-3 py-1.5 text-xs font-medium text-red-700 transition-colors hover:bg-red-50 disabled:opacity-50"
                      >
                        Deregister
                      </button>
                      {device.role === 'ORDERING' && device.isCheckedIn && (
                        <button
                          onClick={() => handleAction(device.id, 'FORCE_CHECKOUT')}
                          disabled={actionLoading === device.id}
                          className="rounded-md border border-orange-300 px-3 py-1.5 text-xs font-medium text-orange-700 transition-colors hover:bg-orange-50 disabled:opacity-50"
                        >
                          Force Check-Out
                        </button>
                      )}
                    </div>
                  )}
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
