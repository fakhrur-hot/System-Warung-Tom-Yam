package com.razstudio.pos.data

/**
 * What each [OperatingMode] is allowed to do (Requirements 1.3, 7.5).
 *
 * The point of this type is that mode-dependent behaviour is decided in **exactly one place**. No
 * feature should infer its topology some other way — in particular, nothing should test "is a
 * Supabase URL configured?" as a proxy for "am I in Cloud Mode", because those can disagree (a device
 * that has been switched to LAN Mode has its Supabase settings cleared, but a half-finished Setup
 * could leave either in any state).
 *
 * Adding a fourth mode later should mean adding one row to [toCapabilities] and nothing else.
 *
 * ### The Payment QR is deliberately absent from this type
 * It is available in **all three** modes (Requirement 14.7). Giving it a capability flag would invite
 * someone to switch it off for LAN/Kiosk alongside the genuinely cloud-shaped image features, which
 * would be wrong. Its visibility depends only on whether an image has been configured — never on the
 * operating mode.
 */
data class ModeCapabilities(
    /** The customer-facing web ordering flow. */
    val customerQrOrdering: Boolean,
    /** Printable per-table QR sheets (`QrPdfScreen` / `QrPdfViewModel`) — only useful with 14.7's web flow. */
    val printableQrSheets: Boolean,
    /** Table grid, table selection, table management. Kiosk has no tables at all (Requirement 3.2). */
    val tables: Boolean,
    /** Devices screen, pairing, approval. Kiosk has no peers (Requirement 3.4). */
    val staffDevices: Boolean,
    /**
     * The `ADMIN_SECONDARY` role. Explicitly out of scope for LAN and Kiosk in this spec — LAN Mode is
     * one `ADMIN` server plus N `ORDERING` clients. This is a flag rather than an undocumented gap so
     * the exclusion is visible and enforced in one place.
     */
    val secondaryAdmin: Boolean,
    /** `https://…/join?invite=` deep-link invitations — impossible without a website (Requirement 7.2). */
    val websiteInvites: Boolean,
    /** Cloud object-storage image upload, versus storing images as local files (Requirement 7.3). */
    val cloudImageHosting: Boolean,
    /**
     * Whether to start the Supabase Realtime WebSocket. False off-cloud: the socket URL is built by
     * string-replacing `https://` with `wss://`, which yields an unusable URL for an `http://` LAN
     * host and would retry on backoff forever (Requirement 6.2). Costs nothing to disable — live
     * behaviour already runs off the periodic catch-up poll.
     */
    val realtimeWebSocket: Boolean,
    /**
     * Whether gateway payment methods (DuitNow QR, e-wallets, FPX, Card) are available at checkout.
     *
     * False in LAN and Kiosk: every gateway flow requires a live internet path to the acquirer, and
     * `NoInternetGuard` blocks all non-local hosts in those modes by design. Showing gateway tiles
     * that can never succeed is worse than hiding them — a greyed tile that the owner has no way to
     * un-grey tells them their configuration is broken when it is working exactly as designed. Cash
     * and the static merchant QR remain available in all three modes. (A1, PG-REQ-3, task 6.4)
     */
    val gatewayPaymentsEnabled: Boolean,
)

/**
 * The single source of truth for what each mode permits.
 *
 * - `CLOUD` — everything on. Behaviour-preserving for existing installs (Requirement 1.2).
 * - `LAN`   — **only** [ModeCapabilities.tables] and [ModeCapabilities.staffDevices]. It has peers
 *             and tables, but no website, no cloud storage, no cloud realtime, no secondary admin.
 * - `KIOSK` — nothing. One device, no peers, no tables, no internet.
 */
fun OperatingMode.toCapabilities(): ModeCapabilities = when (this) {
    OperatingMode.CLOUD -> ModeCapabilities(
        customerQrOrdering = true,
        printableQrSheets = true,
        tables = true,
        staffDevices = true,
        secondaryAdmin = true,
        websiteInvites = true,
        cloudImageHosting = true,
        realtimeWebSocket = true,
        // Gateway payments require a live internet path to the acquirer — available in Cloud only.
        // (A1, PG-REQ-3, task 6.4)
        gatewayPaymentsEnabled = true,
    )

    OperatingMode.LAN -> ModeCapabilities(
        customerQrOrdering = false,
        printableQrSheets = false,
        tables = true,
        staffDevices = true,
        secondaryAdmin = false,
        websiteInvites = false,
        cloudImageHosting = false,
        realtimeWebSocket = false,
        // NoInternetGuard blocks the aggregator host in LAN Mode. (A1, PG-REQ-3)
        gatewayPaymentsEnabled = false,
    )

    OperatingMode.KIOSK -> ModeCapabilities(
        customerQrOrdering = false,
        printableQrSheets = false,
        tables = false,
        staffDevices = false,
        secondaryAdmin = false,
        websiteInvites = false,
        cloudImageHosting = false,
        realtimeWebSocket = false,
        // NoInternetGuard blocks the aggregator host in Kiosk Mode. (A1, PG-REQ-3)
        gatewayPaymentsEnabled = false,
    )
}
