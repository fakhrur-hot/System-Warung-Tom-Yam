package com.razstudio.pos.ui.tableview

/**
 * RBAC flags controlling which order actions a staff member may perform.
 * Admin always gets [ADMIN] (all true); staff permissions are loaded from [SystemSettings].
 */
data class StaffPermissions(
    val canSendToKitchen: Boolean,
    val canTakePayment: Boolean,
    val canCancel: Boolean,
    /**
     * When true, "Pay Cash" is hidden from the payment row and only "Pay QR" is offered — the
     * café-wide `staffQrOnly` setting. Always false for [ADMIN]: this restricts ordering-staff
     * sessions only, never the admin device's own payment buttons.
     */
    val qrOnly: Boolean = false,
) {
    companion object {
        /** Full permissions for the admin role. */
        val ADMIN = StaffPermissions(
            canSendToKitchen = true,
            canTakePayment = true,
            canCancel = true,
            qrOnly = false,
        )
    }
}
