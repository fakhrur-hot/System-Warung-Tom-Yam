import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import jsQR from 'jsqr'

interface QrScannerProps {
  /** Called with the decoded table id once a valid table QR is detected. */
  onDetected: (tableId: string) => void
  /** Called when the customer closes the scanner without scanning. */
  onClose: () => void
}

/**
 * Full-screen camera QR scanner for the customer ordering page.
 *
 * The table QR codes encode a URL like `.../order?table=<id>` (see the admin QR
 * sheets). Historically the "scan" prompts on the site were just text with no way
 * to actually scan — the customer had to leave and use their phone's native camera
 * app. This opens the device camera in-page (triggering the browser's camera
 * permission prompt), decodes frames with jsQR, pulls the `table` param out of the
 * scanned URL, and hands it back so the app can load that table's menu.
 */
export default function QrScanner({ onDetected, onClose }: QrScannerProps) {
  const { t } = useTranslation()
  const videoRef = useRef<HTMLVideoElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const rafRef = useRef<number | null>(null)
  const doneRef = useRef(false)
  // Keep the latest onDetected without restarting the camera on every parent render.
  const onDetectedRef = useRef(onDetected)
  onDetectedRef.current = onDetected

  const [error, setError] = useState<'denied' | 'unsupported' | 'notATable' | null>(null)

  useEffect(() => {
    let cancelled = false

    const stop = () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current)
      rafRef.current = null
      streamRef.current?.getTracks().forEach((track) => track.stop())
      streamRef.current = null
    }

    const tick = () => {
      const video = videoRef.current
      const canvas = canvasRef.current
      if (!video || !canvas || doneRef.current || cancelled) return

      if (video.readyState === video.HAVE_ENOUGH_DATA) {
        const w = video.videoWidth
        const h = video.videoHeight
        if (w && h) {
          canvas.width = w
          canvas.height = h
          const ctx = canvas.getContext('2d', { willReadFrequently: true })
          if (ctx) {
            ctx.drawImage(video, 0, 0, w, h)
            const imageData = ctx.getImageData(0, 0, w, h)
            const code = jsQR(imageData.data, w, h, { inversionAttempts: 'dontInvert' })
            if (code?.data) {
              const tableId = extractTable(code.data)
              if (tableId) {
                doneRef.current = true
                stop()
                onDetectedRef.current(tableId)
                return
              }
              // A QR was read but it isn't one of our table codes — nudge the user
              // and keep scanning rather than silently ignoring it.
              setError('notATable')
            }
          }
        }
      }
      rafRef.current = requestAnimationFrame(tick)
    }

    const start = async () => {
      if (!navigator.mediaDevices?.getUserMedia) {
        setError('unsupported')
        return
      }
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: { ideal: 'environment' } },
          audio: false,
        })
        if (cancelled) {
          stream.getTracks().forEach((track) => track.stop())
          return
        }
        streamRef.current = stream
        const video = videoRef.current
        if (!video) return
        video.srcObject = stream
        video.setAttribute('playsinline', 'true')
        await video.play().catch(() => {})
        rafRef.current = requestAnimationFrame(tick)
      } catch {
        setError('denied')
      }
    }

    start()
    return () => {
      cancelled = true
      stop()
    }
  }, [])

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-black">
      {/* Camera feed */}
      <video ref={videoRef} className="absolute inset-0 h-full w-full object-cover" muted playsInline />
      <canvas ref={canvasRef} className="hidden" />

      {/* Dimmed overlay + scan reticle */}
      <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
        <div className="h-60 w-60 rounded-2xl border-4 border-white/90 shadow-[0_0_0_9999px_rgba(0,0,0,0.5)]" />
      </div>

      {/* Header + hint */}
      <div className="relative z-10 flex items-start justify-between px-4 pt-[max(1rem,env(safe-area-inset-top))]">
        <h2 className="max-w-[75%] text-base font-semibold text-white drop-shadow">
          {t('scanQrTitle')}
        </h2>
        <button
          onClick={onClose}
          aria-label={t('closeScanner')}
          className="min-h-[44px] min-w-[44px] rounded-full bg-white/20 text-2xl leading-none text-white backdrop-blur"
        >
          ✕
        </button>
      </div>

      <div className="relative z-10 mt-auto px-6 pb-[max(2rem,env(safe-area-inset-bottom))] text-center">
        {error === 'denied' && (
          <p className="mx-auto max-w-sm rounded-xl bg-black/60 px-4 py-3 text-sm text-white">
            {t('cameraDenied')}
          </p>
        )}
        {error === 'unsupported' && (
          <p className="mx-auto max-w-sm rounded-xl bg-black/60 px-4 py-3 text-sm text-white">
            {t('cameraUnsupported')}
          </p>
        )}
        {error === 'notATable' && (
          <p className="mx-auto max-w-sm rounded-xl bg-black/60 px-4 py-3 text-sm text-white">
            {t('scanQrNotTable')}
          </p>
        )}
        {!error && (
          <p className="mx-auto max-w-sm rounded-xl bg-black/50 px-4 py-2 text-sm text-white">
            {t('scanQrHint')}
          </p>
        )}
      </div>
    </div>
  )
}

/**
 * Pull the table id out of whatever the QR encoded. Table QRs hold a full URL with
 * a `?table=<id>` param, but we also accept a bare `table=<id>` fragment or a plain
 * short token so a slightly different QR format still works. Anything else returns
 * null (treated as "not one of our codes").
 */
function extractTable(text: string): string | null {
  try {
    const url = new URL(text)
    const param = url.searchParams.get('table')
    if (param) return param
  } catch {
    /* not a URL — fall through */
  }
  const match = text.match(/[?&]table=([^&\s]+)/i)
  if (match) return decodeURIComponent(match[1])
  const trimmed = text.trim()
  if (/^[A-Za-z0-9_-]{1,24}$/.test(trimmed)) return trimmed
  return null
}
