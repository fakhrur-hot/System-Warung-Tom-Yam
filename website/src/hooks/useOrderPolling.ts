import { useState, useRef, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { getSupabase } from '../lib/supabase'

// ─── Types ───────────────────────────────────────────────────────────────────

type OrderStatus = 'RECEIVED' | 'SENT_TO_KITCHEN' | 'PREPARING' | 'READY' | 'COMPLETED' | 'CANCELLED'

// ─── Constants ───────────────────────────────────────────────────────────────

const POLL_INTERVAL_ACTIVE_MS = 5_000   // RECEIVED, SENT_TO_KITCHEN, PREPARING
const POLL_INTERVAL_READY_MS  = 10_000  // READY

// ─── Helpers ─────────────────────────────────────────────────────────────────

/**
 * Returns the poll interval in milliseconds for a given order status.
 * Returns `null` for terminal statuses (COMPLETED, CANCELLED) or null input,
 * which signals that polling should not be scheduled.
 */
function getIntervalMs(s: OrderStatus | null): number | null {
  if (s === 'READY') return POLL_INTERVAL_READY_MS
  if (s === 'RECEIVED' || s === 'SENT_TO_KITCHEN' || s === 'PREPARING') return POLL_INTERVAL_ACTIVE_MS
  return null // Terminal status or null → do not schedule
}

// ─── Hook ────────────────────────────────────────────────────────────────────

export function useOrderPolling(
  tableId: string,
  browserId: string,
  initialStatus: OrderStatus,
  onTableNotFound: () => void,
): {
  status: OrderStatus | null
  pollingError: string | null
} {
  const { t } = useTranslation()
  const [status, setStatus] = useState<OrderStatus | null>(initialStatus)
  const [pollingError, setPollingError] = useState<string | null>(null)

  // ─── Internal refs ───────────────────────────────────────────────────────

  /** Mirrors `status` state for stale-closure-safe reads inside `poll()` */
  const statusRef = useRef<OrderStatus | null>(initialStatus)

  /** Holds the active interval ID so it can be cleared from any closure */
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null)

  /** Set `true` on terminal status — prevents tab-resume from restarting polling */
  const stoppedRef = useRef<boolean>(false)

  /** Set `false` on unmount — guards state updates against in-flight responses after unmount */
  const mountedRef = useRef<boolean>(false)

  // Mirror status state into statusRef on every render
  statusRef.current = status

  // ─── browserId guard ─────────────────────────────────────────────────────

  // If browserId is empty, set pollingError immediately and do not start any interval.
  // This runs synchronously before the useEffect so the guard fires on first render.
  useEffect(() => {
    if (!browserId) {
      setPollingError('Unable to start status updates')
      return
    }

    // Mark as mounted
    mountedRef.current = true

    /** Helper: update both state and ref, guarded against unmount */
    function updateStatus(newStatus: OrderStatus) {
      if (mountedRef.current) {
        setStatus(newStatus)
        statusRef.current = newStatus
      }
    }

    async function poll(): Promise<void> {
      try {
        const supabase = getSupabase()
        const { data, error } = await supabase.functions.invoke(
          `tables-session/${tableId}`,
          {
            method: 'GET',
            headers: { 'x-browser-id': browserId },
          },
        )

        // 404 — table no longer exists
        if (error && (error.status === 404 || error.message?.includes('404'))) {
          if (mountedRef.current) {
            if (intervalRef.current !== null) {
              clearInterval(intervalRef.current)
              intervalRef.current = null
            }
            setPollingError(t('tableNotFound'))
            onTableNotFound()
          }
          return
        }

        // Non-404 HTTP error or thrown error — log and continue polling
        if (error) {
          console.error('[useOrderPolling] poll error:', error)
          return
        }

        // Response shape guard: must be OCCUPIED with an order present
        if (data?.state !== 'OCCUPIED' || !data?.order) {
          return
        }

        const newStatus = data.order.status as OrderStatus

        // No change — no-op
        if (newStatus === statusRef.current) {
          return
        }

        const isTerminal = newStatus === 'COMPLETED' || newStatus === 'CANCELLED'

        if (isTerminal) {
          // Terminal: update status, stop all polling
          updateStatus(newStatus)
          if (intervalRef.current !== null) {
            clearInterval(intervalRef.current)
            intervalRef.current = null
          }
          stoppedRef.current = true
        } else {
          // Non-terminal: update status, reschedule interval if bucket changed
          const prevStatus = statusRef.current
          updateStatus(newStatus)
          rescheduleIfBucketChanged(prevStatus, newStatus)
        }
      } catch (err: unknown) {
        // Thrown exception (network error etc.) — log and continue polling
        console.error('[useOrderPolling] poll exception:', err)
      }
    }

    function rescheduleIfBucketChanged(prevStatus: OrderStatus | null, newStatus: OrderStatus) {
      const prevMs = getIntervalMs(prevStatus)
      const newMs = getIntervalMs(newStatus)
      if (newMs !== null && newMs !== prevMs) {
        if (intervalRef.current !== null) clearInterval(intervalRef.current)
        intervalRef.current = setInterval(poll, newMs)
      }
    }

    function restartIntervalForCurrentStatus() {
      if (stoppedRef.current) return
      const ms = getIntervalMs(statusRef.current)
      if (ms === null) return
      if (intervalRef.current !== null) clearInterval(intervalRef.current)
      intervalRef.current = setInterval(poll, ms)
    }

    function handleVisibility() {
      if (document.visibilityState === 'hidden') {
        if (intervalRef.current !== null) {
          clearInterval(intervalRef.current)
          intervalRef.current = null
        }
        return
      }

      if (stoppedRef.current) return

      void poll().finally(() => {
        if (!mountedRef.current || stoppedRef.current) return
        restartIntervalForCurrentStatus()
      })
    }

    setStatus(initialStatus)
    statusRef.current = initialStatus
    stoppedRef.current = getIntervalMs(initialStatus) === null

    const initialMs = getIntervalMs(initialStatus)
    if (initialMs !== null && !stoppedRef.current) {
      intervalRef.current = setInterval(poll, initialMs)
    }

    document.addEventListener('visibilitychange', handleVisibility)

    return () => {
      mountedRef.current = false

      if (intervalRef.current !== null) {
        clearInterval(intervalRef.current)
        intervalRef.current = null
      }
      document.removeEventListener('visibilitychange', handleVisibility)
    }
    // onTableNotFound and t are intentionally omitted — stable enough for poll error handling
  }, [tableId, browserId, initialStatus])

  return { status, pollingError }
}

// Export getIntervalMs for unit testing only (not part of the public API)
export { getIntervalMs }
