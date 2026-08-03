import { useTranslation } from 'react-i18next'
import AdSlot from './AdSlot'

export default function LoadingView() {
  const { t } = useTranslation()

  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center" role="status" aria-live="polite">
      <div className="text-center">
        <div className="mx-auto mb-4 h-10 w-10 animate-spin rounded-full border-4 border-emerald-200 border-t-emerald-600" />
        <p className="text-sm text-emerald-700">{t('loading')}</p>
      </div>
      {/* Outside the aria-live text so a screen reader announces "loading", not the ad.
          Expect this one to render rarely: the ad config is fetched asynchronously, and this view
          usually unmounts before that resolves — in which case AdSlot never gets far enough to
          claim the shared native container, and the menu's slot takes it instead. That is the
          desired outcome, not a bug: a unit that appears for half a second cannot fill anyway. */}
      <div className="mt-8 w-full max-w-md">
        <AdSlot placement="status" />
      </div>
    </div>
  )
}
