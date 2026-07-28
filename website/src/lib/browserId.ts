/**
 * Browser ID persistence — critical for iOS Safari ITP.
 * Stores UUID in BOTH localStorage AND a SameSite=Lax cookie.
 * On load: cookie first → localStorage fallback → generate fresh only if both empty.
 */

const COOKIE_NAME = 'browserId'
const LS_KEY = 'browserId'
const MAX_AGE = 31536000 // 1 year in seconds

function getCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : null
}

function setCookie(name: string, value: string): void {
  document.cookie = `${name}=${encodeURIComponent(value)}; SameSite=Lax; Secure; Max-Age=${MAX_AGE}; Path=/`
}

export function getBrowserId(): string {
  // 1. Try cookie first
  const fromCookie = getCookie(COOKIE_NAME)
  if (fromCookie) {
    // Sync to localStorage
    localStorage.setItem(LS_KEY, fromCookie)
    return fromCookie
  }

  // 2. Fall back to localStorage
  const fromLs = localStorage.getItem(LS_KEY)
  if (fromLs) {
    // Sync to cookie
    setCookie(COOKIE_NAME, fromLs)
    return fromLs
  }

  // 3. Generate fresh
  const fresh = crypto.randomUUID()
  localStorage.setItem(LS_KEY, fresh)
  setCookie(COOKIE_NAME, fresh)
  return fresh
}
