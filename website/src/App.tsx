import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { applyCafeDefault, serverCodeToLang } from './i18n'
import { getSupabase, functionErrorMessage } from './lib/supabase'
import { getBrowserId } from './lib/browserId'
import { useOrderPolling } from './hooks/useOrderPolling'
import Header from './components/Header'
import SplashScreen from './components/SplashScreen'
import PlaceholderView from './components/PlaceholderView'
import OccupiedView from './components/OccupiedView'
import MenuView from './components/MenuView'
import StatusView from './components/StatusView'
import ConfirmDialog from './components/ConfirmDialog'
import QrScanner from './components/QrScanner'
import { loadAdSense } from './lib/adsense'
import { loadRollerAds } from './lib/rollerads'
import { loadRuntimeConfig } from './lib/runtimeConfig'

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

// A category can arrive as a plain name (legacy) or as { name, sortOrder } (dynamic
// menu). `name` is the canonical id used to group items; `nameI18n` (optional) carries
// the admin-entered display label per language.
type MenuCategory = string | { name: string; sortOrder: number; nameI18n?: Record<string, string> }

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
  | { type: 'closed' }

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

// Map of canonical category name → its per-language display labels (from the admin's
// menu). Used to translate the category tabs/headers while grouping still keys off the
// canonical name.
function deriveCategoryLabels(menu: MenuData): Record<string, Record<string, string>> {
  const map: Record<string, Record<string, string>> = {}
  for (const c of menu.categories || []) {
    if (typeof c === 'object' && c !== null && c.nameI18n) {
      map[c.name] = c.nameI18n
    }
  }
  return map
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
  // Admin-entered table name (from Table Management) — shown to customers instead of the id.
  const [tableName, setTableName] = useState<string | null>(null)
  // When set, the menu is being shown to ADD a round to this existing order (session 2+),
  // so submit appends via orders-items instead of creating a new order.
  const [amendOrderId, setAmendOrderId] = useState<string | null>(null)
  const [isAddingMore, setIsAddingMore] = useState(false)
  // Whether the in-page camera QR scanner overlay is open.
  const [scanning, setScanning] = useState(false)
  const pendingOrderRef = useRef<Array<{ menuItemId: string; quantity: number; note: string; size?: string; unitPrice?: number }>>([])

  const tableId = getTableFromUrl()
  const browserId = getBrowserId()

  const pollingInitialStatus: OrderStatus =
    view.type === 'status' ? view.order.status : 'COMPLETED'

  const handleTableNotFound = useCallback(() => {}, [])

  const { status: polledStatus, pollingError } = useOrderPolling(
    tableId ?? '',
    browserId,
    pollingInitialStatus,
    handleTableNotFound,
  )

  useEffect(() => {
    if (pollingError) {
      setView({ type: 'error', message: pollingError })
    }
  }, [pollingError])

  // Load page-level ad scripts — only from this component, which the router renders solely for
  // customer-facing paths (/order and the unmatched-path fallback), never any /admin/* route.
  //
  // Both no-op when unconfigured, and both run together on purpose. RollerAds' formats are push,
  // in-page push and popunder -- none of which occupy in-page real estate or sit beside an AdSense
  // unit, and RollerAds market push as AdSense-friendly. The Adsterra integration this replaced DID
  // compete for in-page space, which is why it had to suppress AdSense outright.
  //
  // The RollerAds tag src comes from runtime config so one build serves many cafes; `lib/rollerads`
  // falls back to the build-time env when the fetch yields nothing.
  useEffect(() => {
    loadRuntimeConfig().then((cfg) => loadRollerAds(cfg.rolleradsTagSrc))
    loadAdSense()
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
        // Adopt the café-wide default language — only if this visitor hasn't chosen one.
        const def = serverCodeToLang(data?.defaultLangCustomer)
        if (def) applyCafeDefault(def)
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
          `tables-session/${tableId}`,
          {
            method: 'GET',
            headers: { 'x-browser-id': browserId },
          },
        )

        if (sessionError) {
          setView({ type: 'error', message: await functionErrorMessage(sessionError, t('error')) })
          return
        }

        // Prefer the admin-entered table name for display; fall back to the id.
        setTableName((sessionData as { displayName?: string }).displayName ?? null)

        // Step 2: Branch based on session state
        if (sessionData.state === 'CLOSED') {
          // Café signed out for the day — not taking orders.
          setView({ type: 'closed' })
        } else if (sessionData.state === 'FREE') {
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
  }, [tableId, browserId, t])

  // Submit order - show confirmation first
  const handleSubmitOrder = (
    items: Array<{ menuItemId: string; quantity: number; note: string; size?: string; unitPrice?: number }>,
  ) => {
    pendingOrderRef.current = items
    setShowConfirm(true)
  }

  // After the customer confirms, start the "hold before kitchen" countdown instead of
  // sending immediately. The order is only POSTed once the countdown expires, so the
  // customer can still cancel it (nothing reaches the kitchen during the hold).
  //
  // Table validity is re-checked HERE, on the "Yes" tap — not just once when the page
  // first loaded. A table's QR/session can go stale between page-load and this moment
  // (the tab sat open a long time, staff removed/renamed the table, the café closed),
  // and without this check a customer could confirm an order against a table that no
  // longer resolves, only to land on a broken/blank status view afterward instead of a
  // clear error at the moment they actually acted. Applies to every café built from
  // this shared source, not a per-café patch — see tables-session's UNKNOWN_TABLE.
  const handleConfirmOrder = async () => {
    setShowConfirm(false)
    if (!tableId || pendingOrderRef.current.length === 0) return

    try {
      const supabase = getSupabase()
      const { error: sessionError } = await supabase.functions.invoke(`tables-session/${tableId}`, {
        method: 'GET',
        headers: { 'x-browser-id': browserId },
      })
      if (sessionError) {
        pendingOrderRef.current = []
        setView({ type: 'error', message: await functionErrorMessage(sessionError, t('error')) })
        return
      }
    } catch (err: unknown) {
      pendingOrderRef.current = []
      const message = err instanceof Error ? err.message : t('error')
      setView({ type: 'error', message })
      return
    }

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

  // Actual submission (runs after the hold elapses). Two modes:
  // - new order (amendOrderId null): POST /orders
  // - add a round to the existing order (amendOrderId set): POST /orders-items → session N+1
  const submitHeldOrder = async () => {
    const items = pendingOrderRef.current
    if (!tableId || items.length === 0) return
    setIsSubmitting(true)

    const mapped = items.map((item) => ({
      menuItemId: item.menuItemId,
      quantity: item.quantity,
      note: item.note || undefined,
      // Small/Medium/Large: the chosen size + its price (validated server-side).
      size: item.size || undefined,
      unitPrice: item.unitPrice,
    }))

    try {
      const supabase = getSupabase()

      if (amendOrderId) {
        // Append a new session to the customer's own order.
        const { error } = await supabase.functions.invoke(`orders-items?orderId=${amendOrderId}`, {
          body: { items: mapped },
          headers: { 'x-browser-id': browserId },
        })
        if (error) {
          setView({ type: 'error', message: error.message || t('error') })
          return
        }
        setAmendOrderId(null)
      } else {
        const { error } = await supabase.functions.invoke('orders', {
          body: { tableId, browserId, items: mapped },
          headers: { 'x-browser-id': browserId },
        })
        if (error) {
          setView({ type: 'error', message: error.message || t('error') })
          return
        }
      }

      // Re-fetch session to get the full order with all sessions + snapshots.
      const { data: sessionData } = await supabase.functions.invoke(
        `tables-session/${tableId}`,
        {
          method: 'GET',
          headers: { 'x-browser-id': browserId },
        },
      )

      if (sessionData?.order) {
        setView({ type: 'status', order: sessionData.order })
      } else {
        setView({ type: 'error', message: t('error') })
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : t('error')
      setView({ type: 'error', message })
    } finally {
      setIsSubmitting(false)
    }
  }

  // From the status view: fetch the menu again and show it in "add a round" mode.
  const handleAddMore = async () => {
    if (view.type !== 'status') return
    const orderId = view.order.id
    setIsAddingMore(true)
    try {
      const supabase = getSupabase()
      const { data: menuData, error } = await supabase.functions.invoke('menu', { method: 'GET' })
      if (error || !menuData || menuData.configured === false) {
        setView({ type: 'error', message: error?.message || t('error') })
        return
      }
      setAmendOrderId(orderId)
      setView({ type: 'menu', menu: menuData })
    } catch (err: unknown) {
      setView({ type: 'error', message: err instanceof Error ? err.message : t('error') })
    } finally {
      setIsAddingMore(false)
    }
  }

  // Abandon adding a round and return to the order status.
  const handleBackToStatus = async () => {
    setAmendOrderId(null)
    if (!tableId) return
    try {
      const supabase = getSupabase()
      const { data: sessionData } = await supabase.functions.invoke(`tables-session/${tableId}`, {
        method: 'GET',
        headers: { 'x-browser-id': browserId },
      })
      if (sessionData?.order) {
        setView({ type: 'status', order: sessionData.order })
      }
    } catch {
      window.location.reload()
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
      const { error } = await supabase.functions.invoke(`orders-cancel?orderId=${view.order.id}`, {
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

  // After completed/cancelled, the "Scan QR code to order again" button opens the
  // in-page camera scanner so the customer can scan their table QR again (matching
  // what the button says) rather than silently reloading.
  const handleDone = () => {
    setScanning(true)
  }

  // A table QR was scanned — navigate to that table's ordering page. A full
  // navigation (not just state) re-runs the session check cleanly for the new table.
  const handleQrDetected = (scannedTableId: string) => {
    setScanning(false)
    window.location.href = `/order?table=${encodeURIComponent(scannedTableId)}`
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
        // Full-screen branded splash is rendered as an early return below; this is unreachable
        // but keeps the switch exhaustive.
        return null

      case 'no-table':
        return (
          <div className="flex min-h-[60vh] items-center justify-center px-4">
            <div className="text-center">
              <span className="mb-4 block text-5xl" aria-hidden="true">📱</span>
              <p className="text-sm text-emerald-600">{t('noTable')}</p>
              <button
                onClick={() => setScanning(true)}
                className="mt-5 inline-flex min-h-[44px] items-center gap-2 rounded-full bg-emerald-600 px-6 py-3 text-sm font-semibold text-white transition hover:bg-emerald-700 active:scale-95"
              >
                <span aria-hidden="true">📷</span>
                {t('scanQrButton')}
              </button>
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
                className="mt-4 min-h-[44px] rounded-full bg-emerald-600 px-6 py-3 text-sm font-semibold text-white transition hover:bg-emerald-700 active:scale-95"
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

      case 'closed':
        return (
          <div className="flex min-h-[60vh] items-center justify-center px-4">
            <div className="text-center">
              <span className="mb-4 block text-5xl" aria-hidden="true">🌙</span>
              <h2 className="text-xl font-semibold text-emerald-800">{t('cafeClosedTitle')}</h2>
              <p className="mt-2 text-sm text-emerald-600">{t('cafeClosedBody')}</p>
            </div>
          </div>
        )

      case 'menu': {
        const orderedCategories = deriveCategories(view.menu)
        const categoryLabels = deriveCategoryLabels(view.menu)
        return (
          <>
            {amendOrderId && (
              <div className="mx-3 mt-3 flex items-center justify-between rounded-xl bg-emerald-100 px-4 py-3 text-emerald-900">
                <span className="font-semibold">{t('addingToOrder')}</span>
                <button
                  onClick={handleBackToStatus}
                  className="text-sm font-medium text-emerald-700 underline"
                >
                  {t('backToOrder')}
                </button>
              </div>
            )}
            {todaysSpecial.trim() && (
              <div className="mx-3 mt-3 flex items-start gap-2 rounded-xl bg-herb-100 px-4 py-3 text-herb-900">
                <span aria-hidden="true">🌿</span>
                <span className="font-semibold">{todaysSpecial}</span>
              </div>
            )}
            <MenuView
              items={view.menu.items || []}
              categories={orderedCategories}
              categoryLabels={categoryLabels}
              onSubmitOrder={handleSubmitOrder}
              isSubmitting={isSubmitting}
            />
          </>
        )
      }

      case 'status': {
        const order =
          polledStatus !== null ? { ...view.order, status: polledStatus } : view.order
        return (
          <StatusView
            order={order}
            onCancel={handleCancelOrder}
            isCancelling={isCancelling}
            onDone={handleDone}
            onAddMore={handleAddMore}
            isAddingMore={isAddingMore}
          />
        )
      }
    }
  }

  // First-load branded splash (logo + name + skeleton menu), before the app chrome renders.
  if (view.type === 'loading') {
    return (
      <SplashScreen
        cafeName={branding.configured ? branding.cafeName : undefined}
        logoUrl={branding.configured ? branding.logoUrl : undefined}
      />
    )
  }

  return (
    <div className="min-h-screen bg-emerald-50">
      {scanning && <QrScanner onDetected={handleQrDetected} onClose={() => setScanning(false)} />}
      <Header
        cafeName={branding.configured ? branding.cafeName : undefined}
        logoUrl={branding.configured ? branding.logoUrl : undefined}
      />
      {tableId && view.type !== 'no-table' && (
        <div className="bg-emerald-50 py-2 text-center">
          <p className="text-xs font-medium uppercase tracking-widest text-emerald-600">
            {t('tableLabel', { table: tableName ?? tableId })}
          </p>
        </div>
      )}
      <main className="mx-auto max-w-md">
        {renderView()}
      </main>
      {/* Studio credit — mirrors the printed receipt footer. Italic, tiny (matches the
          order-flow step labels' text-[10px]). */}
      <footer className="mx-auto max-w-md px-4 pb-6 pt-4 text-center">
        <p className="text-[10px] italic leading-tight text-emerald-500">
          Zero-Commitment POS by RAZStudio
        </p>
        <p className="text-[10px] italic leading-tight text-emerald-500">+60 11-32605406</p>
      </footer>
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
