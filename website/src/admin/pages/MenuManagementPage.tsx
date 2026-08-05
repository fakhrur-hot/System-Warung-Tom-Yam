import { useCallback, useEffect, useState } from 'react'
import { getSupabase } from '../../lib/supabase'
import { SUPPORTED_LANGS, type Lang } from '../../i18n'

// ── Types (mirrors customer-facing MenuView / App.tsx contracts) ─────────────

type MenuCategoryObject = {
  name: string
  sortOrder: number
  nameI18n?: Record<string, string>
}

type MenuCategory = string | MenuCategoryObject

interface MenuItem {
  id: string
  code?: string
  category: string
  categories?: string[]
  price: number
  marketPrice?: boolean
  available: boolean
  askMeDaily: boolean
  name: Record<string, string>
  description?: Record<string, string>
  image?: string
  hasVariablePrice?: boolean
  priceOption1?: number
  priceOption2?: number
  priceOption3?: number
}

interface MenuSnapshot {
  configured?: boolean
  categories?: MenuCategory[]
  items: MenuItem[]
}

// ── Helpers ──────────────────────────────────────────────────────────────────

function categoryName(cat: MenuCategory): string {
  return typeof cat === 'string' ? cat : cat.name
}

function categorySortOrder(cat: MenuCategory): number {
  return typeof cat === 'string' ? 0 : cat.sortOrder ?? 0
}

function makeId(prefix = 'id'): string {
  return `${prefix}_${Math.random().toString(36).slice(2, 10)}_${Date.now().toString(36)}`
}

function emptyItem(): MenuItem {
  return {
    id: makeId('item'),
    category: '',
    price: 0,
    available: true,
    askMeDaily: false,
    name: { en: '' },
  }
}

function emptyCategory(): MenuCategoryObject {
  return { name: '', sortOrder: 0, nameI18n: {} }
}

// ── Component ────────────────────────────────────────────────────────────────

export default function MenuManagementPage() {
  const [snapshot, setSnapshot] = useState<MenuSnapshot | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saveSuccess, setSaveSuccess] = useState(false)

  const fetchMenu = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const supabase = getSupabase()
      const { data, error: fetchError } = await supabase.functions.invoke('menu', { method: 'GET' })

      if (fetchError) {
        setError(fetchError.message || 'Failed to load menu')
        return
      }

      const incoming = (data || { configured: false, items: [] }) as MenuSnapshot
      if (incoming.configured === false) {
        setSnapshot({ configured: true, categories: [], items: [] })
      } else {
        setSnapshot({
          configured: true,
          categories: incoming.categories || [],
          items: incoming.items || [],
        })
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to load menu')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchMenu()
  }, [fetchMenu])

  const handleSave = async () => {
    if (!snapshot) return

    // Basic client-side guard before sending.
    const invalidCategory = snapshot.categories?.some((c) => categoryName(c).trim() === '')
    if (invalidCategory) {
      setError('Every category must have a name.')
      return
    }
    const invalidItem = snapshot.items.some(
      (it) => it.category.trim() === '' || !it.name.en || it.name.en.trim() === '',
    )
    if (invalidItem) {
      setError('Every item must have a category and an English name.')
      return
    }

    setSaving(true)
    setSaveSuccess(false)
    setError(null)

    try {
      const supabase = getSupabase()
      const { error: saveError } = await supabase.functions.invoke('menu', {
        method: 'PUT',
        body: snapshot,
      })

      if (saveError) {
        setError(saveError.message || 'Failed to save menu')
      } else {
        setSaveSuccess(true)
        setTimeout(() => setSaveSuccess(false), 3000)
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to save menu')
    } finally {
      setSaving(false)
    }
  }

  // ── Category helpers ───────────────────────────────────────────────────────

  const addCategory = () => {
    setSnapshot((prev) => {
      if (!prev) return prev
      const next = [...(prev.categories || [])]
      next.push(emptyCategory())
      return { ...prev, categories: next }
    })
  }

  const updateCategory = (index: number, patch: Partial<MenuCategoryObject>) => {
    setSnapshot((prev) => {
      if (!prev) return prev
      const next = [...(prev.categories || [])]
      const current = next[index]
      if (typeof current === 'string') {
        next[index] = { name: current, sortOrder: 0, ...patch }
      } else {
        next[index] = { ...current, ...patch }
      }
      return { ...prev, categories: next }
    })
  }

  const updateCategoryI18n = (index: number, lang: string, value: string) => {
    setSnapshot((prev) => {
      if (!prev) return prev
      const next = [...(prev.categories || [])]
      const current = next[index]
      let obj: MenuCategoryObject
      if (typeof current === 'string') {
        obj = { name: current, sortOrder: 0, nameI18n: {} }
      } else {
        obj = { ...current }
      }
      obj.nameI18n = { ...obj.nameI18n, [lang]: value }
      next[index] = obj
      return { ...prev, categories: next }
    })
  }

  const removeCategory = (index: number) => {
    setSnapshot((prev) => {
      if (!prev) return prev
      const next = [...(prev.categories || [])]
      const removed = next.splice(index, 1)[0]
      const removedName = categoryName(removed)
      // Remove the category from any item that references it.
      const items = prev.items.map((it) => ({
        ...it,
        category: it.category === removedName ? '' : it.category,
        categories: it.categories?.filter((c) => c !== removedName) || undefined,
      }))
      return { ...prev, categories: next, items }
    })
  }

  // ── Item helpers ────────────────────────────────────────────────────────────

  const addItem = () => {
    setSnapshot((prev) => {
      if (!prev) return prev
      return { ...prev, items: [...prev.items, emptyItem()] }
    })
  }

  const updateItem = (index: number, patch: Partial<MenuItem>) => {
    setSnapshot((prev) => {
      if (!prev) return prev
      const next = [...prev.items]
      next[index] = { ...next[index], ...patch }
      return { ...prev, items: next }
    })
  }

  const updateItemName = (index: number, lang: Lang, value: string) => {
    setSnapshot((prev) => {
      if (!prev) return prev
      const next = [...prev.items]
      next[index] = { ...next[index], name: { ...next[index].name, [lang]: value } }
      return { ...prev, items: next }
    })
  }

  const updateItemDescription = (index: number, lang: Lang, value: string) => {
    setSnapshot((prev) => {
      if (!prev) return prev
      const next = [...prev.items]
      next[index] = {
        ...next[index],
        description: { ...(next[index].description || {}), [lang]: value },
      }
      return { ...prev, items: next }
    })
  }

  const removeItem = (index: number) => {
    setSnapshot((prev) => {
      if (!prev) return prev
      const next = [...prev.items]
      next.splice(index, 1)
      return { ...prev, items: next }
    })
  }

  // ── Render ──────────────────────────────────────────────────────────────────

  if (loading) {
    return (
      <div className="flex min-h-[40vh] items-center justify-center">
        <div
          className="h-8 w-8 animate-spin rounded-full border-4 border-emerald-200 border-t-emerald-600"
          role="status"
          aria-label="Loading menu"
        />
      </div>
    )
  }

  if (!snapshot) {
    return (
      <div className="rounded-md bg-red-50 p-4 text-sm text-red-700" role="alert">
        {error || 'Could not load menu.'}
      </div>
    )
  }

  const categoryNames = (snapshot.categories || []).map(categoryName)

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <h1 className="text-2xl font-bold text-gray-900">Menu Management</h1>
        <div className="flex items-center gap-3">
          {saveSuccess && <span className="text-sm font-medium text-emerald-700">Saved ✓</span>}
          <button
            onClick={handleSave}
            disabled={saving}
            className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save Menu'}
          </button>
        </div>
      </div>

      {error && (
        <div className="rounded-md bg-red-50 p-3 text-sm text-red-700" role="alert">
          {error}
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-[320px_1fr]">
        {/* Categories */}
        <section className="rounded-lg border border-gray-200 bg-white p-5 shadow-sm">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-lg font-semibold text-gray-900">Categories</h2>
            <button
              onClick={addCategory}
              className="rounded-md bg-emerald-50 px-2.5 py-1.5 text-sm font-medium text-emerald-700 transition-colors hover:bg-emerald-100"
            >
              + Add
            </button>
          </div>

          <div className="space-y-3">
            {(snapshot.categories || []).length === 0 && (
              <p className="text-sm text-gray-500">No categories yet.</p>
            )}
            {(snapshot.categories || []).map((cat, idx) => (
              <div key={idx} className="rounded-md border border-gray-200 p-3">
                <div className="mb-2 flex items-center gap-2">
                  <input
                    type="text"
                    value={categoryName(cat)}
                    onChange={(e) => updateCategory(idx, { name: e.target.value })}
                    placeholder="Category ID"
                    className="flex-1 rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                  />
                  <input
                    type="number"
                    value={categorySortOrder(cat)}
                    onChange={(e) => updateCategory(idx, { sortOrder: parseInt(e.target.value || '0', 10) })}
                    className="w-16 rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                    aria-label="Sort order"
                  />
                  <button
                    onClick={() => removeCategory(idx)}
                    className="rounded-md px-2 py-1.5 text-sm text-red-600 transition-colors hover:bg-red-50"
                    aria-label="Remove category"
                  >
                    ×
                  </button>
                </div>
                <div className="space-y-1.5">
                  {SUPPORTED_LANGS.map((lang) => (
                    <div key={lang} className="flex items-center gap-2">
                      <span className="w-6 text-xs font-medium uppercase text-gray-500">{lang}</span>
                      <input
                        type="text"
                        value={(typeof cat === 'object' ? cat.nameI18n?.[lang] : '') || ''}
                        onChange={(e) => updateCategoryI18n(idx, lang, e.target.value)}
                        placeholder={`Display name (${lang})`}
                        className="flex-1 rounded-md border border-gray-300 px-2 py-1 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                      />
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </section>

        {/* Items */}
        <section className="rounded-lg border border-gray-200 bg-white p-5 shadow-sm">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-lg font-semibold text-gray-900">Items ({snapshot.items.length})</h2>
            <button
              onClick={addItem}
              className="rounded-md bg-emerald-600 px-3 py-1.5 text-sm font-semibold text-white transition-colors hover:bg-emerald-700"
            >
              + Add Item
            </button>
          </div>

          <div className="space-y-4">
            {snapshot.items.length === 0 && (
              <p className="text-sm text-gray-500">No items yet.</p>
            )}
            {snapshot.items.map((item, idx) => (
              <div key={item.id} className="rounded-lg border border-gray-200 p-4">
                <div className="mb-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                  <div className="sm:col-span-2">
                    <label className="mb-1 block text-xs font-medium text-gray-600">English name *</label>
                    <input
                      type="text"
                      value={item.name.en || ''}
                      onChange={(e) => updateItemName(idx, 'en', e.target.value)}
                      className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                    />
                  </div>

                  <div>
                    <label className="mb-1 block text-xs font-medium text-gray-600">Category *</label>
                    <select
                      value={item.category}
                      onChange={(e) => updateItem(idx, { category: e.target.value })}
                      className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                    >
                      <option value="">— select —</option>
                      {categoryNames.map((c) => (
                        <option key={c} value={c}>
                          {c}
                        </option>
                      ))}
                    </select>
                  </div>

                  <div>
                    <label className="mb-1 block text-xs font-medium text-gray-600">Code</label>
                    <input
                      type="text"
                      value={item.code || ''}
                      onChange={(e) => updateItem(idx, { code: e.target.value })}
                      className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                    />
                  </div>

                  <div>
                    <label className="mb-1 block text-xs font-medium text-gray-600">Base price (RM)</label>
                    <input
                      type="number"
                      min="0"
                      step="0.01"
                      value={item.price}
                      onChange={(e) => updateItem(idx, { price: parseFloat(e.target.value || '0') })}
                      className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                    />
                  </div>

                  <div>
                    <label className="mb-1 block text-xs font-medium text-gray-600">Image URL</label>
                    <input
                      type="text"
                      value={item.image || ''}
                      onChange={(e) => updateItem(idx, { image: e.target.value })}
                      placeholder="https://…"
                      className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                    />
                  </div>

                  <div className="flex items-end gap-4">
                    <label className="flex items-center gap-2 text-sm text-gray-700">
                      <input
                        type="checkbox"
                        checked={item.available}
                        onChange={(e) => updateItem(idx, { available: e.target.checked })}
                        className="h-4 w-4 rounded border-gray-300 text-emerald-600 focus:ring-emerald-500"
                      />
                      Available
                    </label>
                    <label className="flex items-center gap-2 text-sm text-gray-700">
                      <input
                        type="checkbox"
                        checked={item.askMeDaily}
                        onChange={(e) => updateItem(idx, { askMeDaily: e.target.checked })}
                        className="h-4 w-4 rounded border-gray-300 text-emerald-600 focus:ring-emerald-500"
                      />
                      Ask daily
                    </label>
                    <label className="flex items-center gap-2 text-sm text-gray-700">
                      <input
                        type="checkbox"
                        checked={item.marketPrice || false}
                        onChange={(e) => updateItem(idx, { marketPrice: e.target.checked })}
                        className="h-4 w-4 rounded border-gray-300 text-emerald-600 focus:ring-emerald-500"
                      />
                      Market price
                    </label>
                  </div>
                </div>

                {/* Translated names */}
                <details className="group mb-3">
                  <summary className="cursor-pointer text-sm font-medium text-emerald-700 hover:text-emerald-800">
                    Translated names & descriptions
                  </summary>
                  <div className="mt-3 grid gap-3 sm:grid-cols-2">
                    {SUPPORTED_LANGS.filter((l) => l !== 'en').map((lang) => (
                      <div key={lang}>
                        <label className="mb-1 block text-xs font-medium text-gray-600">{lang} name</label>
                        <input
                          type="text"
                          value={item.name[lang] || ''}
                          onChange={(e) => updateItemName(idx, lang, e.target.value)}
                          className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                        />
                        <label className="mb-1 mt-2 block text-xs font-medium text-gray-600">{lang} description</label>
                        <input
                          type="text"
                          value={item.description?.[lang] || ''}
                          onChange={(e) => updateItemDescription(idx, lang, e.target.value)}
                          className="w-full rounded-md border border-gray-300 px-2.5 py-1.5 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                        />
                      </div>
                    ))}
                  </div>
                </details>

                {/* Variable prices */}
                <details className="group">
                  <summary className="cursor-pointer text-sm font-medium text-emerald-700 hover:text-emerald-800">
                    Variable sizes (S / M / L)
                  </summary>
                  <div className="mt-3 flex flex-wrap items-center gap-3">
                    <label className="flex items-center gap-2 text-sm text-gray-700">
                      <input
                        type="checkbox"
                        checked={item.hasVariablePrice || false}
                        onChange={(e) => updateItem(idx, { hasVariablePrice: e.target.checked })}
                        className="h-4 w-4 rounded border-gray-300 text-emerald-600 focus:ring-emerald-500"
                      />
                      Has sizes
                    </label>
                    {(['S', 'M', 'L'] as const).map((size) => {
                      const field: 'priceOption1' | 'priceOption2' | 'priceOption3' =
                        size === 'S' ? 'priceOption1' : size === 'M' ? 'priceOption2' : 'priceOption3'
                      const priceValue = item[field]
                      return (
                        <div key={size} className="flex items-center gap-2">
                          <span className="text-sm font-medium text-gray-600">{size}</span>
                          <input
                            type="number"
                            min="0"
                            step="0.01"
                            value={typeof priceValue === 'number' ? priceValue : ''}
                            onChange={(e) => updateItem(idx, { [field]: parseFloat(e.target.value || '0') })}
                            disabled={!item.hasVariablePrice}
                            className="w-20 rounded-md border border-gray-300 px-2 py-1 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500 disabled:bg-gray-100"
                          />
                        </div>
                      )
                    })}
                  </div>
                </details>

                <div className="mt-3 flex justify-end">
                  <button
                    onClick={() => removeItem(idx)}
                    className="rounded-md px-3 py-1.5 text-sm text-red-600 transition-colors hover:bg-red-50"
                  >
                    Remove item
                  </button>
                </div>
              </div>
            ))}
          </div>
        </section>
      </div>
    </div>
  )
}
