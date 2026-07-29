package com.warungtomyam.pos.ui.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.warungtomyam.pos.ui.demo.DemoCustomerPreviewScreen
import com.warungtomyam.pos.ui.demo.DemoHomeScreen
import com.warungtomyam.pos.ui.demo.DemoMenuManagementScreen
import com.warungtomyam.pos.ui.demo.DemoReportsScreen
import com.warungtomyam.pos.ui.demo.DemoViewModel
import com.warungtomyam.pos.ui.demo.DemoWalkthroughScreen

fun NavGraphBuilder.demoNavGraph(navController: NavHostController) {
    composable(NavRoutes.DEMO_WALKTHROUGH) {
        val demoViewModel: DemoViewModel = hiltViewModel()
        val walkthroughStep by demoViewModel.walkthroughStep.collectAsState()

        val onExitDemo = {
            demoViewModel.exitDemo()
            navController.navigate(NavRoutes.ROLE_SELECT) {
                popUpTo(NavRoutes.DEMO_WALKTHROUGH) { inclusive = true }
            }
        }

        if (walkthroughStep != null) {
            DemoWalkthroughScreen(
                currentStep = walkthroughStep!!,
                onNextStep = { demoViewModel.advanceWalkthrough() },
                onSkip = { demoViewModel.skipWalkthrough() }
            ) {
                DemoHomeScreen(
                    demoViewModel = demoViewModel,
                    onExitDemo = { onExitDemo() },
                    onNavigateToMenu = {
                        navController.navigate(NavRoutes.DEMO_MENU_MANAGEMENT)
                    },
                    onNavigateToReports = {
                        navController.navigate(NavRoutes.DEMO_REPORTS)
                    },
                    onNavigateToCustomerPreview = {
                        navController.navigate(NavRoutes.DEMO_CUSTOMER_PREVIEW)
                    }
                )
            }
        } else {
            // Walkthrough dismissed, navigate to DEMO_HOME
            LaunchedEffect(Unit) {
                navController.navigate(NavRoutes.DEMO_HOME) {
                    popUpTo(NavRoutes.DEMO_WALKTHROUGH) { inclusive = true }
                }
            }
        }
    }

    composable(NavRoutes.DEMO_HOME) {
        val demoViewModel: DemoViewModel = hiltViewModel()

        DemoHomeScreen(
            demoViewModel = demoViewModel,
            onExitDemo = {
                demoViewModel.exitDemo()
                navController.navigate(NavRoutes.ROLE_SELECT) {
                    popUpTo(NavRoutes.DEMO_HOME) { inclusive = true }
                }
            },
            onNavigateToMenu = {
                navController.navigate(NavRoutes.DEMO_MENU_MANAGEMENT)
            },
            onNavigateToReports = {
                navController.navigate(NavRoutes.DEMO_REPORTS)
            },
            onNavigateToCustomerPreview = {
                navController.navigate(NavRoutes.DEMO_CUSTOMER_PREVIEW)
            }
        )
    }

    composable(NavRoutes.DEMO_MENU_MANAGEMENT) {
        val demoViewModel: DemoViewModel = hiltViewModel()

        DemoMenuManagementScreen(
            demoViewModel = demoViewModel,
            onBack = { navController.popBackStack() }
        )
    }

    composable(NavRoutes.DEMO_REPORTS) {
        val demoViewModel: DemoViewModel = hiltViewModel()

        DemoReportsScreen(
            viewModel = demoViewModel,
            onBack = { navController.popBackStack() }
        )
    }

    composable(NavRoutes.DEMO_CUSTOMER_PREVIEW) {
        val demoViewModel: DemoViewModel = hiltViewModel()

        DemoCustomerPreviewScreen(
            demoViewModel = demoViewModel,
            onBack = { navController.popBackStack() }
        )
    }
}
