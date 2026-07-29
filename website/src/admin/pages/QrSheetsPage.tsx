import { useCallback, useState } from 'react'
import QRCode from 'qrcode'
import { getSupabase } from '../../lib/supabase'
import './qr-sheets-print.css'

interface TableSlot {
  id: number
  tableId: string
}

const EMPTY_SLOTS: TableSlot[] = [
  { id: 1, tableId: '' },
  { id: 2, tableId: '' },
  { id: 3, tableId: '' },
  { id: 4, tableId: '' },
]

export default function QrSheetsPage() {
  const [slots, setSlots] = useState<TableSlot[]>(EMPTY_SLOTS)
  const [cafeName, setCafeName] = useState('Warung Tom Yam')
  const [qrSvgs, setQrSvgs] = useState<Map<number, string>>(new Map())
  const [generating, setGenerating] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const updateSlot = (slotId: number, tableId: string) => {
    setSlots((prev) => prev.map((s) => (s.id === slotId ? { ...s, tableId } : s)))
  }

  const activeSlots = slots.filter((s) => s.tableId.trim() !== '')

  const generateQrCodes = useCallback(async () => {
    setGenerating(true)
    setError(null)
    const newSvgs = new Map<number, string>()

    try {
      const baseUrl = window.location.origin

      // Resolve typed table ids → their opaque QR tokens so the printed QR isn't a
      // guessable ?table=T0006. Falls back to the raw id if a token isn't found
      // (the backend resolves either form).
      const tokenById = new Map<string, string>()
      try {
        const { data } = await getSupabase().functions.invoke('tables', { method: 'GET' })
        for (const t of (data?.tables ?? []) as Array<{ id: string; qrToken?: string }>) {
          if (t.id && t.qrToken) tokenById.set(t.id, t.qrToken)
        }
      } catch {
        /* offline / no tokens — fall back to raw ids below */
      }

      for (const slot of slots) {
        if (slot.tableId.trim()) {
          const id = slot.tableId.trim()
          const qrValue = tokenById.get(id) ?? id
          const url = `${baseUrl}/order?table=${encodeURIComponent(qrValue)}`
          const svg = await QRCode.toString(url, {
            type: 'svg',
            errorCorrectionLevel: 'H',
            margin: 1,
            width: 200,
          })
          newSvgs.set(slot.id, svg)
        }
      }

      setQrSvgs(newSvgs)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to generate QR codes')
    } finally {
      setGenerating(false)
    }
  }, [slots])

  const handlePrint = () => {
    window.print()
  }

  const hasQrCodes = qrSvgs.size > 0

  return (
    <>
      {/* Screen-only UI controls */}
      <div className="qr-no-print space-y-6">
        <div className="flex items-center justify-between">
          <h1 className="text-2xl font-bold text-gray-900">QR Sheet Generator</h1>
          {hasQrCodes && (
            <button
              onClick={handlePrint}
              className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-emerald-700"
            >
              🖨️ Print
            </button>
          )}
        </div>

        <p className="text-sm text-gray-500">
          Create print-ready A4 sheets with up to 4 QR code cards (A6 size). Each card contains
          a QR code linking customers to your ordering page for a specific table.
        </p>

        {error && (
          <div className="rounded-md bg-red-50 p-3 text-sm text-red-700" role="alert">
            {error}
          </div>
        )}

        {/* Café name input */}
        <section className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
          <h2 className="mb-3 text-lg font-semibold text-gray-900">Café Name</h2>
          <input
            type="text"
            value={cafeName}
            onChange={(e) => setCafeName(e.target.value)}
            placeholder="Your café name"
            className="w-full max-w-sm rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
            aria-label="Café name for QR cards"
          />
        </section>

        {/* Table slot assignment */}
        <section className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
          <h2 className="mb-3 text-lg font-semibold text-gray-900">Assign Tables to Slots</h2>
          <p className="mb-4 text-xs text-gray-500">
            Enter the table ID/name for each card position (1–4). Leave a slot empty to skip it.
          </p>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {slots.map((slot) => (
              <div key={slot.id} className="flex items-center gap-3">
                <span className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-emerald-100 text-sm font-bold text-emerald-700">
                  {slot.id}
                </span>
                <input
                  type="text"
                  value={slot.tableId}
                  onChange={(e) => updateSlot(slot.id, e.target.value)}
                  placeholder={`Table ID (e.g., T${slot.id})`}
                  className="flex-1 rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                  aria-label={`Table for slot ${slot.id}`}
                />
              </div>
            ))}
          </div>

          <div className="mt-4 flex items-center gap-3">
            <button
              onClick={generateQrCodes}
              disabled={activeSlots.length === 0 || generating}
              className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {generating ? 'Generating...' : 'Generate QR Codes'}
            </button>
            <span className="text-xs text-gray-500">
              {activeSlots.length} of 4 slots filled
            </span>
          </div>
        </section>

        {/* Preview */}
        {hasQrCodes && (
          <section className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
            <h2 className="mb-3 text-lg font-semibold text-gray-900">Preview</h2>
            <p className="mb-4 text-xs text-gray-500">
              Below is how the printed sheet will look. Click "Print" to print or save as PDF.
            </p>
          </section>
        )}
      </div>

      {/* Print-ready A4 sheet */}
      {hasQrCodes && (
        <div className="qr-print-sheet">
          <div className="qr-a4-page">
            {/* Cut guide overlay */}
            <div className="qr-cut-guide-vertical" />
            <div className="qr-cut-guide-horizontal" />

            {/* 2×2 grid of A6 cards */}
            <div className="qr-grid">
              {slots.map((slot) => (
                <div key={slot.id} className="qr-card">
                  {slot.tableId.trim() && qrSvgs.has(slot.id) ? (
                    <div className="qr-card-content">
                      <p className="qr-cafe-name">{cafeName}</p>
                      <div
                        className="qr-code-container"
                        dangerouslySetInnerHTML={{ __html: qrSvgs.get(slot.id)! }}
                      />
                      <p className="qr-table-label">{slot.tableId.trim()}</p>
                      <p className="qr-scan-text">Scan to order</p>
                    </div>
                  ) : (
                    <div className="qr-card-empty" />
                  )}
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </>
  )
}
