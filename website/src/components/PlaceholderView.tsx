import { useTranslation } from 'react-i18next'
import AdSlot from './AdSlot'

export default function PlaceholderView() {
  const { t } = useTranslation()

  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center gap-8 px-4">
      <div className="text-center">
        <span className="mb-4 block text-5xl" aria-hidden="true">🍳</span>
        <h2 className="text-xl font-semibold text-emerald-800">{t('comingSoon')}</h2>
        <p className="mt-2 text-sm text-emerald-600">{t('menuNotConfigured')}</p>
      </div>
      {/* Same reasoning as OccupiedView: nothing to order yet, so nothing to interrupt. */}
      <div className="w-full max-w-md">
        <AdSlot placement="status" />
      </div>
    </div>
  )
}
