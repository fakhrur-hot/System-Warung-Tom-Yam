import { useTranslation } from 'react-i18next'

interface ConfirmDialogProps {
  open: boolean
  onConfirm: () => void
  onCancel: () => void
}

export default function ConfirmDialog({ open, onConfirm, onCancel }: ConfirmDialogProps) {
  const { t } = useTranslation()

  if (!open) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div className="mx-4 w-full max-w-sm rounded-2xl bg-white p-6 shadow-xl">
        <h2 className="text-center text-lg font-semibold text-emerald-900">
          {t('confirmOrderTitle')}
        </h2>
        <div className="mt-6 flex justify-center gap-4">
          <button
            onClick={onConfirm}
            className="min-h-[44px] rounded-full bg-emerald-600 px-6 py-3 text-sm font-semibold text-white transition-colors hover:bg-emerald-700"
          >
            {t('confirmYes')}
          </button>
          <button
            onClick={onCancel}
            className="min-h-[44px] rounded-full bg-red-500 px-6 py-3 text-sm font-semibold text-white transition-colors hover:bg-red-600"
          >
            {t('confirmNo')}
          </button>
        </div>
      </div>
    </div>
  )
}
