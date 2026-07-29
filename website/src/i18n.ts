import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'

import en from './locales/en.json'
import bm from './locales/bm.json'
import zh from './locales/zh.json'
import ta from './locales/ta.json'
import th from './locales/th.json'

// English is the authored base. Malay / Mandarin / Tamil / Thai are dictionary-translated over it,
// with English fallback for any missing key (see specs REQ-9 / REQ-12 Gap A).
export const SUPPORTED_LANGS = ['en', 'bm', 'zh', 'ta', 'th'] as const
export type Lang = (typeof SUPPORTED_LANGS)[number]

// Default the customer UI to Bahasa Malaysia; a returning visitor's saved choice still wins,
// and any key missing from a locale falls back to English (fallbackLng below).
const stored = localStorage.getItem('lang')
const initialLang: Lang =
  stored && (SUPPORTED_LANGS as readonly string[]).includes(stored) ? (stored as Lang) : 'bm'

void i18n.use(initReactI18next).init({
  resources: {
    en: { translation: en },
    bm: { translation: bm },
    zh: { translation: zh },
    ta: { translation: ta },
    th: { translation: th },
  },
  lng: initialLang,
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
})

export function setLang(lang: Lang) {
  localStorage.setItem('lang', lang)
  void i18n.changeLanguage(lang)
}

export default i18n
