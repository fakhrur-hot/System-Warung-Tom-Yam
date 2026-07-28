import { useEffect, useRef, useState } from 'react'
import { getSupabase } from '../../lib/supabase'

interface OrderItem {
  id: string
  nameSnapshot: string
  unitPriceSnapshot: number
  categorySnapshot: string
  quantity: number
  note?: string
  sentToKitchen: boolean
}

interface Order {
  id: string
  tableId: string
  source: 'QR' | 'STAFF'
  status: 'RECEIVED' | 'SENT_TO_KITCHEN' | 'PREPARING' | 'READY' | 'COMPLETED' | 'CANCELLED'
  total: number
  createdAt: string
  items: OrderItem[]
}

export default function OrdersPage() {
  const [orders, setOrders] = useState<Order[]>([])
  const [loading, setLoading] = useState(true)
  const [connected, setConnected] = useState(false)
  const channelRef = useRef<ReturnType<ReturnType<typeof getSupabase>['channel']> | null>(null)

  // Subscribe to admin-orders channel for live updates
  useEffect(() => {
    const supabase = getSupabase()

    const channel = supabase
      .channel('admin-orders')
      .on('broadcast', { event: 'NEW_ORDER' }, (payload) => {
        const newOrder = payload.payload?.order as Order | undefined
        if (newOrder) {
          setOrders((prev) => [newOrder, ...prev])
        }
      })
      .subscribe((status) => {
        setConnected(status === 'SUBSCRIBED')
      })

    channelRef.current = channel

    return () => {
      supabase.removeChannel(channel)
      channelRef.current = null
    }
  }, [])

  // Initial fetch of recent orders
  useEffect(() => {
    async function fetchOrders() {
      try {
        const supabase = getSupabase()
        // Fetch orders from the last 24 hours
        const since = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString()
        const { data, error } = await supabase.functions.invoke(`orders?since=${since}`, {
          method: 'GET',
        })

        if (!error && data?.orders) {
          setOrders(data.orders as Order[])
        }
      } catch {
        // Non-critical — we'll get live updates anyway
      } finally {
        setLoading(false)
      }
    }
    fetchOrders()
  }, [])

  const getStatusClasses = (status: string) => {
    switch (status) {
      case 'RECEIVED': return 'bg-blue-100 text-blue-700'
      case 'SENT_TO_KITCHEN': return 'bg-yellow-100 text-yellow-700'
      case 'PREPARING': return 'bg-orange-100 text-orange-700'
      case 'READY': return 'bg-green-100 text-green-700'
      case 'COMPLETED': return 'bg-gray-100 text-gray-600'
      case 'CANCELLED': return 'bg-red-100 text-red-700'
      default: return 'bg-gray-100 text-gray-600'
    }
  }

  const formatStatus = (status: string) => {
    return status.replace(/_/g, ' ')
  }

  if (loading) {
    return (
      <div className="flex min-h-[40vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-emerald-200 border-t-emerald-600" role="status" aria-label="Loading orders" />
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Live Orders</h1>
        <div className="flex items-center gap-2">
          <span
            className={`inline-block h-2 w-2 rounded-full ${connected ? 'bg-green-500' : 'bg-red-400'}`}
            aria-hidden="true"
          />
          <span className="text-xs text-gray-500">
            {connected ? 'Connected' : 'Disconnected'}
          </span>
        </div>
      </div>

      {orders.length === 0 ? (
        <div className="rounded-lg border border-gray-200 bg-white p-8 text-center">
          <span className="mb-3 block text-4xl" aria-hidden="true">🧾</span>
          <p className="text-sm text-gray-500">No orders yet. New orders will appear here in real time.</p>
        </div>
      ) : (
        <div className="space-y-3" role="list" aria-label="Order list">
          {orders.map((order) => (
            <div
              key={order.id}
              className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm transition-colors hover:border-emerald-200"
              role="listitem"
            >
              <div className="flex items-start justify-between">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-bold text-gray-900">Table {order.tableId}</span>
                    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${getStatusClasses(order.status)}`}>
                      {formatStatus(order.status)}
                    </span>
                    <span className="rounded-full bg-gray-50 px-2 py-0.5 text-xs text-gray-500">
                      {order.source}
                    </span>
                  </div>
                  <p className="mt-1 text-xs text-gray-400">
                    {new Date(order.createdAt).toLocaleString()}
                  </p>
                </div>
                <p className="text-sm font-bold text-emerald-700">RM {order.total.toFixed(2)}</p>
              </div>

              {/* Items list */}
              <div className="mt-3 border-t border-gray-100 pt-3">
                <ul className="space-y-1">
                  {order.items.map((item) => (
                    <li key={item.id} className="flex items-center justify-between text-sm">
                      <span className="text-gray-700">
                        {item.quantity}× {item.nameSnapshot}
                        {item.note && (
                          <span className="ml-1 text-xs italic text-gray-400">({item.note})</span>
                        )}
                      </span>
                      <span className="text-gray-500">
                        RM {(item.unitPriceSnapshot * item.quantity).toFixed(2)}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
