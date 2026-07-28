import { useTranslation } from 'react-i18next'

export default function OccupiedView() {
  const { t } = useTranslation()

  return (
    <div className="flex min-h-[60vh] items-center justify-center px-4">
      <div className="text-center">
        <span className="mb-4 block text-5xl" aria-hidden="true">🚫</span>
        <h2 className="text-xl font-semibold text-emerald-800">{t('tableOccupied')}</h2>
        <p className="mt-2 text-sm text-emerald-600">{t('tableOccupiedDesc')}</p>
      </div>
    </div>
  )
}
