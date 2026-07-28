import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getSupabase } from './lib/supabase'
import { getBrowserId } from './lib/browserId'
import Header from './components/Header'
import LoadingView from './components/LoadingView'
import PlaceholderView from './components/PlaceholderView'
import OccupiedView from './components/OccupiedView'
import MenuView from './components/MenuView'
import StatusView from './components/StatusView'
import ConfirmDialog from './components/ConfirmDialog'

// ─── Types ───────────────────────────────────────────────────────────────────

type OrderStatus = 'RECEIVED' | 'SENT_TO_KITCHEN' | 'PREPARING' | 'READY' | 'COMPLETED' | 'CANCELLED'

interface OrderItem {
  id: string
  nameSnapshot: string
  unitPriceSnapshot: number
  quantity: number
  note?: string
}

interface Order {
  id: string
  status: OrderStatus
  total: number
  items: OrderItem[]
  createdAt?: string
}

interface MenuItem {
  id: string
  code?: string
  category: string
  price: number
  marketPrice?: boolean
  available: boolean
  askMeDaily: boolean
  name: Record<string, string>
  description?: Record<string, string>
}

// A category can arrive as a plain name (legacy) or as { name, sortOrder } (dynamic menu).
type MenuCategory = string | { name: string; sortOrder: number }

interface MenuData {
  configured: boolean
  categories?: MenuCategory[]
  items?: MenuItem[]
}

interface BrandingData {
  configured: boolean
  cafeName?: string
  logoUrl?: string
}

type ViewState =
  | { type: 'loading' }
  | { type: 'no-table' }
  | { type: 'error'; message: string }
  | { type: 'occupied' }
  | { type: 'menu'; menu: MenuData }
  | { type: 'status'; order: Order }
  | { type: 'placeholder' }

// ─── Helpers ─────────────────────────────────────────────────────────────────

function getTableFromUrl(): string | null {
  const params = new URLSearchParams(window.location.search)
  return params.get('table')
}

// Produce an ORDERED list of category names for the menu tabs:
//  1. If the menu supplies `categories`, use them — objects sorted by sortOrder, plain
//     strings kept in given order — mapped to their names.
//  2. Otherwise fall back to the distinct categories present in items (first-seen order).
//  3. As a last resort, the legacy hardcoded 4-category list.
function deriveCategories(menu: MenuData): string[] {
  const cats = menu.categories
  if (cats && cats.length > 0) {
    const objs = cats.filter((c): c is { name: string; sortOrder: number } => typeof c === 'object' && c !== null)
    if (objs.length > 0) {
      return [...objs].sort((a, b) => a.sortOrder - b.sortOrder).map((c) => c.name)
    }
    return (cats as string[]).slice()
  }

  const items = menu.items || []
  if (items.length > 0) {
    const seen: string[] = []
    for (const item of items) {
      if (item.category && !seen.includes(item.category)) seen.push(item.category)
    }
    if (seen.length > 0) return seen
  }

  return ['FOOD', 'BEVERAGES', 'SIDE_DISHES', 'OTHERS']
}

// ─── App ─────────────────────────────────────────────────────────────────────

export default function App() {
  const { t } = useTranslation()
  const [view, setView] = useState<ViewState>({ type: 'loading' })
  const [branding, setBranding] = useState<BrandingData>({ configured: false })
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isCancelling, setIsCancelling] = useState(false)
  const [showConfirm, setShowConfirm] = useState(false)
  // "Hold before kitchen": after confirming, the order waits this many seconds (from the
  // customerOrderHoldSeconds setting) before it's actually sent — the customer can cancel
  // during the countdown. null when no hold is running.
  const [holdSeconds, setHoldSeconds] = useState(15)
  const [todaysSpecial, setTodaysSpecial] = useState('')
  const [holdRemaining, setHoldRemaining] = useState<number | null>(null)
  const pendingOrderRef = useRef<Array<{ menuItemId: string; quantity: number; note: string }>>([])
  const channelRef = useRef<ReturnType<ReturnType<typeof getSupabase>['channel']> | null>(null)

  const tableId = getTableFromUrl()
  const browserId = getBrowserId()

  // Clean up realtime subscription on unmount
  useEffect(() => {
    return () => {
      if (channelRef.current) {
        const supabase = getSupabase()
        supabase.removeChannel(channelRef.current)
        channelRef.current = null
      }
    }
  }, [])

  // Subscribe to order realtime updates
  const subscribeToOrder = useCallback((orderId: string) => {
    const supabase = getSupabase()
    // Clean previous channel
    if (channelRef.current) {
      supabase.removeChannel(channelRef.current)
    }

    const channel = supabase
      .channel(`order:${orderId}`)
      .on('broadcast', { event: 'STATUS_UPDATE' }, (payload) => {
        const newStatus = payload.payload?.status as OrderStatus | undefined
        if (newStatus) {
          setView((prev) => {
            if (prev.type === 'status') {
              return { type: 'status', order: { ...prev.order, status: newStatus } }
            }
            return prev
          })
        }
      })
      .subscribe()

    channelRef.current = channel
  }, [])

  // Fetch branding
  useEffect(() => {
    async function fetchBranding() {
      try {
        const supabase = getSupabase()
        const { data, error } = await supabase.functions.invoke('branding', {
          method: 'GET',
        })
        if (!error && data && data.configured !== false) {
          setBranding({ configured: true, cafeName: data.cafeName, logoUrl: data.logoUrl })
        }
      } catch {
        // Branding fetch is non-critical, use defaults
      }
    }
    fetchBranding()
  }, [])

  // Fetch public customer settings: the "hold before kitchen" delay + today's special.
  useEffect(() => {
    async function fetchPublicSettings() {
      try {
        const supabase = getSupabase()
        const { data, error } = await supabase.functions.invoke('settings', { method: 'GET' })
        if (error) return
        const s = Number(data?.customerOrderHoldSeconds)
        if (Number.isFinite(s) && s > 0) setHoldSeconds(s)
        if (typeof data?.todaysSpecial === 'string') setTodaysSpecial(data.todaysSpecial)
      } catch {
        // Non-critical — fall back to defaults.
      }
    }
    fetchPublicSettings()
  }, [])

  // Initial session check state machine
  useEffect(() => {
    if (!tableId) {
      setView({ type: 'no-table' })
      return
    }

    async function checkSession() {
      try {
        const supabase = getSupabase()

        // Step 1: Check table session
        const { data: sessionData, error: sessionError } = await supabase.functions.invoke(
          `tables/${tableId}/session`,
          {
            method: 'GET',
            headers: { 'x-browser-id': browserId },
          },
        )

        if (sessionError) {
          setView({ type: 'error', message: sessionError.message || t('error') })
          return
        }

        // Step 2: Branch based on session state
        if (sessionData.state === 'FREE') {
          // Fetch menu
          const { data: menuData, error: menuError } = await supabase.functions.invoke('menu', {
            method: 'GET',
          })

          if (menuError) {
            setView({ type: 'error', message: menuError.message || t('error') })
            return
          }

          if (menuData.configured === false) {
            setView({ type: 'placeholder' })
          } else {
            setView({ type: 'menu', menu: menuData })
          }
        } else if (sessionData.state === 'OCCUPIED') {
          // Check if it's our own order
          if (sessionData.order) {
            const order: Order = sessionData.order
            setView({ type: 'status', order })
            subscribeToOrder(order.id)
          } else {
            // Another browser owns this table
            setView({ type: 'occupied' })
          }
        }
      } catch (err: unknown) {
        const message = err instanceof Error ? err.message : t('error')
        setView({ type: 'error', message })
      }
    }

    checkSession()
  }, [tableId, browserId, subscribeToOrder, t])

  // Submit order - show confirmation first
  const handleSubmitOrder = (items: Array<{ menuItemId: string; quantity: number; note: string }>) => {
    pendingOrderRef.current = items
    setShowConfirm(true)
  }

  // After the customer confirms, start the "hold before kitchen" countdown instead of
  // sending immediately. The order is only POSTed once the countdown expires, so the
  // customer can still cancel it (nothing reaches the kitchen during the hold).
  const handleConfirmOrder = () => {
    setShowConfirm(false)
    if (!tableId || pendingOrderRef.current.length === 0) return
    setHoldRemaining(holdSeconds)
  }

  // Cancel during the hold — abort before anything is sent.
  const handleCancelHold = () => {
    setHoldRemaining(null)
    pendingOrderRef.current = []
  }

  // Tick the hold countdown; fire the real submission when it reaches 0.
  useEffect(() => {
    if (holdRemaining === null) return
    if (holdRemaining <= 0) {
      setHoldRemaining(null)
      void submitHeldOrder()
      return
    }
    const timer = setTimeout(() => setHoldRemaining((r) => (r === null ? null : r - 1)), 1000)
    return () => clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [holdRemaining])

  // Actual submission (runs after the hold elapses).
  const submitHeldOrder = async () => {
    const items = pendingOrderRef.current
    if (!tableId || items.length === 0) return
    setIsSubmitting(true)

    try {
      const supabase = getSupabase()
      const { data, error } = await supabase.functions.invoke('orders', {
        body: {
          tableId,
          browserId,
          items: items.map((item) => ({
            menuItemId: item.menuItemId,
            quantity: item.quantity,
            note: item.note || undefined,
          })),
        },
        headers: { 'x-browser-id': browserId },
      })

      if (error) {
        setView({ type: 'error', message: error.message || t('error') })
        return
      }

      // On success show status view and subscribe to realtime. createdAt is a client-side
      // approximation (the create response doesn't include it) — fine here since this
      // object is only shown for the brief moment before the session re-fetch below
      // replaces it with the server's real createdAt, and the cancel-window check has
      // generous slack either way.
      const order: Order = {
        id: data.orderId,
        status: data.status || 'RECEIVED',
        total: data.total,
        items: items.map((item, i) => ({
          id: `temp-${i}`,
          nameSnapshot: item.menuItemId,
          unitPriceSnapshot: 0,
          quantity: item.quantity,
          note: item.note || undefined,
        })),
        createdAt: new Date().toISOString(),
      }

      // Re-fetch session to get the full order with snapshots
      const { data: sessionData } = await supabase.functions.invoke(
        `tables/${tableId}/session`,
        {
          method: 'GET',
          headers: { 'x-browser-id': browserId },
        },
      )

      if (sessionData?.order) {
        setView({ type: 'status', order: sessionData.order })
        subscribeToOrder(sessionData.order.id)
      } else {
        setView({ type: 'status', order })
        subscribeToOrder(order.id)
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : t('error')
      setView({ type: 'error', message })
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleCancelConfirm = () => {
    setShowConfirm(false)
    pendingOrderRef.current = []
  }

  // Cancel order
  const handleCancelOrder = async () => {
    if (view.type !== 'status') return
    setIsCancelling(true)

    try {
      const supabase = getSupabase()
      const { error } = await supabase.functions.invoke(`orders/${view.order.id}`, {
        method: 'DELETE',
        body: {
          reason: 'Customer cancelled',
          cancelledBy: 'customer',
        },
        headers: { 'x-browser-id': browserId },
      })

      if (error) {
        setView({ type: 'error', message: error.message || t('error') })
        return
      }

      setView({ type: 'status', order: { ...view.order, status: 'CANCELLED' } })
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : t('error')
      setView({ type: 'error', message })
    } finally {
      setIsCancelling(false)
    }
  }

  // Reset to fresh menu after completed/cancelled
  const handleDone = () => {
    // Reload the page to get a fresh session check
    window.location.reload()
  }

  // Retry after error
  const handleRetry = () => {
    setView({ type: 'loading' })
    window.location.reload()
  }

  // ─── Render ──────────────────────────────────────────────────────────────────

  const renderView = () => {
    switch (view.type) {
      case 'loading':
        return <LoadingView />

      case 'no-table':
        return (
          <div className="flex min-h-[60vh] items-center justify-center px-4">
            <div className="text-center">
              <span className="mb-4 block text-5xl" aria-hidden="true">📱</span>
              <p className="text-sm text-emerald-600">{t('noTable')}</p>
            </div>
          </div>
        )

      case 'error':
        return (
          <div className="flex min-h-[60vh] items-center justify-center px-4">
            <div className="text-center">
              <span className="mb-4 block text-5xl" aria-hidden="true">⚠️</span>
              <h2 className="text-lg font-semibold text-emerald-800">{t('error')}</h2>
              <p className="mt-2 text-sm text-emerald-600">{view.message}</p>
              <button
                onClick={handleRetry}
                className="mt-4 min-h-[44px] rounded-full bg-emerald-600 px-6 py-3 text-sm font-semibold text-white transition-colors hover:bg-emerald-700"
              >
                {t('retry')}
              </button>
            </div>
          </div>
        )

      case 'occupied':
        return <OccupiedView />

      case 'placeholder':
        return <PlaceholderView />

      case 'menu': {
        const orderedCategories = deriveCategories(view.menu)
        return (
          <>
            {todaysSpecial.trim() && (
              <div className="mx-3 mt-3 rounded-xl bg-amber-100 px-4 py-3 text-amber-900">
                <span className="mr-1" aria-hidden="true">⭐</span>
                <span className="font-semibold">{todaysSpecial}</span>
              </div>
            )}
            <MenuView
              items={view.menu.items || []}
              categories={orderedCategories}
              onSubmitOrder={handleSubmitOrder}
              isSubmitting={isSubmitting}
            />
          </>
        )
      }

      case 'status':
        return (
          <StatusView
            order={view.order}
            onCancel={handleCancelOrder}
            isCancelling={isCancelling}
            onDone={handleDone}
          />
        )
    }
  }

  return (
    <div className="min-h-screen bg-emerald-50">
      <Header
        cafeName={branding.configured ? branding.cafeName : undefined}
        logoUrl={branding.configured ? branding.logoUrl : undefined}
      />
      {tableId && view.type !== 'no-table' && (
        <div className="bg-emerald-50 py-2 text-center">
          <p className="text-xs font-medium uppercase tracking-widest text-emerald-600">
            {t('tableLabel', { table: tableId })}
          </p>
        </div>
      )}
      <main className="mx-auto max-w-md">
        {renderView()}
      </main>
      <ConfirmDialog
        open={showConfirm}
        onConfirm={handleConfirmOrder}
        onCancel={handleCancelConfirm}
      />
      {holdRemaining !== null && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 px-4"
          role="dialog"
          aria-modal="true"
        >
          <div className="w-full max-w-xs rounded-2xl bg-white p-6 text-center shadow-xl">
            <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-emerald-100 text-2xl font-bold text-emerald-700">
              {holdRemaining}
            </div>
            <p className="text-base font-semibold text-emerald-800">
              {t('holdSending', { seconds: holdRemaining })}
            </p>
            <p className="mt-1 text-sm text-emerald-600">{t('holdHint')}</p>
            <button
              onClick={handleCancelHold}
              className="mt-5 min-h-[44px] w-full rounded-full bg-red-500 px-6 py-3 text-sm font-semibold text-white transition-colors hover:bg-red-600"
            >
              {t('holdCancelBtn')}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
