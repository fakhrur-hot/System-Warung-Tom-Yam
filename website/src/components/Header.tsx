import { useTranslation } from 'react-i18next'
import { SUPPORTED_LANGS, setLang, type Lang } from '../i18n'

const LANG_LABELS: Record<Lang, string> = {
  en: 'EN',
  bm: 'BM',
  zh: '中文',
  ta: 'தமிழ்',
  th: 'ไทย',
}

interface HeaderProps {
  cafeName?: string
  logoUrl?: string
}

export default function Header({ cafeName, logoUrl }: HeaderProps) {
  const { i18n } = useTranslation()

  return (
    <header
      className="sticky top-0 z-20 flex items-center justify-between border-b border-emerald-100 bg-white/95 px-4 py-3 backdrop-blur"
      role="banner"
    >
      <div className="flex items-center gap-2">
        {logoUrl ? (
          <img
            src={logoUrl}
            alt={cafeName || 'POS'}
            className="h-8 w-8 rounded-full object-cover"
          />
        ) : (
          <span className="text-2xl" aria-hidden="true">🍜</span>
        )}
        <h1 className="text-lg font-bold text-emerald-900">
          {cafeName || 'POS'}
        </h1>
      </div>
      <nav className="flex gap-1" aria-label="language">
        {SUPPORTED_LANGS.map((lang) => (
          <button
            key={lang}
            onClick={() => setLang(lang)}
            aria-pressed={i18n.language === lang}
            className={`min-h-[44px] min-w-[44px] rounded px-2 py-1 text-sm font-medium transition-colors ${
              i18n.language === lang
                ? 'bg-emerald-700 text-white'
                : 'text-emerald-700 hover:bg-emerald-50'
            }`}
          >
            {LANG_LABELS[lang]}
          </button>
        ))}
      </nav>
    </header>
  )
}
