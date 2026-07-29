package com.warungtomyam.pos.ui.tableview

/**
 * RBAC flags controlling which order actions a staff member may perform.
 * Admin always gets [ADMIN] (all true); staff permissions are loaded from [SystemSettings].
 */
data class StaffPermissions(
    val canSendToKitchen: Boolean,
    val canTakePayment: Boolean,
    val canCancel: Boolean,
) {
    companion object {
        /** Full permissions for the admin role. */
        val ADMIN = StaffPermissions(
            canSendToKitchen = true,
            canTakePayment = true,
            canCancel = true
        )
    }
}
