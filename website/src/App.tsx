import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SUPPORTED_LANGS, setLang, type Lang } from './i18n'

const LANG_LABELS: Record<Lang, string> = {
  en: 'EN',
  bm: 'BM',
  zh: '中文',
  ta: 'தமிழ்',
}

/**
 * Customer ordering page — skeleton (Phase 1).
 * Reads ?table=<slug> and shows the language selector + a "coming soon" placeholder
 * until the backend is configured. The full session-aware flow arrives in Phase 4 (Task 11).
 */
export default function App() {
  const { t, i18n } = useTranslation()
  const [table, setTable] = useState<string | null>(null)

  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    setTable(params.get('table'))
  }, [])

  return (
    <div className="min-h-screen bg-neutral-50 text-neutral-900 dark:bg-neutral-950 dark:text-neutral-100">
      <header className="sticky top-0 flex items-center justify-between border-b border-neutral-200 bg-white/80 px-4 py-3 backdrop-blur dark:border-neutral-800 dark:bg-neutral-900/80">
        <h1 className="text-lg font-bold">🍜 Warung Tom Yam</h1>
        <nav className="flex gap-1" aria-label="language">
          {SUPPORTED_LANGS.map((lang) => (
            <button
              key={lang}
              onClick={() => setLang(lang)}
              aria-pressed={i18n.language === lang}
              className={`rounded px-2 py-1 text-sm ${
                i18n.language === lang
                  ? 'bg-neutral-900 text-white dark:bg-white dark:text-neutral-900'
                  : 'text-neutral-500'
              }`}
            >
              {LANG_LABELS[lang]}
            </button>
          ))}
        </nav>
      </header>

      <main className="mx-auto max-w-md px-4 py-16 text-center">
        <p className="text-2xl font-semibold">{t('comingSoon')}</p>
        <p className="mt-3 text-neutral-500">
          {table ? t('tableLabel', { table }) : t('noTable')}
        </p>
      </main>
    </div>
  )
}
