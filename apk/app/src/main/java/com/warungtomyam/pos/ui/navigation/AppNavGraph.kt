package com.warungtomyam.pos.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.warungtomyam.pos.data.AuthEventBus
import com.warungtomyam.pos.ui.viewmodels.AuthViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.warungtomyam.pos.ui.screens.AddMenuItemScreen
import com.warungtomyam.pos.ui.screens.AdminConnectScreen
import com.warungtomyam.pos.ui.screens.AdminHomeScreen
import com.warungtomyam.pos.ui.screens.AdminLockScreen
import com.warungtomyam.pos.ui.screens.AdminSettingsScreen
import com.warungtomyam.pos.ui.screens.BackupScreen
import com.warungtomyam.pos.ui.screens.CafeManagementScreen
import com.warungtomyam.pos.ui.screens.DevicesScreen
import com.warungtomyam.pos.ui.screens.KeepAliveSetupScreen
import com.warungtomyam.pos.ui.screens.ManualDineInScreen
import com.warungtomyam.pos.ui.screens.MenuManagementScreen
import com.warungtomyam.pos.ui.screens.OrderingConnectScreen
import com.warungtomyam.pos.ui.screens.OrderingHomeScreen
import com.warungtomyam.pos.ui.screens.PendingApprovalScreen
import com.warungtomyam.pos.ui.screens.PrintersScreen
import com.warungtomyam.pos.ui.screens.QrPdfScreen
import com.warungtomyam.pos.ui.screens.ReportsScreen
import com.warungtomyam.pos.ui.screens.RoleSelectScreen

/**
 * Main navigation graph. Start destination is determined by current auth state.
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String,
    authViewModel: AuthViewModel = hiltViewModel()
) {
    // Global session-expiry observer: any 401 from an admin-authenticated call
    // clears the back stack and sends the admin to re-handshake.
    LaunchedEffect(Unit) {
        authViewModel.authEventBus.events.collect { event ->
            if (event is AuthEventBus.AuthEvent.SessionExpired) {
                navController.navigate(NavRoutes.ADMIN_CONNECT) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(NavRoutes.ROLE_SELECT) {
            RoleSelectScreen(
                onAdminConnect = {
                    navController.navigate(NavRoutes.ADMIN_CONNECT)
                },
                onOrderingConnect = {
                    navController.navigate(NavRoutes.ORDERING_CONNECT)
                },
                onTryDemo = {
                    navController.navigate(NavRoutes.DEMO_WALKTHROUGH)
                }
            )
        }

        composable(NavRoutes.ADMIN_CONNECT) {
            AdminConnectScreen(
                onConnected = {
                    navController.navigate(NavRoutes.ADMIN_HOME) {
                        popUpTo(NavRoutes.ROLE_SELECT) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.ORDERING_CONNECT) {
            OrderingConnectScreen(
                onRegistered = {
                    navController.navigate(NavRoutes.PENDING_APPROVAL) {
                        popUpTo(NavRoutes.ORDERING_CONNECT) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.PENDING_APPROVAL) {
            PendingApprovalScreen(
                onApproved = { isSecondaryAdmin ->
                    // A secondary admin lands in the admin home; ordering staff in theirs.
                    val dest = if (isSecondaryAdmin) NavRoutes.ADMIN_HOME else NavRoutes.ORDERING_HOME
                    navController.navigate(dest) {
                        popUpTo(NavRoutes.ROLE_SELECT) { inclusive = true }
                    }
                },
                onRevoked = {
                    navController.navigate(NavRoutes.ROLE_SELECT) {
                        popUpTo(NavRoutes.ROLE_SELECT) { inclusive = true }
                    }
                }
            )
        }

        composable(NavRoutes.ADMIN_HOME) {
            // Listen for "openTableManagement" signal from CafeManagementScreen
            val openTableManagement = it.savedStateHandle
                .getStateFlow("openTableManagement", false)
                .collectAsState()

            AdminHomeScreen(
                openTableManagementOnStart = openTableManagement.value,
                onTableManagementOpened = {
                    it.savedStateHandle["openTableManagement"] = false
                },
                onNavigateToLock = {
                    navController.navigate(NavRoutes.ADMIN_LOCK) {
                        popUpTo(NavRoutes.ADMIN_HOME) { inclusive = true }
                    }
                },
                onNavigateToReconnect = {
                    // Expired/revoked token surfaced without an HTTP 401 (e.g. openSession
                    // finding no token). Mirror the global session-expiry handler: wipe the
                    // back stack and send the admin to re-handshake.
                    navController.navigate(NavRoutes.ADMIN_CONNECT) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateToDineIn = {
                    navController.navigate(NavRoutes.MANUAL_DINE_IN)
                },
                onNavigateToDevices = {
                    navController.navigate(NavRoutes.DEVICES)
                },
                onNavigateToSettings = {
                    navController.navigate(NavRoutes.ADMIN_SETTINGS)
                },
                onNavigateToPrinters = {
                    navController.navigate(NavRoutes.PRINTERS)
                },
                onNavigateToQrPdf = {
                    navController.navigate(NavRoutes.QR_PDF)
                },
                onNavigateToReports = {
                    navController.navigate(NavRoutes.REPORTS)
                },
                onNavigateToBackup = {
                    navController.navigate(NavRoutes.BACKUP)
                },
                onNavigateToKeepAliveSetup = {
                    navController.navigate(NavRoutes.KEEP_ALIVE_SETUP)
                },
                onNavigateToCafeManagement = {
                    navController.navigate(NavRoutes.CAFE_MANAGEMENT)
                }
            )
        }

        composable(NavRoutes.ADMIN_LOCK) {
            AdminLockScreen(
                onReopen = {
                    navController.navigate(NavRoutes.ADMIN_HOME) {
                        popUpTo(NavRoutes.ADMIN_LOCK) { inclusive = true }
                    }
                }
            )
        }

        composable(NavRoutes.ORDERING_HOME) {
            OrderingHomeScreen()
        }

        // Menu Management
        composable(NavRoutes.MENU_MANAGEMENT) {
            MenuManagementScreen(
                onBack = { navController.popBackStack() },
                onAddItem = { category ->
                    navController.navigate(NavRoutes.addMenuItemRoute(category))
                },
                onEditItem = { itemId ->
                    navController.navigate(NavRoutes.editMenuItemRoute(itemId))
                }
            )
        }

        // Add Menu Item (type-first: category pre-selected)
        composable(
            route = NavRoutes.ADD_MENU_ITEM,
            arguments = listOf(
                navArgument("category") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val category = android.net.Uri.decode(
                backStackEntry.arguments?.getString("category") ?: "FOOD"
            )
            AddMenuItemScreen(
                mode = MenuItemMode.ADD,
                category = category,
                itemId = null,
                onBack = { navController.popBackStack() }
            )
        }

        // Edit Menu Item — category is loaded from the existing item, not passed in
        composable(
            route = NavRoutes.EDIT_MENU_ITEM,
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getString("itemId") ?: ""
            AddMenuItemScreen(
                mode = MenuItemMode.EDIT,
                category = null,
                itemId = itemId,
                onBack = { navController.popBackStack() }
            )
        }

        // Manual Dine-In Order Entry
        composable(NavRoutes.MANUAL_DINE_IN) {
            ManualDineInScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // Devices Management
        composable(NavRoutes.DEVICES) {
            DevicesScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // Admin Settings
        composable(NavRoutes.ADMIN_SETTINGS) {
            AdminSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // Printers Management
        composable(NavRoutes.PRINTERS) {
            PrintersScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // QR PDF Generator
        composable(NavRoutes.QR_PDF) {
            QrPdfScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // Reports
        composable(NavRoutes.REPORTS) {
            ReportsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // Backup & Restore
        composable(NavRoutes.BACKUP) {
            BackupScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // Keep-Alive / Background Setup
        composable(NavRoutes.KEEP_ALIVE_SETUP) {
            KeepAliveSetupScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // Café Management hub
        composable(NavRoutes.CAFE_MANAGEMENT) {
            CafeManagementScreen(
                onBack = { navController.popBackStack() },
                onNavigateToMenu = {
                    navController.navigate(NavRoutes.MENU_MANAGEMENT)
                },
                onNavigateToTables = {
                    // Signal AdminHomeScreen to open the table management dialog on return
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("openTableManagement", true)
                    navController.popBackStack()
                }
            )
        }

        // Demo Mode Navigation Graph
        demoNavGraph(navController)
    }
}
