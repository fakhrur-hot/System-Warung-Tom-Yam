/**
 * Fiuu (formerly Razer Merchant Services / MOLPay) gateway adapter. (designs.md F1–F6, task 6.2)
 *
 * Isolated behind this module — not inlined into the payment-initiate / payment-query /
 * payment-callback handlers — so a different acquirer can be swapped in later by replacing this
 * file alone (Design Principle 1: "the POS talks to one gateway client, not individual e-wallet
 * APIs").
 *
 * ### What is confirmed here, and what is not
 *
 * designs.md F3 gives the callback-verification and requery signature formulas precisely, and F4
 * gives the callback ACK contract precisely — those are implemented as documented. F6 lists open
 * questions the design explicitly says still need the acquirer's answer before going live,
 * including **which signature version applies (MD5 here, vs. a v2 HMAC-SHA256)** and whether a
 * seamless dynamic-QR API exists at all. This project's design excerpt does not carry a confirmed
 * request-signing formula for the *initiate* leg (only for callback-verify and requery) —
 * [buildHostedPageUrl]'s `vcode` follows the long-standing MOLPay/Razer "Seamless" hosted-page
 * formula that this aggregator's `/RMS/…` paths descend from, but it is inherited by pattern, not
 * confirmed against `docs.fiuu.dev`. Same caveat for [parseRequeryResponse]'s response shape.
 * **Confirm both against the acquirer's current documentation before taking a live payment.**
 */
import { md5 } from "https://esm.sh/js-md5@0.8.3";

export const FIUU_HOSTS = {
  hostedPage: {
    live: "https://pay.fiuu.com/RMS/pay",
    sandbox: "https://sandbox-payment.fiuu.com/RMS/pay",
  },
  // designs.md F2 — status requery, one host regardless of sandbox.
  requery: "https://api.fiuu.com/RMS/API/gate-query/index.php",
} as const;

/** App-neutral PaymentMethod.code (apk/data/local/PaymentMethod.kt) → Fiuu channel code. (F1) */
const CHANNEL_CODES: Record<string, string> = {
  DUITNOW_QR: "RPP_DuitNowQR",
  TNG: "TNG-EWALLET",
  GRABPAY: "GrabPay",
  BOOST: "BOOST",
  SHOPEEPAY: "ShopeePay",
  FPX: "fpx",
  CARD: "credit",
};

/** Null when this app's method code has no Fiuu channel — CASH and STATIC_QR never reach here. */
export function fiuuChannelCode(paymentMethodCode: string): string | null {
  return CHANNEL_CODES[paymentMethodCode] ?? null;
}

/**
 * Hosted payment page URL for a new attempt.
 *
 * Every channel Fiuu documents (F1, F6 #1) is a **hosted page**, not a seamless API that returns a
 * QR payload to render ourselves — so this always produces a `checkoutUrl`, never a `qrString`.
 * That is a real UX gap against designs.md Screen 3 (a POS-rendered QR); it stays open per F6 #1
 * until the acquirer confirms otherwise.
 */
export function buildHostedPageUrl(params: {
  merchantId: string;
  verifyKey: string;
  channelCode: string;
  orderId: string;
  /** Ringgit with 2 decimals, e.g. "25.50" — Fiuu's hosted page takes ringgit, not sen. */
  amountRinggit: string;
  currency: string;
  isSandbox: boolean;
  returnUrl: string;
  notifyUrl: string;
  callbackUrl: string;
}): string {
  const host = params.isSandbox
    ? FIUU_HOSTS.hostedPage.sandbox
    : FIUU_HOSTS.hostedPage.live;

  // Inherited MOLPay/Razer "Seamless" formula — see the module-level caveat. Not confirmed for Fiuu.
  const vcode = md5(
    `${params.amountRinggit}${params.merchantId}${params.orderId}${params.verifyKey}`,
  );

  const qs = new URLSearchParams({
    amount: params.amountRinggit,
    orderid: params.orderId,
    currency: params.currency,
    vcode,
    returnurl: params.returnUrl,
    notifyurl: params.notifyUrl,
    callbackurl: params.callbackUrl,
  });

  return `${host}/${encodeURIComponent(params.merchantId)}/${params.channelCode}?${qs.toString()}`;
}

/** Requery signature: skey = md5(txID + domain + verifyKey + amount). (F3) */
export function requerySignature(
  txId: string,
  domain: string,
  verifyKey: string,
  amountRinggit: string,
): string {
  return md5(`${txId}${domain}${verifyKey}${amountRinggit}`);
}

/**
 * Verify a callback/notification's `skey` against the two-round MD5 in F3:
 *
 * ```
 * key0 = md5(tranID + orderid + status + domain + amount + currency)
 * key1 = md5(paydate + domain + key0 + appcode + secretKey)
 * ```
 *
 * A mismatch must be treated as a failed callback and must never mark a payment successful — this
 * is the entire defence against a forged "payment succeeded" POST. (F3)
 */
export function verifyCallbackSignature(
  payload: {
    tranId: string;
    orderId: string;
    status: string;
    domain: string;
    amount: string;
    currency: string;
    paydate: string;
    appcode: string;
    skey: string;
  },
  secretKey: string,
): boolean {
  const key0 = md5(
    `${payload.tranId}${payload.orderId}${payload.status}${payload.domain}${payload.amount}${payload.currency}`,
  );
  const key1 = md5(`${payload.paydate}${payload.domain}${key0}${payload.appcode}${secretKey}`);
  return timingSafeEqual(key1, payload.skey);
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** Fiuu status codes on requery/callback: '00' success, '11' failure, '22' pending. (F5) */
export function mapFiuuStatus(statCode: string): "SUCCESS" | "FAILED" | "PENDING" {
  switch (statCode) {
    case "00":
      return "SUCCESS";
    case "11":
      return "FAILED";
    default:
      return "PENDING";
  }
}

/**
 * Extract a `statcode` (or `status`) field from the requery response body.
 *
 * **Response shape is not given in this project's design excerpt** — F2 documents only the
 * `gate-query/index.php` URL, not its reply format. This assumes the common MOLPay/Fiuu
 * name=value convention; confirm against `docs.fiuu.dev` and adjust before relying on it (F6).
 */
export function parseRequeryResponse(raw: string): string {
  const match = raw.match(/stat(?:us|code)\s*[=:]\s*['"]?(\d+)/i);
  return match ? match[1] : "22";
}
