import { useTranslation } from 'react-i18next'
import AdSlot from './AdSlot'

export default function OccupiedView() {
  const { t } = useTranslation()

  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center gap-8 px-4">
      <div className="text-center">
        <span className="mb-4 block text-5xl" aria-hidden="true">🚫</span>
        <h2 className="text-xl font-semibold text-emerald-800">{t('tableOccupied')}</h2>
        <p className="mt-2 text-sm text-emerald-600">{t('tableOccupiedDesc')}</p>
      </div>
      {/* Dead-end state with real dwell time: the customer cannot order until the table frees, so
          there is no ordering flow left to interrupt. Below the message, never above it — the
          reason they are stuck is the thing they came to read. */}
      <div className="w-full max-w-md">
        <AdSlot placement="status" />
      </div>
    </div>
  )
}
