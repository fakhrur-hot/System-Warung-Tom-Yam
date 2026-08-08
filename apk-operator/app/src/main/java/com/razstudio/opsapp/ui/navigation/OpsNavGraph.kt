package com.razstudio.opsapp.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.razstudio.opsapp.ui.home.CafesHomeScreen
import com.razstudio.opsapp.ui.screens.AffiliateDashboardScreen
import com.razstudio.opsapp.ui.screens.AffiliateDebugScreen
import com.razstudio.opsapp.ui.screens.CafeProfileShellScreen
import com.razstudio.opsapp.ui.screens.ConnectExistingCafeScreen
import com.razstudio.opsapp.ui.screens.PromoCatalogScreen
import com.razstudio.opsapp.ui.screens.ProvisionWizardScreen

/**
 * Top-level navigation destinations for the Operator APK.
 */
object OpsDestinations {
    const val HOME = "home"
    const val PROVISION_WIZARD = "provision_wizard"
    const val CONNECT_EXISTING_CAFE = "connect_existing_cafe"
    const val CAFE_PROFILE_SHELL = "cafe_profile_shell/{cafeId}"
    const val AFFILIATE_ADS = "affiliate_ads"
    const val AFFILIATE_DEBUG_TOOLS = "affiliate_debug_tools"
    const val PROMO_CATALOG = "promo_catalog"

    fun cafeProfileShell(cafeId: String) = "cafe_profile_shell/$cafeId"
}

@Composable
fun OpsNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = OpsDestinations.HOME,
        modifier = modifier,
    ) {
        composable(OpsDestinations.HOME) {
            CafesHomeScreen(
                onProvisionNewCafe = { navController.navigate(OpsDestinations.PROVISION_WIZARD) },
                onConnectExistingCafe = { navController.navigate(OpsDestinations.CONNECT_EXISTING_CAFE) },
                onOpenCafe = { cafeId ->
                    navController.navigate(OpsDestinations.cafeProfileShell(cafeId))
                },
                onOpenAffiliateAds = { navController.navigate(OpsDestinations.AFFILIATE_ADS) },
            )
        }

        composable(OpsDestinations.PROVISION_WIZARD) {
            ProvisionWizardScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(OpsDestinations.CONNECT_EXISTING_CAFE) {
            ConnectExistingCafeScreen(
                onConnected = {
                    navController.navigate(OpsDestinations.HOME) {
                        popUpTo(OpsDestinations.HOME) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = OpsDestinations.CAFE_PROFILE_SHELL,
            arguments = listOf(
                navArgument("cafeId") { type = NavType.StringType }
            ),
        ) { backStackEntry ->
            val cafeId = backStackEntry.arguments?.getString("cafeId") ?: ""
            CafeProfileShellScreen(
                cafeId = cafeId,
                onBack = { navController.popBackStack() },
            )
        }

        composable(OpsDestinations.AFFILIATE_ADS) {
            AffiliateDashboardScreen(
                onBack = { navController.popBackStack() },
                onOpenDebugTools = { navController.navigate(OpsDestinations.AFFILIATE_DEBUG_TOOLS) },
                onOpenCatalogEditor = { navController.navigate(OpsDestinations.PROMO_CATALOG) },
            )
        }

        composable(OpsDestinations.AFFILIATE_DEBUG_TOOLS) {
            AffiliateDebugScreen(
                onBack = { navController.popBackStack() },
                onOpenCatalogEditor = { navController.navigate(OpsDestinations.PROMO_CATALOG) },
            )
        }

        composable(OpsDestinations.PROMO_CATALOG) {
            PromoCatalogScreen(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label)
    }
}
