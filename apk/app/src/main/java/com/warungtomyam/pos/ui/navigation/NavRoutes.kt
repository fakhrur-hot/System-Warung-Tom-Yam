package com.warungtomyam.pos.ui.navigation

/** Navigation route constants for the app. */
object NavRoutes {
    const val ROLE_SELECT = "role_select"
    const val ADMIN_CONNECT = "admin_connect"
    const val ORDERING_CONNECT = "ordering_connect"
    const val PENDING_APPROVAL = "pending_approval"
    const val ADMIN_HOME = "admin_home"
    const val ADMIN_LOCK = "admin_lock"
    const val ORDERING_HOME = "ordering_home"
    const val MENU_MANAGEMENT = "menu_management"
    const val ADD_MENU_ITEM = "add_menu_item/{category}"
    const val EDIT_MENU_ITEM = "edit_menu_item/{itemId}"
    const val MANUAL_DINE_IN = "manual_dine_in"
    const val DEVICES = "devices"
    const val PRINTERS = "printers"
    const val ADMIN_SETTINGS = "admin_settings"
    const val QR_PDF = "qr_pdf"
    const val REPORTS = "reports"
    const val BACKUP = "backup"
    const val KEEP_ALIVE_SETUP = "keep_alive_setup"
    const val CAFE_MANAGEMENT = "cafe_management"
    const val TABLE_MANAGEMENT = "table_management"

    // Category names can contain "/", spaces or "()" (e.g. "UDANG/SOTONG", "MINUMAN (AIS)"),
    // so URL-encode the path segment; AppNavGraph decodes it when reading the argument.
    fun addMenuItemRoute(category: String): String =
        "add_menu_item/${android.net.Uri.encode(category)}"
    fun editMenuItemRoute(itemId: String): String = "edit_menu_item/$itemId"
}

/** Explicit mode for the add/edit menu-item screen — replaces the old empty-string sentinel. */
enum class MenuItemMode { ADD, EDIT }
