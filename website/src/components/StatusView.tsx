import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'

type OrderStatus = 'RECEIVED' | 'SENT_TO_KITCHEN' | 'PREPARING' | 'READY' | 'COMPLETED' | 'CANCELLED'

interface OrderItem {
  id: string
  nameSnapshot: string
  unitPriceSnapshot: number
  quantity: number
  note?: string
  sessionNumber?: number
}

interface Order {
  id: string
  status: OrderStatus
  total: number
  items: OrderItem[]
  createdAt?: string
}

// Orders auto-print to the kitchen the instant they're placed (no "received but not
// yet sent" phase to gate on anymore), so self-service cancellation is a short
// time-based grace window instead — must match the backend's CUSTOMER_CANCEL_WINDOW_MS
// in supabase/functions/orders-cancel/index.ts.
const CUSTOMER_CANCEL_WINDOW_MS = 60_000

function remainingCancelWindow(createdAt?: string): number {
  if (!createdAt) return 0
  const elapsed = Date.now() - new Date(createdAt).getTime()
  return Math.max(0, CUSTOMER_CANCEL_WINDOW_MS - elapsed)
}

interface StatusViewProps {
  order: Order
  onCancel: () => void
  isCancelling: boolean
  onDone: () => void
  onAddMore: () => void
  isAddingMore: boolean
}

const STATUS_STEPS: OrderStatus[] = ['RECEIVED', 'SENT_TO_KITCHEN', 'PREPARING', 'READY', 'COMPLETED']

const STATUS_KEYS: Record<OrderStatus, string> = {
  RECEIVED: 'orderReceived',
  SENT_TO_KITCHEN: 'sentToKitchen',
  PREPARING: 'preparing',
  READY: 'ready',
  COMPLETED: 'completed',
  CANCELLED: 'cancelled',
}

export default function StatusView({ order, onCancel, isCancelling, onDone, onAddMore, isAddingMore }: StatusViewProps) {
  const { t, i18n: _i18n } = useTranslation()
  const [confirmCancel, setConfirmCancel] = useState(false)
  const [cancelWindowRemainingMs, setCancelWindowRemainingMs] = useState(() =>
    remainingCancelWindow(order.createdAt),
  )

  useEffect(() => {
    setCancelWindowRemainingMs(remainingCancelWindow(order.createdAt))
    const interval = setInterval(() => {
      setCancelWindowRemainingMs(remainingCancelWindow(order.createdAt))
    }, 1000)
    return () => clearInterval(interval)
  }, [order.createdAt])

  // Terminal states
  if (order.status === 'COMPLETED') {
    return (
      <div className="flex min-h-[60vh] items-center justify-center px-4">
        <div className="text-center">
          <span className="mb-4 block text-5xl" aria-hidden="true">✅</span>
          <h2 className="text-xl font-semibold text-emerald-800">{t('thankYou')}</h2>
          <button
            onClick={onDone}
            className="mt-6 min-h-[44px] rounded-full bg-emerald-600 px-6 py-3 text-sm font-semibold text-white transition-colors hover:bg-emerald-700"
          >
            {t('scanAgain')}
          </button>
        </div>
      </div>
    )
  }

  if (order.status === 'CANCELLED') {
    return (
      <div className="flex min-h-[60vh] items-center justify-center px-4">
        <div className="text-center">
          <span className="mb-4 block text-5xl" aria-hidden="true">❌</span>
          <h2 className="text-xl font-semibold text-emerald-800">{t('orderCancelled')}</h2>
          <button
            onClick={onDone}
            className="mt-6 min-h-[44px] rounded-full bg-emerald-600 px-6 py-3 text-sm font-semibold text-white transition-colors hover:bg-emerald-700"
          >
            {t('scanAgain')}
          </button>
        </div>
      </div>
    )
  }

  const currentStepIndex = STATUS_STEPS.indexOf(order.status)
  const canCancel = cancelWindowRemainingMs > 0

  return (
    <div className="mx-auto max-w-md px-4 py-6">
      <h2 className="mb-6 text-center text-lg font-bold text-emerald-900">{t('orderStatus')}</h2>

      {/* Progress tracker */}
      <div className="mb-8" role="progressbar" aria-valuenow={currentStepIndex + 1} aria-valuemin={1} aria-valuemax={STATUS_STEPS.length}>
        <div className="flex items-center justify-between">
          {STATUS_STEPS.map((step, index) => {
            const isActive = index <= currentStepIndex
            return (
              <div key={step} className="flex flex-1 flex-col items-center">
                <div
                  className={`flex h-8 w-8 items-center justify-center rounded-full text-xs font-bold ${
                    isActive ? 'bg-emerald-600 text-white' : 'bg-emerald-100 text-emerald-400'
                  }`}
                >
                  {index + 1}
                </div>
                <span className={`mt-1 text-center text-[10px] leading-tight ${
                  isActive ? 'font-medium text-emerald-700' : 'text-emerald-400'
                }`}>
                  {t(STATUS_KEYS[step])}
                </span>
                {index < STATUS_STEPS.length - 1 && (
                  <div className="absolute" />
                )}
              </div>
            )
          })}
        </div>
        {/* Connector lines */}
        <div className="mt-[-32px] flex px-4">
          {STATUS_STEPS.slice(0, -1).map((_, index) => (
            <div
              key={index}
              className={`h-0.5 flex-1 ${index < currentStepIndex ? 'bg-emerald-600' : 'bg-emerald-100'}`}
              style={{ marginTop: '16px' }}
            />
          ))}
        </div>
      </div>

      {/* Order items — grouped by session (each round the customer added while waiting) */}
      <div className="mb-6 rounded-xl border border-emerald-100 bg-white p-4">
        <h3 className="mb-3 text-sm font-semibold text-emerald-700">{t('yourOrder')}</h3>
        {(() => {
          const sessions = Array.from(
            new Set(order.items.map((i) => i.sessionNumber ?? 1)),
          ).sort((a, b) => a - b)
          const multi = sessions.length > 1
          return sessions.map((session) => (
            <div key={session} className="mb-3 last:mb-0">
              {multi && (
                <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-emerald-500">
                  {t('sessionLabel', { n: session })}
                </p>
              )}
              <div className="space-y-2">
                {order.items
                  .filter((i) => (i.sessionNumber ?? 1) === session)
                  .map((item) => (
                    <div key={item.id} className="flex items-center justify-between text-sm">
                      <div>
                        <span className="text-emerald-900">{item.nameSnapshot}</span>
                        <span className="ml-1 text-emerald-500">×{item.quantity}</span>
                        {item.note && (
                          <p className="text-xs text-emerald-400 italic">{item.note}</p>
                        )}
                      </div>
                      <span className="font-medium text-emerald-700">
                        RM {(item.unitPriceSnapshot * item.quantity).toFixed(2)}
                      </span>
                    </div>
                  ))}
              </div>
            </div>
          ))
        })()}
        <div className="mt-3 border-t border-emerald-100 pt-3">
          <div className="flex justify-between font-bold text-emerald-900">
            <span>{t('total')}</span>
            <span>RM {order.total.toFixed(2)}</span>
          </div>
        </div>
      </div>

      {/* Add more items — start another round on the same table while waiting */}
      <div className="mb-6 text-center">
        <button
          onClick={onAddMore}
          disabled={isAddingMore}
          className="min-h-[44px] w-full rounded-full bg-emerald-600 px-6 py-3 text-sm font-semibold text-white transition-colors hover:bg-emerald-700 disabled:opacity-50"
        >
          {isAddingMore ? '…' : `+ ${t('addMoreItems')}`}
        </button>
      </div>

      {/* Cancel button - only within the short grace window after placing */}
      {canCancel && (
        <div className="text-center">
          {confirmCancel ? (
            <div className="space-y-2">
              <p className="text-sm text-emerald-700">{t('cancelConfirm')}</p>
              <div className="flex justify-center gap-3">
                <button
                  onClick={onCancel}
                  disabled={isCancelling}
                  className="min-h-[44px] rounded-full bg-red-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-red-700 disabled:opacity-50"
                >
                  {isCancelling ? '...' : t('cancel')}
                </button>
                <button
                  onClick={() => setConfirmCancel(false)}
                  className="min-h-[44px] rounded-full border border-emerald-200 px-5 py-2 text-sm font-medium text-emerald-700 transition-colors hover:bg-emerald-50"
                >
                  ←
                </button>
              </div>
            </div>
          ) : (
            <button
              onClick={() => setConfirmCancel(true)}
              className="min-h-[44px] rounded-full border border-red-200 px-6 py-2 text-sm font-medium text-red-600 transition-colors hover:bg-red-50"
            >
              {t('cancel')} ({Math.ceil(cancelWindowRemainingMs / 1000)}s)
            </button>
          )}
        </div>
      )}
    </div>
  )
}
