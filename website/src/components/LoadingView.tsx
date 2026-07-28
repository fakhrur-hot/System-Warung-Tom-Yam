import { useTranslation } from 'react-i18next'

export default function LoadingView() {
  const { t } = useTranslation()

  return (
    <div className="flex min-h-[60vh] items-center justify-center" role="status" aria-live="polite">
      <div className="text-center">
        <div className="mx-auto mb-4 h-10 w-10 animate-spin rounded-full border-4 border-emerald-200 border-t-emerald-600" />
        <p className="text-sm text-emerald-700">{t('loading')}</p>
      </div>
    </div>
  )
}
