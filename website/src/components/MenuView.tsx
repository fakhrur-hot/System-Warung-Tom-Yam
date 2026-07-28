import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import CartBar from './CartBar'
import type { Lang } from '../i18n'

// Types matching the API contract
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
  image?: string
}

interface CartItem {
  menuItemId: string
  quantity: number
  note: string
}

interface PlacedItemData {
  id: string
  name: string
  quantity: number
  price: number
  marketPrice?: boolean
  category: string
}

interface MenuViewProps {
  items: MenuItem[]
  categories: string[]
  onSubmitOrder: (items: CartItem[]) => void
  isSubmitting: boolean
  placedItems?: PlacedItemData[]
}

const CATEGORY_KEYS: Record<string, string> = {
  FOOD: 'categoryFood',
  BEVERAGES: 'categoryBeverages',
  SIDE_DISHES: 'categorySideDishes',
  OTHERS: 'categoryOthers',
}

export default function MenuView({ items, categories, onSubmitOrder, isSubmitting, placedItems }: MenuViewProps) {
  const { t, i18n } = useTranslation()
  const [activeCategory, setActiveCategory] = useState(categories[0] || 'FOOD')
  const [cart, setCart] = useState<Record<string, { quantity: number; note: string }>>({})
  const [noteItemId, setNoteItemId] = useState<string | null>(null)

  const lang = i18n.language as Lang

  const getItemName = (item: MenuItem): string => {
    if (item.name.doNotTranslate) return item.name.en || ''
    return item.name[lang] || item.name.en || ''
  }

  const getItemDescription = (item: MenuItem): string => {
    if (!item.description) return ''
    return item.description[lang] || item.description.en || ''
  }

  const filteredItems = items.filter((item) => item.category === activeCategory)
  const [previewImage, setPreviewImage] = useState<string | null>(null)

  const updateQuantity = (id: string, delta: number) => {
    setCart((prev) => {
      const current = prev[id] || { quantity: 0, note: '' }
      const next = Math.max(0, current.quantity + delta)
      if (next === 0) {
        const updated = { ...prev }
        delete updated[id]
        return updated
      }
      return { ...prev, [id]: { ...current, quantity: next } }
    })
  }

  const updateNote = (id: string, note: string) => {
    setCart((prev) => {
      const current = prev[id] || { quantity: 1, note: '' }
      return { ...prev, [id]: { ...current, note } }
    })
  }

  const totalItems = Object.values(cart).reduce((sum, c) => sum + c.quantity, 0)
  // Market-price items have no numeric amount — they contribute 0 to the subtotal.
  const totalPrice = Object.entries(cart).reduce((sum, [id, c]) => {
    const item = items.find((m) => m.id === id)
    if (!item || item.marketPrice) return sum
    return sum + (item.price || 0) * c.quantity
  }, 0)

  // Build cart items with full details for the CartBar expanded view
  const cartItemsDetailed = Object.entries(cart).map(([id, c]) => {
    const menuItem = items.find((m) => m.id === id)
    return {
      id,
      name: menuItem ? getItemName(menuItem) : id,
      quantity: c.quantity,
      price: menuItem?.price || 0,
      marketPrice: menuItem?.marketPrice || false,
      category: menuItem?.category || 'OTHERS',
    }
  })

  const handleSubmit = () => {
    const orderItems: CartItem[] = Object.entries(cart).map(([menuItemId, c]) => ({
      menuItemId,
      quantity: c.quantity,
      note: c.note,
    }))
    onSubmitOrder(orderItems)
  }

  return (
    <div className="pb-28">
      {/* Category tabs - sticky below header */}
      <div
        className="sticky top-[57px] z-10 border-b border-emerald-100 bg-white/95 backdrop-blur"
        role="tablist"
        aria-label={t('menu')}
      >
        <div className="mx-auto flex max-w-md overflow-x-auto px-4">
          {categories.map((cat) => (
            <button
              key={cat}
              role="tab"
              aria-selected={activeCategory === cat}
              onClick={() => setActiveCategory(cat)}
              className={`min-h-[44px] whitespace-nowrap px-4 py-3 text-sm font-medium transition-colors ${
                activeCategory === cat
                  ? 'border-b-2 border-emerald-600 text-emerald-700'
                  : 'text-emerald-500 hover:text-emerald-700'
              }`}
            >
              {t(CATEGORY_KEYS[cat] || cat)}
            </button>
          ))}
        </div>
      </div>

      {/* Item list */}
      <div className="mx-auto max-w-md px-4 py-4">
        <div className="space-y-3">
          {filteredItems.map((item) => {
            const qty = cart[item.id]?.quantity || 0
            const note = cart[item.id]?.note || ''
            const isUnavailable = !item.available

              return (
              <div
                key={item.id}
                className={`rounded-xl border p-4 ${
                  isUnavailable
                    ? 'border-gray-200 bg-gray-50 opacity-60'
                    : 'border-emerald-100 bg-white shadow-sm'
                }`}
              >
                <div className="flex items-start justify-between gap-3">
                  {item.image && (
                    <img
                      src={item.image}
                      alt={getItemName(item)}
                      onClick={() => setPreviewImage(item.image || null)}
                      style={{ width: '160px', height: '128px', objectFit: 'cover', aspectRatio: '5/4', cursor: 'pointer' }}
                      className="rounded-md mr-3 flex-shrink-0"
                    />
                  )}
                  <div className="flex-1">
                    <h3 className={`font-semibold ${isUnavailable ? 'text-gray-400' : 'text-emerald-900'}`}>
                      {getItemName(item)}
                    </h3>
                    {getItemDescription(item) && (
                      <p className="mt-0.5 text-xs text-emerald-600 line-clamp-2">
                        {getItemDescription(item)}
                      </p>
                    )}
                    <p className={`mt-1 text-sm font-medium ${isUnavailable ? 'text-gray-400' : 'text-emerald-700'}`}>
                      {item.marketPrice ? t('marketPrice') : `RM ${item.price.toFixed(2)}`}
                    </p>
                    {isUnavailable && (
                      <span className="mt-1 inline-block text-xs text-gray-400">{t('unavailable')}</span>
                    )}
                  </div>
                  {!isUnavailable && (
                    <div className="flex items-center gap-2">
                      {qty > 0 ? (
                        <>
                          <button
                            onClick={() => updateQuantity(item.id, -1)}
                            aria-label={`Decrease ${getItemName(item)}`}
                            className="flex h-9 w-9 items-center justify-center rounded-full bg-emerald-100 text-emerald-700 transition-colors hover:bg-emerald-200"
                          >
                            −
                          </button>
                          <span className="w-5 text-center font-medium text-emerald-900">{qty}</span>
                          <button
                            onClick={() => updateQuantity(item.id, 1)}
                            aria-label={`Increase ${getItemName(item)}`}
                            className="flex h-9 w-9 items-center justify-center rounded-full bg-emerald-600 text-white transition-colors hover:bg-emerald-700"
                          >
                            +
                          </button>
                        </>
                      ) : (
                        <button
                          onClick={() => updateQuantity(item.id, 1)}
                          aria-label={`Add ${getItemName(item)} to cart`}
                          className="min-h-[44px] rounded-full bg-emerald-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-emerald-700"
                        >
                          {t('addToCart')}
                        </button>
                      )}
                    </div>
                  )}
                </div>
                {/* Note input for items in cart */}
                {qty > 0 && (
                  <div className="mt-2">
                    {noteItemId === item.id ? (
                      <input
                        type="text"
                        value={note}
                        onChange={(e) => updateNote(item.id, e.target.value)}
                        onBlur={() => setNoteItemId(null)}
                        placeholder={t('itemNote')}
                        autoFocus
                        className="w-full rounded-lg border border-emerald-200 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                      />
                    ) : (
                      <button
                        onClick={() => setNoteItemId(item.id)}
                        className="text-xs text-emerald-500 hover:text-emerald-700"
                      >
                        {note || `+ ${t('itemNote')}`}
                      </button>
                    )}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>

      <CartBar
        totalItems={totalItems}
        totalPrice={totalPrice}
        cartItems={cartItemsDetailed}
        placedItems={placedItems}
        categories={categories}
        onSubmit={handleSubmit}
        onUpdateQuantity={updateQuantity}
        isSubmitting={isSubmitting}
      />
          {previewImage && (
            <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70" onClick={() => setPreviewImage(null)}>
              <div className="max-w-lg p-4">
                <img src={previewImage} alt="Preview" style={{ width: '100%', aspectRatio: '1/1', objectFit: 'cover' }} className="rounded" />
              </div>
            </div>
          )}
        </div>
      )
    }
