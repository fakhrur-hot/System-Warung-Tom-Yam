/**
 * Provider-agnostic gateway seam: one adapter per provider, selected at runtime.
 *
 * Replaces the assumption baked into the first design — that a single aggregator with a single
 * credential set covers every channel. True for Fiuu; false for Touch 'n Go direct plus DuitNow
 * via an acquiring bank, which are two separate merchant relationships with different credential
 * shapes and a callback URL each.
 *
 * ### The rule that matters most here
 *
 * An adapter for a provider we are **not yet onboarded with must fail closed**: [charge] throws
 * and [verifyCallback] returns `false`. A `verifyCallback` that returns `true` as a placeholder is
 * an accept-anything signature check — it would let a forged "payment succeeded" POST mark orders
 * paid, for free, with no gateway involved. That is strictly worse than having no adapter at all,
 * because it looks implemented. Every stub below returns false, and [PROVIDERS] marks it
 * `AWAITING_ONBOARDING` so the settings screen can say so plainly rather than offering a channel
 * that cannot work.
 *
 * ### Money
 *
 * Amounts cross this interface as **integer minor units (sen)** — never a float, never ringgit.
 * The POS already converts exactly once, at `PaymentTransaction.fromRinggit`, which rounds rather
 * than truncates because `(19.99 * 100).toLong()` is 1998. An adapter whose provider wants ringgit
 * on the wire formats it from the integer at the edge; it does not reintroduce float arithmetic.
 */

/** One credential input a provider needs. Drives the settings screen, which renders fields from
 *  this spec rather than hardcoding a form per provider. */
export interface CredentialField {
  key: string;
  label: string;
  /** Masked in the UI and write-only: never returned to a client once stored. */
  secret: boolean;
  required: boolean;
  /** Shown under the field — e.g. where in the provider's portal to find this value. */
  hint?: string;
}

export interface ChargeRequest {
  /** Our order id. Comes back on the callback and is how a payment is matched to a transaction. */
  reference: string;
  /** Integer sen. See the money note above. */
  amountMinorUnits: number;
  currency: string;
  /** Channel identifier in the provider's own vocabulary. */
  methodCode: string;
  /** Where the provider should send the shopper / post the result. */
  callbackUrl: string;
}

export interface ChargeResult {
  /** The provider's own transaction id, once issued. */
  providerReference: string | null;
  status: "PENDING" | "SUCCESS" | "FAILED";
  /** Hosted page or redirect target, when the flow needs one. */
  redirectUrl: string | null;
}

export type ProviderStatus =
  /** Adapter implemented against a real, documented spec. */
  | "AVAILABLE"
  /** Adapter is a fail-closed placeholder: the spec arrives with merchant onboarding. */
  | "AWAITING_ONBOARDING";

export interface GatewayAdapter {
  provider: string;
  displayName: string;
  status: ProviderStatus;
  credentialFields: CredentialField[];
  /** Human-readable reason shown in settings when [status] is AWAITING_ONBOARDING. */
  unavailableReason?: string;

  charge(credentials: Record<string, string>, req: ChargeRequest): Promise<ChargeResult>;

  /** MUST return false unless the signature genuinely verifies. Never a placeholder `true`. */
  verifyCallback(
    credentials: Record<string, string>,
    // deno-lint-ignore no-explicit-any
    notification: any,
  ): Promise<boolean>;
}

/**
 * Builds an adapter that refuses to do anything until real onboarding details exist.
 *
 * The credential field names it declares are the ones the provider is *expected* to issue, so the
 * settings screen can be filled in the moment approval lands — but nothing here contacts a
 * network endpoint, because no published spec exists to contact. Writing a plausible-looking URL
 * would produce code that compiles, reviews clean, and silently cannot take money.
 */
function awaitingOnboarding(
  provider: string,
  displayName: string,
  credentialFields: CredentialField[],
  unavailableReason: string,
): GatewayAdapter {
  return {
    provider,
    displayName,
    status: "AWAITING_ONBOARDING",
    credentialFields,
    unavailableReason,
    charge: () => {
      throw new Error(
        `${displayName} is not integrated yet: ${unavailableReason} ` +
          `Complete merchant onboarding, then implement this adapter against the spec they issue.`,
      );
    },
    // Fails closed. See the class note — a placeholder `true` here accepts forged callbacks.
    verifyCallback: () => Promise.resolve(false),
  };
}

const TOUCHNGO: GatewayAdapter = awaitingOnboarding(
  "touchngo",
  "Touch 'n Go eWallet",
  [
    { key: "merchantId", label: "Merchant ID", secret: false, required: true },
    { key: "verifyKey", label: "Verify Key", secret: true, required: true },
    { key: "secretKey", label: "Secret Key", secret: true, required: true },
  ],
  "Touch 'n Go publishes no public merchant API spec — integration details are issued after " +
    "merchant approval, and most merchants reach TNG through a PSP (2C2P, Nuvei, Nomupay, HitPay).",
);

const DUITNOW: GatewayAdapter = awaitingOnboarding(
  "duitnow",
  "DuitNow (via acquiring bank)",
  [
    { key: "bank", label: "Acquiring Bank", secret: false, required: true, hint: "e.g. Maybank, CIMB" },
    { key: "clientId", label: "OAuth Client ID", secret: false, required: true },
    { key: "clientSecret", label: "OAuth Client Secret", secret: true, required: true },
  ],
  "PayNet exposes DuitNow APIs to licensed banks and payment institutions, not directly to " +
    "merchants — the endpoints and OAuth details come from whichever bank acquires you.",
);

/**
 * Fiuu is the one provider with a spec documented end-to-end in `designs.md` (F1–F6), so its
 * adapter can be implemented for real rather than stubbed. It is left wired to the existing
 * `_shared/fiuu.ts` helpers.
 *
 * Two of its formulas were inherited by convention rather than confirmed against `docs.fiuu.dev`
 * — the initiate-leg `vcode` and the requery response shape — and are flagged as such in that
 * file. Callback verification and requery signing ARE documented (F3) and are implemented exactly.
 */
const FIUU: GatewayAdapter = {
  provider: "fiuu",
  displayName: "Fiuu (formerly Razer Merchant Services)",
  status: "AVAILABLE",
  credentialFields: [
    { key: "merchantId", label: "Merchant ID", secret: false, required: true, hint: "Appears in the payment URL — not a secret." },
    { key: "verifyKey", label: "Verify Key", secret: true, required: true },
    { key: "secretKey", label: "Secret Key", secret: true, required: true },
  ],
  charge: async (credentials, req) => {
    const { buildHostedPageUrl, fiuuChannelCode } = await import("./fiuu.ts");
    const channel = fiuuChannelCode(req.methodCode);
    if (!channel) throw new Error(`Fiuu has no channel for ${req.methodCode}`);
    const url = buildHostedPageUrl({
      merchantId: credentials.merchantId,
      verifyKey: credentials.verifyKey,
      channelCode: channel,
      orderId: req.reference,
      // Formatted from the integer at the edge — no float arithmetic reintroduced.
      amountRinggit: (req.amountMinorUnits / 100).toFixed(2),
      currency: req.currency,
      isSandbox: true,
      returnUrl: req.callbackUrl,
      notifyUrl: req.callbackUrl,
      callbackUrl: req.callbackUrl,
    });
    return { providerReference: null, status: "PENDING", redirectUrl: url };
  },
  verifyCallback: async (credentials, notification) => {
    const { verifyCallbackSignature } = await import("./fiuu.ts");
    if (!credentials.secretKey) return false;
    return verifyCallbackSignature(notification, credentials.secretKey);
  },
};

export const PROVIDERS: Record<string, GatewayAdapter> = {
  fiuu: FIUU,
  touchngo: TOUCHNGO,
  duitnow: DUITNOW,
};

export function adapterFor(provider: string): GatewayAdapter | null {
  return PROVIDERS[provider] ?? null;
}

/** Provider descriptors for the settings screen — field specs and availability, never secrets. */
export function describeProviders() {
  return Object.values(PROVIDERS).map((a) => ({
    provider: a.provider,
    displayName: a.displayName,
    status: a.status,
    unavailableReason: a.unavailableReason ?? null,
    credentialFields: a.credentialFields,
  }));
}
