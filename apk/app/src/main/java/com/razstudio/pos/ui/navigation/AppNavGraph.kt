package com.razstudio.pos.ui.navigation

import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.razstudio.pos.data.AuthEventBus
import com.razstudio.pos.data.local.AmbientSettingsStore
import com.razstudio.pos.realtime.RealtimeService
import com.razstudio.pos.ui.ambient.AmbientOverlay
import com.razstudio.pos.ui.components.AmbientSuppressor
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
import com.razstudio.pos.ui.screens.BillHistoryScreen
import com.razstudio.pos.ui.screens.HardwareDevicesScreen
import com.razstudio.pos.ui.screens.AdminLockScreen
import com.razstudio.pos.ui.screens.AdminSettingsScreen
import com.razstudio.pos.ui.screens.BackupScreen
import com.razstudio.pos.ui.screens.CafeManagementScreen
import com.razstudio.pos.ui.screens.CafeProfileScreen
import com.razstudio.pos.ui.screens.DevicesScreen
import com.razstudio.pos.ui.screens.KeepAliveSetupScreen
import com.razstudio.pos.ui.screens.ManualDineInScreen
import com.razstudio.pos.ui.screens.MenuManagementScreen
import com.razstudio.pos.ui.screens.OrderingConnectScreen
import com.razstudio.pos.ui.screens.OrderingHomeScreen
import com.razstudio.pos.ui.screens.PaymentGatewaySettingsScreen
import com.razstudio.pos.ui.screens.PendingApprovalScreen
import com.razstudio.pos.ui.screens.ProvisionerScreen
import com.razstudio.pos.ui.screens.PrintersScreen
import com.razstudio.pos.ui.screens.QrPdfScreen
import com.razstudio.pos.ui.screens.ReportsScreen
import com.razstudio.pos.ui.screens.RoleSelectScreen
import com.razstudio.pos.ui.screens.LanPairingScreen
import com.razstudio.pos.ui.screens.SetupScreen
import com.razstudio.pos.ui.screens.SignInScreen
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
    // DemoModeOverlay is a global banner drawn ON TOP of the entire NavHost (same outer Box,
    // composed after it) — without this, it silently covers AND intercepts touches for any
    // screen's own bottom bar (e.g. AdminSettingsScreen's Save/Cancel row), since both sit at the
    // same screen Y position. Measuring the banner's real height and reserving that much bottom
    // padding on the NavHost's container means every screen's own bottom UI renders just above it
    // instead — no per-screen changes needed, and no hardcoded height to keep in sync by hand.
    var demoOverlayHeightPx by remember { mutableIntStateOf(0) }
    val demoOverlayHeightDp = with(LocalDensity.current) {
        if (demoActive) demoOverlayHeightPx.toDp() else 0.dp
    }
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val demoRole = if (currentRoute == NavRoutes.ORDERING_HOME) DemoRole.STAFF else DemoRole.ADMIN

    // ── Ambient (screensaver) mode ────────────────────────────────────────────────────────────
    // Ambient mode only takes over the two "station idles here" screens; deeper management screens
    // are always deliberate navigation, so covering them would be surprising.
    val context = LocalContext.current
    val ambientStore = remember { AmbientSettingsStore(context) }
    var ambientEnabled by remember { mutableStateOf(ambientStore.isEnabled()) }
    var ambientActive by remember { mutableStateOf(false) }
    var lastInteractionAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val routeState = rememberUpdatedState(currentRoute)

    /**
     * Hold the display awake for as long as the feature is on — this is what makes the station
     * behave "like a movie playing". It must NOT be scoped to the ambient overlay alone: with a
     * 30-second system screen timeout the panel would sleep long before an idle delay of minutes
     * ever elapsed, and ambient mode would never appear. The flag is cleared on dispose, so the
     * device returns to normal power behaviour the moment the feature is switched off.
     */
    val activityWindow = (context as? android.app.Activity)?.window
    DisposableEffect(ambientEnabled, activityWindow) {
        if (ambientEnabled) {
            activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Idle watcher. Re-reads the store each tick so a settings change applies without a restart.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            ambientEnabled = ambientStore.isEnabled()
            val route = routeState.value
            val idleHere = route == NavRoutes.ADMIN_HOME || route == NavRoutes.ORDERING_HOME
            // AmbientSuppressor is the task-17.2 wiring (Requirement 13.7). Without this check the
            // idle watcher would replace the Payment QR with the ambient table board while a customer
            // is mid-scan — the QR dialog holds FLAG_KEEP_SCREEN_ON, which stops the display sleeping
            // but does nothing to stop THIS timer from firing. Reference-counted rather than a
            // boolean so overlapping holders cannot cancel each other out.
            if (ambientEnabled && idleHere && !ambientActive &&
                !AmbientSuppressor.isSuppressed &&
                System.currentTimeMillis() - lastInteractionAt >= ambientStore.getTimeoutMillis()
            ) {
                ambientActive = true
            }
        }
    }

    // Let the foreground service tighten its catch-up poll only while ambient mode is showing.
    DisposableEffect(ambientActive) {
        RealtimeService.ambientModeActive = ambientActive
        onDispose { RealtimeService.ambientModeActive = false }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = demoOverlayHeightDp)
            // Observe every touch on the Initial pass so the idle timer resets without consuming
            // the event — child buttons and scrolling keep working untouched. Throttled to one
            // write per second so a scroll gesture doesn't thrash state.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        val now = System.currentTimeMillis()
                        if (now - lastInteractionAt > 1_000) lastInteractionAt = now
                    }
                }
            }
    ) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Task 23 — the first screen. Every exit here pops itself off the stack: an owner who has
        // reached their café must not be able to swipe back into the account screen, and one who
        // skipped has already answered the question.
        composable(NavRoutes.SIGN_IN) {
            SignInScreen(
                onContinueToEntry = {
                    navController.navigate(NavRoutes.ROLE_SELECT) {
                        popUpTo(NavRoutes.SIGN_IN) { inclusive = true }
                    }
                },
                onSetup = {
                    navController.navigate(NavRoutes.SETUP) {
                        popUpTo(NavRoutes.SIGN_IN) { inclusive = true }
                    }
                },
                onTryDemo = {
                    demoModeViewModel.enter {
                        navController.navigate(NavRoutes.ADMIN_HOME) {
                            popUpTo(NavRoutes.SIGN_IN) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(NavRoutes.ROLE_SELECT) {
            RoleSelectScreen(
                onAdminConnect = {
                    navController.navigate(NavRoutes.ADMIN_CONNECT)
                },
                onOrderingConnect = {
                    navController.navigate(NavRoutes.ORDERING_CONNECT)
                },
                // Wireless AP Mode. LanPairingScreen already existed but nothing routed to it —
                // a LAN café had no way to show the QR its staff phones need to scan.
                onWirelessAp = {
                    navController.navigate(NavRoutes.LAN_PAIRING)
                },
                // Hosting opens the till, not the pairing QR. The whole back stack is cleared: an
                // owner who is now running their café should not be able to swipe back into the
                // mode picker and half-leave it.
                onHostCafe = {
                    navController.navigate(NavRoutes.ADMIN_HOME) {
                        popUpTo(NavRoutes.ROLE_SELECT) { inclusive = true }
                    }
                },
                // Kiosk: the same order-entry screen, minus the table step. Reusing it keeps the
                // menu grid, search and cart identical across modes instead of drifting apart.
                onKiosk = {
                    navController.navigate(NavRoutes.MANUAL_DINE_IN)
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
                onSetup = { navController.navigate(NavRoutes.SETUP) },
                onProvision = { navController.navigate(NavRoutes.PROVISIONER) },
                // "Reload from Google Drive" is the sign-in screen again: it re-authenticates and
                // re-lists the account's cafés, which is exactly what that screen already does.
                // A second code path would be a second place for the chooser to drift.
                onReloadDrive = { navController.navigate(NavRoutes.SIGN_IN) },
                onAccountSignedOut = {
                    navController.navigate(NavRoutes.ROLE_SELECT) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(NavRoutes.LAN_PAIRING) {
            LanPairingScreen(onBack = { navController.popBackStack() })
        }

        // Debug builds only: the catalog editor carries a GitHub token and writes to `main`, which
        // belongs with the owner and not on a café's phone. Absent from release entirely rather than
        // hidden behind a flag — a route that cannot be navigated to cannot be reached by a deep link.
        if (com.razstudio.pos.BuildConfig.DEBUG) {
            composable(NavRoutes.PROMO_CATALOG) {
                com.razstudio.pos.ui.screens.PromoCatalogScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(NavRoutes.SETUP) {
            SetupScreen(
                onBack = { navController.popBackStack() },
                onOpenPromoCatalog = { navController.navigate(NavRoutes.PROMO_CATALOG) },
                // Setup owns the owner-key flow now; it only needs telling where to land.
                onOwnerKeyAccepted = {
                    navController.navigate(NavRoutes.ADMIN_HOME) {
                        popUpTo(NavRoutes.ROLE_SELECT) { inclusive = true }
                    }
                },
                // Land back on the home screen so the mode just unlocked is visible immediately.
                onSaved = {
                    navController.navigate(NavRoutes.ROLE_SELECT) {
                        popUpTo(NavRoutes.ROLE_SELECT) { inclusive = true }
                    }
                },
            )
        }

        composable(NavRoutes.PROVISIONER) {
            ProvisionerScreen(
                onBack = { navController.popBackStack() },
                onDone = {
                    navController.navigate(NavRoutes.ADMIN_HOME) {
                        popUpTo(NavRoutes.ROLE_SELECT) { inclusive = true }
                    }
                },
            )
        }

        composable(NavRoutes.ADMIN_CONNECT) {
            AdminConnectScreen(
                onConnected = {
                    // popUpTo(0), not popUpTo(ROLE_SELECT): role-select is only on the stack when
                    // the device came through it. Signing in from the lock screen — or from a
                    // start destination of ADMIN_HOME — leaves it absent, so the old form popped
                    // nothing and back walked into whatever preceded the login.
                    navController.navigate(NavRoutes.ADMIN_HOME) {
                        popUpTo(0) { inclusive = true }
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
                onAccountSignedOut = {
                    navController.navigate(NavRoutes.ROLE_SELECT) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateToLock = {
                    // Sign-out: clear the ENTIRE back stack, so the lock screen is a dead end.
                    //
                    // This used to be popUpTo(ROLE_SELECT), which only pops if role-select is
                    // actually on the stack — and it usually is not. StartupViewModel sends a
                    // signed-in admin device straight to ADMIN_HOME as the START destination, so
                    // role-select was never visited, popUpTo matched nothing, and the lock screen
                    // was simply pushed on top of the still-live home screen. Pressing back walked
                    // straight back into the café that had just been signed out of.
                    //
                    // popUpTo(0) has no such precondition: it clears everything regardless of how
                    // the device got here, which is what the original comment intended. Back from
                    // the lock screen now leaves the app, and relaunching returns to the lock
                    // screen because StartupViewModel checks sessionPrefs.isLocked().
                    navController.navigate(NavRoutes.ADMIN_LOCK) {
                        popUpTo(0) { inclusive = true }
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
                },
                onSignInWithOwnerKey = {
                    // Same owner-key screen a fresh device uses. The lock screen is popped so a
                    // cancelled scan cannot leave the till sitting behind a login it never
                    // completed — backing out returns to the lock, which is where it belongs.
                    navController.navigate(NavRoutes.ADMIN_CONNECT) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(NavRoutes.ORDERING_HOME) {
            OrderingHomeScreen(
                onAccountSignedOut = {
                    navController.navigate(NavRoutes.ROLE_SELECT) {
                        popUpTo(0) { inclusive = true }
                    }
                },)
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
                onBack = { navController.popBackStack() },
                onPairStaffDevice = { navController.navigate(NavRoutes.LAN_PAIRING) },
            )
        }

        // Admin Settings
        composable(NavRoutes.ADMIN_SETTINGS) {
            AdminSettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToPrinters = { navController.navigate(NavRoutes.PRINTERS) },
                onNavigateToHardwareDevices = {
                    navController.navigate(NavRoutes.HARDWARE_DEVICES)
                },
                onNavigateToKeepAliveSetup = {
                    navController.navigate(NavRoutes.KEEP_ALIVE_SETUP)
                },
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
                onBack = { navController.popBackStack() },
                onNavigateToBillHistory = { navController.navigate(NavRoutes.BILL_HISTORY) }
            )
        }

        // Bill History — search past bills, view one, reprint its receipt.
        composable(NavRoutes.BILL_HISTORY) {
            BillHistoryScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // Devices & Hardware — printer transport, cash drawer and customer display (HW-REQ-6).
        composable(NavRoutes.HARDWARE_DEVICES) {
            HardwareDevicesScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.PAYMENT_GATEWAY_SETTINGS) {
            PaymentGatewaySettingsScreen(
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

        // Café Profile — the café's own identity (name, logo, location, preset, payment QR)
        composable(NavRoutes.CAFE_PROFILE) {
            CafeProfileScreen(onBack = { navController.popBackStack() })
        }

        // Café Management hub
        composable(NavRoutes.CAFE_MANAGEMENT) {
            CafeManagementScreen(
                onBack = { navController.popBackStack() },
                onNavigateToCafeProfile = {
                    navController.navigate(NavRoutes.CAFE_PROFILE)
                },
                onNavigateToMenu = {
                    navController.navigate(NavRoutes.MENU_MANAGEMENT)
                },
                onNavigateToTables = {
                    navController.navigate(NavRoutes.TABLE_MANAGEMENT)
                },
                onNavigateToQrPdf = {
                    navController.navigate(NavRoutes.QR_PDF)
                },
                onNavigateToPaymentGateway = {
                    navController.navigate(NavRoutes.PAYMENT_GATEWAY_SETTINGS)
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
    } // end touch-observing Box wrapping the NavHost

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
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { demoOverlayHeightPx = it.height },
        )

        // Ambient (screensaver) mode sits above everything, including the demo banner. Any touch
        // dismisses it and restarts the idle countdown.
        if (ambientActive) {
            AmbientOverlay(
                onDismiss = {
                    ambientActive = false
                    lastInteractionAt = System.currentTimeMillis()
                }
            )
        }
    }
}
