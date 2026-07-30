import { useTranslation } from 'react-i18next'

interface SplashScreenProps {
  cafeName?: string
  logoUrl?: string
}

/**
 * Branded full-screen splash shown on first load while the table, branding, and menu are
 * fetched. It carries the café logo/name plus skeleton cards (animate-pulse) so the wait
 * reads as "menu loading" rather than a blank spinner. Auto-dismisses when data arrives.
 */
export default function SplashScreen({ cafeName, logoUrl }: SplashScreenProps) {
  const { t } = useTranslation()

  return (
    <div
      className="flex min-h-screen flex-col items-center bg-emerald-50 px-6 pt-16"
      role="status"
      aria-live="polite"
    >
      <div className="flex flex-col items-center text-center">
        {logoUrl ? (
          <img
            src={logoUrl}
            alt={cafeName || ''}
            className="h-20 w-20 rounded-2xl object-cover shadow-md"
          />
        ) : (
          <span className="text-6xl" aria-hidden="true">🍜</span>
        )}
        <h1 className="mt-4 text-2xl font-extrabold tracking-tight text-emerald-800">
          {cafeName || 'POS'}
        </h1>
        <p className="mt-1 flex items-center gap-2 text-sm text-emerald-600">
          <span className="h-3 w-3 animate-spin rounded-full border-2 border-emerald-200 border-t-emerald-600" />
          {t('loading')}
        </p>
      </div>

      {/* Skeleton menu preview */}
      <div className="mt-8 w-full max-w-md space-y-3">
        {[0, 1, 2].map((i) => (
          <div key={i} className="flex gap-3 rounded-xl border border-emerald-100 bg-white p-4 shadow-sm">
            <div className="h-20 w-28 flex-shrink-0 animate-pulse rounded-md bg-emerald-100" />
            <div className="flex-1 space-y-2 py-1">
              <div className="h-4 w-2/3 animate-pulse rounded bg-emerald-100" />
              <div className="h-3 w-1/3 animate-pulse rounded bg-emerald-100" />
              <div className="mt-3 h-3 w-1/2 animate-pulse rounded bg-emerald-50" />
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
