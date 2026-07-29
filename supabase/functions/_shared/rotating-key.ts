/**
 * Rotating key derivation.
 * HMAC-SHA256(ROTATING_KEY_SECRET, floor(unixTime / 30)) → first 6 digits.
 */

/**
 * Derive a 6-digit rotating key for a given time window.
 */
export async function deriveRotatingKey(
  secret: string,
  windowIndex: number
): Promise<string> {
  const encoder = new TextEncoder();
  const keyData = encoder.encode(secret);
  const message = encoder.encode(String(windowIndex));

  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    keyData,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const signature = await crypto.subtle.sign("HMAC", cryptoKey, message);
  const hex = Array.from(new Uint8Array(signature))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");

  // Extract first 6 digits from the hex string
  const digits = hex.replace(/[^0-9]/g, "");
  return digits.slice(0, 6).padStart(6, "0");
}

/**
 * Get the current window index (30-second epochs).
 */
export function getCurrentWindow(nowSeconds?: number): number {
  const now = nowSeconds ?? Math.floor(Date.now() / 1000);
  return Math.floor(now / 30);
}

/**
 * Get the current rotating key and its expiry info.
 */
export async function getRotatingKeyInfo(secret: string): Promise<{
  key: string;
  expiresInSeconds: number;
}> {
  const nowSeconds = Math.floor(Date.now() / 1000);
  const window = getCurrentWindow(nowSeconds);
  const key = await deriveRotatingKey(secret, window);
  const expiresInSeconds = 30 - (nowSeconds % 30);
  return { key, expiresInSeconds };
}

/**
 * Timing-safe comparison for strings.
 */
function timingSafeEqual(a: string, b: string): boolean {
  const aBytes = new TextEncoder().encode(a);
  const bBytes = new TextEncoder().encode(b);
  if (aBytes.length !== bBytes.length) return false;
  let diff = 0;
  for (let i = 0; i < aBytes.length; i++) {
    diff |= aBytes[i] ^ bBytes[i];
  }
  return diff === 0;
}

/**
 * Validate a rotating key against current ±1 window (60s grace).
 * Returns true if the key matches any of the 3 windows.
 */
export async function validateRotatingKey(
  secret: string,
  candidateKey: string
): Promise<boolean> {
  const window = getCurrentWindow();
  for (const offset of [0, -1, 1]) {
    const key = await deriveRotatingKey(secret, window + offset);
    if (timingSafeEqual(candidateKey, key)) return true;
  }
  return false;
}
