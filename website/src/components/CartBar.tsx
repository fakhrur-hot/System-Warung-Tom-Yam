import { useState, useRef } from 'react'
import { useTranslation } from 'react-i18next'

interface CartItemData {
  id: string
  name: string
  quantity: number
  price: number
  marketPrice?: boolean
  category: string
  // Special instruction for this line, if any. Lines with a note are kept separate
  // from plain units of the same dish.
  note?: string
}

interface CartBarProps {
  totalItems: number
  totalPrice: number
  cartItems: CartItemData[]
  placedItems?: CartItemData[]
  // Ordered category names driving the group order (dynamic menu categories).
  categories?: string[]
  // canonical category name → per-language display labels (admin-entered).
  categoryLabels?: Record<string, Record<string, string>>
  onSubmit: () => void
  onUpdateQuantity: (id: string, delta: number) => void
  isSubmitting: boolean
}

// Legacy fallback order, used only when no dynamic `categories` prop is supplied.
const CATEGORY_ORDER = ['FOOD', 'BEVERAGES', 'SIDE_DISHES', 'OTHERS']
const CATEGORY_LABELS: Record<string, string> = {
  FOOD: 'categoryFood',
  BEVERAGES: 'categoryBeverages',
  SIDE_DISHES: 'categorySideDishes',
  OTHERS: 'categoryOthers',
}

function groupByCategory(items: CartItemData[]): Record<string, CartItemData[]> {
  const groups: Record<string, CartItemData[]> = {}
  for (const item of items) {
    const cat = item.category || 'OTHERS'
    if (!groups[cat]) groups[cat] = []
    groups[cat].push(item)
  }
  return groups
}

export default function CartBar({
  totalItems,
  totalPrice,
  cartItems,
  placedItems,
  categories,
  categoryLabels,
  onSubmit,
  onUpdateQuantity,
  isSubmitting,
}: CartBarProps) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language

  // Localized category header: admin's per-language label first, then English, then
  // the legacy hardcoded key, then the raw canonical name.
  const categoryLabel = (cat: string): string => {
    const labels = categoryLabels?.[cat]
    if (labels && (labels[lang] || labels.en)) return labels[lang] || labels.en
    return t(CATEGORY_LABELS[cat] || cat)
  }
  const [isExpanded, setIsExpanded] = useState(false)
  const startYRef = useRef<number | null>(null)

  const hasPlacedItems = placedItems && placedItems.length > 0

  const handleTouchStart = (e: React.TouchEvent) => {
    startYRef.current = e.touches[0].clientY
  }

  const handleTouchEnd = (e: React.TouchEvent) => {
    if (startYRef.current === null) return
    const deltaY = startYRef.current - e.changedTouches[0].clientY
    // Swipe up to expand, swipe down to collapse
    if (deltaY > 40 && !isExpanded) {
      setIsExpanded(true)
    } else if (deltaY < -40 && isExpanded) {
      setIsExpanded(false)
    }
    startYRef.current = null
  }

  const handleToggle = () => {
    setIsExpanded((prev) => !prev)
  }

  const renderGroupedItems = (
    items: CartItemData[],
    editable: boolean,
  ) => {
    const groups = groupByCategory(items)
    // Prefer the dynamic category order passed from the menu; fall back to the legacy
    // fixed order when none is provided.
    const order = categories && categories.length > 0 ? categories : CATEGORY_ORDER
    const sortedCategories = order.filter((cat) => groups[cat]?.length)
    // Also append any categories present in the cart but not in the ordered list.
    const extraCategories = Object.keys(groups).filter((cat) => !order.includes(cat))

    return [...sortedCategories, ...extraCategories].map((cat) => (
      <div key={cat} className="mb-3">
        <h4 className="mb-1 text-xs font-bold uppercase tracking-wide text-emerald-700">
          {categoryLabel(cat)}
        </h4>
        <div className="space-y-1">
          {groups[cat].map((item) => (
            <div
              key={item.id}
              className={`flex items-center justify-between rounded-lg px-3 py-2 ${
                editable ? 'bg-white' : 'bg-gray-50'
              }`}
            >
              <div className="flex-1 min-w-0">
                <span className={`text-sm ${editable ? 'text-emerald-900' : 'text-gray-500'}`}>
                  {item.name}
                </span>
                <span className={`ml-2 text-xs ${editable ? 'text-emerald-600' : 'text-gray-400'}`}>
                  {item.marketPrice ? t('marketPrice') : `RM ${item.price.toFixed(2)}`}
                </span>
                {item.note && item.note.trim() && (
                  <p className={`mt-0.5 text-xs italic ${editable ? 'text-emerald-500' : 'text-gray-400'}`}>
                    {item.note}
                  </p>
                )}
              </div>
              {editable ? (
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => onUpdateQuantity(item.id, -1)}
                    aria-label={`Decrease ${item.name}`}
                    className="flex h-7 w-7 items-center justify-center rounded-full bg-emerald-100 text-emerald-700 text-sm hover:bg-emerald-200"
                  >
                    −
                  </button>
                  <span className="w-5 text-center text-sm font-medium text-emerald-900">
                    {item.quantity}
                  </span>
                  <button
                    onClick={() => onUpdateQuantity(item.id, 1)}
                    aria-label={`Increase ${item.name}`}
                    className="flex h-7 w-7 items-center justify-center rounded-full bg-emerald-600 text-white text-sm hover:bg-emerald-700"
                  >
                    +
                  </button>
                </div>
              ) : (
                <span className="text-sm text-gray-400">×{item.quantity}</span>
              )}
            </div>
          ))}
        </div>
      </div>
    ))
  }

  const placedTotal = placedItems
    ? placedItems.reduce((sum, item) => (item.marketPrice ? sum : sum + item.price * item.quantity), 0)
    : 0

  return (
    <>
      {/* Backdrop when expanded */}
      {isExpanded && (
        <div
          className="fixed inset-0 z-30 bg-black/30"
          onClick={() => setIsExpanded(false)}
        />
      )}

      {/* Cart bar / sheet */}
      <div
        className={`fixed bottom-0 left-0 right-0 z-40 bg-white pb-safe shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.05)] transition-all duration-300 ease-in-out ${
          isExpanded ? 'top-[40%] rounded-t-2xl overflow-hidden' : 'rounded-t-xl'
        }`}
        onTouchStart={handleTouchStart}
        onTouchEnd={handleTouchEnd}
      >
        {/* Drag handle */}
        <div
          className="flex cursor-pointer items-center justify-center py-3"
          onClick={handleToggle}
          role="button"
          aria-label={isExpanded ? 'Collapse cart' : 'Expand cart'}
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') handleToggle()
          }}
        >
          <div className="h-1 w-10 rounded-full bg-gray-300" />
        </div>

        {/* Expanded content */}
        {isExpanded && (
          <div className="flex flex-col overflow-y-auto px-4 pb-4" style={{ maxHeight: 'calc(60vh - 120px)' }}>
            {hasPlacedItems && (
              <>
                <h3 className="mb-2 text-sm font-bold text-gray-500 uppercase tracking-wide">
                  {t('yourOrders')}
                </h3>
                {renderGroupedItems(placedItems, false)}

                {cartItems.length > 0 && (
                  <>
                    <hr className="my-3 border-emerald-100" />
                    <h3 className="mb-2 text-sm font-bold text-emerald-800 uppercase tracking-wide">
                      {t('addMoreItems')}
                    </h3>
                    {renderGroupedItems(cartItems, true)}
                  </>
                )}
              </>
            )}

            {!hasPlacedItems && cartItems.length > 0 && (
              <>
                <h3 className="mb-2 text-sm font-bold text-emerald-800 uppercase tracking-wide">
                  {t('yourCart')}
                </h3>
                {renderGroupedItems(cartItems, true)}
              </>
            )}

            {!hasPlacedItems && cartItems.length === 0 && (
              <p className="py-6 text-center text-sm text-gray-400">{t('cartEmpty')}</p>
            )}

            {/* Total line */}
            <div className="mt-3 flex items-center justify-between border-t border-emerald-100 pt-3">
              <span className="text-sm font-bold text-emerald-900">{t('total')}</span>
              <span className="text-lg font-bold text-emerald-900">
                RM {(totalPrice + placedTotal).toFixed(2)}
              </span>
            </div>
          </div>
        )}

        {/* Bottom bar: item count + total + Place Order button.
            The whole info area (everything except the Place Order button) is a
            single button that expands/collapses the sheet, so tapping anywhere on
            the banner works — not just the drag handle. */}
        <div className="border-t border-emerald-100 px-4 py-3">
          <div className="mx-auto flex max-w-md items-center gap-3">
            <button
              type="button"
              onClick={handleToggle}
              aria-expanded={isExpanded}
              aria-label={isExpanded ? t('collapseOrder') : t('expandOrder')}
              className="flex flex-1 items-center justify-start rounded-lg text-left transition-colors active:bg-emerald-50"
            >
              <div>
                <p className="text-xs text-emerald-600">
                  {totalItems > 0 ? t('items', { count: totalItems }) : t('cartEmpty')}
                </p>
                <div className="flex items-center gap-1.5">
                  {/* Eye affordance: shown once there's a priced order, hinting the
                      banner can be tapped to reveal the order details. */}
                  {totalItems > 0 && totalPrice > 0 && (
                    <svg
                      xmlns="http://www.w3.org/2000/svg"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth={2}
                      aria-hidden="true"
                      className="h-4 w-4 shrink-0 text-emerald-500"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        d="M2.036 12.322a1.012 1.012 0 010-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178z"
                      />
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                      />
                    </svg>
                  )}
                  <p className="text-lg font-bold text-emerald-900">
                    RM {totalPrice.toFixed(2)}
                  </p>
                </div>
              </div>
            </button>
            <button
              onClick={(e) => {
                e.stopPropagation()
                onSubmit()
              }}
              disabled={totalItems === 0 || isSubmitting}
              className={`min-h-[44px] shrink-0 rounded-full px-6 py-3 text-sm font-semibold transition active:scale-95 ${
                totalItems === 0
                  ? 'bg-gray-300 text-gray-500 cursor-not-allowed'
                  : 'bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-50'
              }`}
            >
              {isSubmitting ? '...' : t('submitOrder')}
            </button>
          </div>
        </div>
      </div>
    </>
  )
}
