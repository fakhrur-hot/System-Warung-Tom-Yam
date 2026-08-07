import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import fc from 'fast-check'
import { getIntervalMs, useOrderPolling } from './useOrderPolling'

type OrderStatus =
  | 'RECEIVED'
  | 'SENT_TO_KITCHEN'
  | 'PREPARING'
  | 'READY'
  | 'COMPLETED'
  | 'CANCELLED'

const ALL_STATUSES: OrderStatus[] = [
  'RECEIVED',
  'SENT_TO_KITCHEN',
  'PREPARING',
  'READY',
  'COMPLETED',
  'CANCELLED',
]

const ACTIVE_STATUSES: OrderStatus[] = ['RECEIVED', 'SENT_TO_KITCHEN', 'PREPARING', 'READY']

const invokeMock = vi.fn()
let visibilityStateMock: DocumentVisibilityState = 'visible'

vi.mock('../lib/supabase', () => ({
  getSupabase: () => ({
    functions: { invoke: (...args: unknown[]) => invokeMock(...args) },
    channel: vi.fn(),
  }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => (key === 'tableNotFound' ? 'Table not found' : key),
  }),
}))

function occupiedResponse(status: OrderStatus) {
  return {
    data: { state: 'OCCUPIED' as const, order: { id: 'order-1', status, total: 0, items: [] } },
    error: null,
  }
}

describe('getIntervalMs', () => {
  it('returns 5000 for each active pre-READY status', () => {
    expect(getIntervalMs('RECEIVED')).toBe(5000)
    expect(getIntervalMs('SENT_TO_KITCHEN')).toBe(5000)
    expect(getIntervalMs('PREPARING')).toBe(5000)
  })

  it('returns 10000 for READY', () => {
    expect(getIntervalMs('READY')).toBe(10000)
  })

  it('returns null for terminal statuses and null', () => {
    expect(getIntervalMs('COMPLETED')).toBeNull()
    expect(getIntervalMs('CANCELLED')).toBeNull()
    expect(getIntervalMs(null)).toBeNull()
  })

  it('Property 4: interval mapping correctness', () => {
    fc.assert(
      fc.property(
        fc.constantFrom(...ALL_STATUSES, null),
        (s) => {
          const ms = getIntervalMs(s)
          if (s === 'RECEIVED' || s === 'SENT_TO_KITCHEN' || s === 'PREPARING') {
            expect(ms).toBe(5000)
          } else if (s === 'READY') {
            expect(ms).toBe(10000)
          } else {
            expect(ms).toBeNull()
          }
        },
      ),
      { numRuns: 100 },
    )
  })
})

describe('useOrderPolling', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    invokeMock.mockReset()
    invokeMock.mockResolvedValue(occupiedResponse('RECEIVED'))
    visibilityStateMock = 'visible'
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => visibilityStateMock,
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('Property 1: poll request format', async () => {
    await fc.assert(
      fc.asyncProperty(
        fc.string({ minLength: 1 }).filter((s) => s.trim().length > 0),
        fc.string({ minLength: 1 }).filter((s) => s.trim().length > 0),
        async (tableId, browserId) => {
          invokeMock.mockClear()
          invokeMock.mockResolvedValue(occupiedResponse('RECEIVED'))
          const { unmount } = renderHook(() =>
            useOrderPolling(tableId, browserId, 'RECEIVED', () => {}),
          )
          await act(async () => {
            await vi.advanceTimersByTimeAsync(5000)
            await Promise.resolve()
            await Promise.resolve()
          })
          expect(invokeMock).toHaveBeenCalled()
          const [path, options] = invokeMock.mock.calls[0] as [
            string,
            { method: string; headers: Record<string, string> },
          ]
          expect(path).toBe(`tables-session/${tableId}`)
          expect(options.method).toBe('GET')
          expect(options.headers['x-browser-id']).toBe(browserId)
          unmount()
        },
      ),
      { numRuns: 100 },
    )
  })

  it('Property 2: status update on change', async () => {
    await fc.assert(
      fc.asyncProperty(
        fc
          .tuple(fc.constantFrom(...ACTIVE_STATUSES), fc.constantFrom(...ALL_STATUSES))
          .filter(([a, b]) => a !== b),
        async ([initialStatus, newStatus]) => {
          invokeMock.mockClear()
          invokeMock.mockResolvedValue(occupiedResponse(newStatus))
          const { result, unmount } = renderHook(() =>
            useOrderPolling('t1', 'browser-1', initialStatus, () => {}),
          )
          await act(async () => {
            const waitMs = getIntervalMs(initialStatus) ?? 5000
            await vi.advanceTimersByTimeAsync(waitMs)
            await Promise.resolve()
            await Promise.resolve()
          })
          expect(result.current.status).toBe(newStatus)
          unmount()
        },
      ),
      { numRuns: 100 },
    )
  })

  it('Property 3: resilience to bad responses', async () => {
    const badCases = [
      { label: 'missing order', value: { data: { state: 'OCCUPIED' }, error: null } },
      { label: 'non-OCCUPIED', value: { data: { state: 'FREE' }, error: null } },
      { label: 'http error', value: { data: null, error: { status: 500, message: 'server error' } } },
    ]

    await fc.assert(
      fc.asyncProperty(fc.constantFrom(...badCases), fc.constantFrom(...ACTIVE_STATUSES), async (badCase, initialStatus) => {
        invokeMock.mockClear()
        const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
        invokeMock.mockResolvedValue(badCase.value)
        const { result, unmount } = renderHook(() =>
          useOrderPolling('t1', 'browser-1', initialStatus, () => {}),
        )
        await act(async () => {
          const waitMs = getIntervalMs(initialStatus) ?? 5000
          await vi.advanceTimersByTimeAsync(waitMs)
          await Promise.resolve()
          await Promise.resolve()
        })
        expect(result.current.status).toBe(initialStatus)
        if (badCase.label === 'http error') {
          expect(errorSpy).toHaveBeenCalled()
        }
        errorSpy.mockRestore()
        unmount()
      }),
      { numRuns: 100 },
    )

    await fc.assert(
      fc.asyncProperty(fc.constantFrom(...ACTIVE_STATUSES), async (initialStatus) => {
        invokeMock.mockClear()
        const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
        invokeMock.mockRejectedValue(new Error('network'))
        const { result, unmount } = renderHook(() =>
          useOrderPolling('t1', 'browser-1', initialStatus, () => {}),
        )
        await act(async () => {
          const waitMs = getIntervalMs(initialStatus) ?? 5000
          await vi.advanceTimersByTimeAsync(waitMs)
          await Promise.resolve()
          await Promise.resolve()
        })
        expect(result.current.status).toBe(initialStatus)
        expect(errorSpy).toHaveBeenCalled()
        errorSpy.mockRestore()
        unmount()
      }),
      { numRuns: 100 },
    )
  })

  it('Property 5: interval rescheduling on bucket change', async () => {
    const setIntervalSpy = vi.spyOn(globalThis, 'setInterval')
    const clearIntervalSpy = vi.spyOn(globalThis, 'clearInterval')

    invokeMock.mockClear()
    invokeMock
      .mockResolvedValueOnce(occupiedResponse('READY'))
      .mockResolvedValue(occupiedResponse('READY'))

    const { unmount } = renderHook(() =>
      useOrderPolling('t1', 'browser-1', 'PREPARING', () => {}),
    )

    const clearsBefore = clearIntervalSpy.mock.calls.length
    const setsBefore = setIntervalSpy.mock.calls.length

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(invokeMock).toHaveBeenCalled()

    expect(clearIntervalSpy.mock.calls.length).toBeGreaterThan(clearsBefore)
    expect(setIntervalSpy.mock.calls.length).toBeGreaterThan(setsBefore)
    const lastInterval = setIntervalSpy.mock.calls.slice(-1)[0]?.[1]
    expect(lastInterval).toBe(10000)

    unmount()
    setIntervalSpy.mockRestore()
    clearIntervalSpy.mockRestore()
  })

  it('Property 6: terminal status stops all polling', async () => {
    await fc.assert(
      fc.asyncProperty(fc.constantFrom('COMPLETED' as const, 'CANCELLED' as const), async (terminal) => {
        invokeMock.mockClear()
        invokeMock.mockResolvedValue(occupiedResponse(terminal))
        const { unmount } = renderHook(() =>
          useOrderPolling('t1', 'browser-1', 'PREPARING', () => {}),
        )
        await act(async () => {
          await vi.advanceTimersByTimeAsync(5000)
          await Promise.resolve()
          await Promise.resolve()
        })
        expect(invokeMock).toHaveBeenCalled()
        const callsAfterTerminal = invokeMock.mock.calls.length

        visibilityStateMock = 'hidden'
        document.dispatchEvent(new Event('visibilitychange'))
        visibilityStateMock = 'visible'
        document.dispatchEvent(new Event('visibilitychange'))
        await act(async () => {
          await vi.advanceTimersByTimeAsync(20000)
          await Promise.resolve()
          await Promise.resolve()
        })

        expect(invokeMock.mock.calls.length).toBe(callsAfterTerminal)
        unmount()
      }),
      { numRuns: 100 },
    )
  })

  it('Property 7: tab-hide pauses, tab-show resumes', async () => {
    await fc.assert(
      fc.asyncProperty(fc.constantFrom(...ACTIVE_STATUSES), async (activeStatus) => {
        invokeMock.mockClear()
        invokeMock.mockResolvedValue(occupiedResponse(activeStatus))
        const clearSpy = vi.spyOn(globalThis, 'clearInterval')
        const setSpy = vi.spyOn(globalThis, 'setInterval')

        const { unmount } = renderHook(() =>
          useOrderPolling('t1', 'browser-1', activeStatus, () => {}),
        )

        await act(async () => {
          const waitMs = getIntervalMs(activeStatus) ?? 5000
          await vi.advanceTimersByTimeAsync(waitMs)
          await Promise.resolve()
          await Promise.resolve()
        })
        invokeMock.mockClear()

        visibilityStateMock = 'hidden'
        document.dispatchEvent(new Event('visibilitychange'))
        expect(clearSpy).toHaveBeenCalled()

        visibilityStateMock = 'visible'
        document.dispatchEvent(new Event('visibilitychange'))
        await act(async () => {
          await Promise.resolve()
          await Promise.resolve()
          await Promise.resolve()
        })

        expect(invokeMock).toHaveBeenCalledTimes(1)
        expect(setSpy).toHaveBeenCalled()

        clearSpy.mockRestore()
        setSpy.mockRestore()
        unmount()
      }),
      { numRuns: 100 },
    )
  })

  it('empty browserId: no interval, pollingError set', async () => {
    const setIntervalSpy = vi.spyOn(globalThis, 'setInterval')
    const { result } = renderHook(() => useOrderPolling('t1', '', 'RECEIVED', () => {}))
    await act(async () => {
      await Promise.resolve()
    })
    expect(result.current.pollingError).toBe('Unable to start status updates')
    expect(setIntervalSpy).not.toHaveBeenCalled()
    setIntervalSpy.mockRestore()
  })

  it('404: clears interval, sets table not found, calls onTableNotFound', async () => {
    const onTableNotFound = vi.fn()
    invokeMock.mockResolvedValue({ data: null, error: { status: 404, message: 'Not found' } })
    const clearSpy = vi.spyOn(globalThis, 'clearInterval')

    const { result, unmount } = renderHook(() =>
      useOrderPolling('t1', 'browser-1', 'RECEIVED', onTableNotFound),
    )

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })

    expect(result.current.pollingError).toContain('Table not found')
    expect(onTableNotFound).toHaveBeenCalled()
    expect(clearSpy).toHaveBeenCalled()
    clearSpy.mockRestore()
    unmount()
  })

  it('does not update status after unmount', async () => {
    let resolvePoll!: (value: unknown) => void
    invokeMock.mockReturnValue(
      new Promise((resolve) => {
        resolvePoll = resolve
      }),
    )

    const { result, unmount } = renderHook(() =>
      useOrderPolling('t1', 'browser-1', 'RECEIVED', () => {}),
    )

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })

    const statusBeforeResolve = result.current.status
    unmount()
    await act(async () => {
      resolvePoll(occupiedResponse('READY'))
      await Promise.resolve()
    })

    expect(statusBeforeResolve).toBe('RECEIVED')
  })

  it('never opens a Supabase Realtime channel', async () => {
    const { getSupabase } = await import('../lib/supabase')
    const supabase = getSupabase()
    const { unmount } = renderHook(() => useOrderPolling('t1', 'browser-1', 'RECEIVED', () => {}))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })
    expect(supabase.channel).not.toHaveBeenCalled()
    unmount()
  })

  it('cleans up interval and visibility listener on unmount', () => {
    const removeSpy = vi.spyOn(document, 'removeEventListener')
    const clearSpy = vi.spyOn(globalThis, 'clearInterval')
    const { unmount } = renderHook(() => useOrderPolling('t1', 'browser-1', 'RECEIVED', () => {}))
    unmount()
    expect(clearSpy).toHaveBeenCalled()
    expect(removeSpy).toHaveBeenCalledWith('visibilitychange', expect.any(Function))
    removeSpy.mockRestore()
    clearSpy.mockRestore()
  })

  it('registers visibilitychange exactly once per mount', () => {
    const addSpy = vi.spyOn(document, 'addEventListener')
    const { unmount } = renderHook(() => useOrderPolling('t1', 'browser-1', 'RECEIVED', () => {}))
    const visibilityAdds = addSpy.mock.calls.filter(([event]) => event === 'visibilitychange')
    expect(visibilityAdds).toHaveLength(1)
    unmount()
    addSpy.mockRestore()
  })

  it('visibility hidden clears interval; visible after terminal is no-op', async () => {
    invokeMock.mockResolvedValue(occupiedResponse('COMPLETED'))
    const setSpy = vi.spyOn(globalThis, 'setInterval')

    const { unmount } = renderHook(() =>
      useOrderPolling('t1', 'browser-1', 'RECEIVED', () => {}),
    )

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })
    invokeMock.mockClear()
    setSpy.mockClear()

    visibilityStateMock = 'visible'
    document.dispatchEvent(new Event('visibilitychange'))
    await act(async () => {
      await vi.runOnlyPendingTimersAsync()
    })

    expect(invokeMock).not.toHaveBeenCalled()
    expect(setSpy).not.toHaveBeenCalled()
    setSpy.mockRestore()
    unmount()
  })
})
