package com.razstudio.pos.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.razstudio.pos.data.AuthEventBus
import com.razstudio.pos.ui.components.DemoModeOverlay
import com.razstudio.pos.ui.components.DemoRole
import com.razstudio.pos.ui.viewmodels.AuthViewModel
import com.razstudio.pos.ui.viewmodels.DemoModeViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.razstudio.pos.ui.screens.AddMenuItemScreen
import com.razstudio.pos.ui.screens.AdminConnectScreen
import com.razstudio.pos.ui.screens.AdminHomeScreen
import com.razstudio.pos.ui.screens.AdminLockScreen
import com.razstudio.pos.ui.screens.AdminSettingsScreen
import com.razstudio.pos.ui.screens.BackupScreen
import com.razstudio.pos.ui.screens.CafeManagementScreen
import com.razstudio.pos.ui.screens.DevicesScreen
import com.razstudio.pos.ui.screens.KeepAliveSetupScreen
import com.razstudio.pos.ui.screens.ManualDineInScreen
import com.razstudio.pos.ui.screens.MenuManagementScreen
import com.razstudio.pos.ui.screens.OrderingConnectScreen
import com.razstudio.pos.ui.screens.OrderingHomeScreen
import com.razstudio.pos.ui.screens.PendingApprovalScreen
import com.razstudio.pos.ui.screens.PrintersScreen
import com.razstudio.pos.ui.screens.QrPdfScreen
import com.razstudio.pos.ui.screens.ReportsScreen
import com.razstudio.pos.ui.screens.RoleSelectScreen
import com.razstudio.pos.ui.screens.SetupScreen
import com.razstudio.pos.ui.screens.TableManagementScreen

/**
 * Main navigation graph. Start destination is determined by current auth state.
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String,
    authViewModel: AuthViewModel = hiltViewModel(),
    demoModeViewModel: DemoModeViewModel = hiltViewModel()
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

    val demoActive by demoModeViewModel.active.collectAsState()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val demoRole = if (currentRoute == NavRoutes.ORDERING_HOME) DemoRole.STAFF else DemoRole.ADMIN

    Box(modifier = Modifier.fillMaxSize()) {
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
                    // Rebuilt demo: seed one shared local dataset, then drop the user into the REAL
                    // admin home. The global DemoModeOverlay handles exit + teardown from any screen.
                    demoModeViewModel.enter {
                        navController.navigate(NavRoutes.ADMIN_HOME) {
                            popUpTo(NavRoutes.ROLE_SELECT) { inclusive = true }
                        }
                    }
                },
                onSetup = { navController.navigate(NavRoutes.SETUP) }
            )
        }

        composable(NavRoutes.SETUP) {
            SetupScreen(onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.ADMIN_CONNECT) {
            AdminConnectScreen(
                onConnected = {
                    navController.navigate(NavRoutes.ADMIN_HOME) {
                        popUpTo(NavRoutes.ROLE_SELECT) { inclusive = true }
                    }
                },
                onSecondaryRegistered = {
                    // Secondary Admin registered via invite → wait for Main-Admin approval.
                    navController.navigate(NavRoutes.PENDING_APPROVAL) {
                        popUpTo(NavRoutes.ADMIN_CONNECT) { inclusive = true }
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
            AdminHomeScreen(
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
                    navController.navigate(NavRoutes.TABLE_MANAGEMENT)
                },
                onNavigateToQrPdf = {
                    navController.navigate(NavRoutes.QR_PDF)
                },
                onNavigateToPrinters = {
                    navController.navigate(NavRoutes.PRINTERS)
                }
            )
        }

        // Tables Management — dedicated full-screen page (was a modal over the table view).
        composable(NavRoutes.TABLE_MANAGEMENT) {
            TableManagementScreen(
                onBack = { navController.popBackStack() }
            )
        }

    }

        // Global Demo Mode banner + role switcher + exit-confirm, overlaying every real screen.
        // Admin and Staff share the same seeded dataset, so an order placed on one surface shows
        // up live on the other.
        DemoModeOverlay(
            active = demoActive,
            onRole = demoRole,
            onGoAdmin = {
                navController.navigate(NavRoutes.ADMIN_HOME) {
                    popUpTo(NavRoutes.ADMIN_HOME) { inclusive = true }
                    launchSingleTop = true
                }
            },
            onGoStaff = {
                navController.navigate(NavRoutes.ORDERING_HOME) {
                    popUpTo(NavRoutes.ORDERING_HOME) { inclusive = true }
                    launchSingleTop = true
                }
            },
            onConfirmExit = {
                demoModeViewModel.exit {
                    navController.navigate(NavRoutes.ROLE_SELECT) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
