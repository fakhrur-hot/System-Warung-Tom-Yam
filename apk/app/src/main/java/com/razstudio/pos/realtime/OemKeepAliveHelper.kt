package com.razstudio.pos.realtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Spike: OEM-specific battery optimization whitelisting helper.
 *
 * Android OEMs (Xiaomi, Samsung, OPPO, Vivo, Huawei) aggressively kill background
 * apps beyond stock Android doze behavior. This helper detects the OEM and provides
 * intents to guide users through the whitelisting process.
 *
 * This feeds Task 28 (onboarding flow) with the detection logic and intent map.
 */
object OemKeepAliveHelper {

    private const val TAG = "OemKeepAlive"

    enum class OemType {
        XIAOMI,
        SAMSUNG,
        OPPO,
        VIVO,
        HUAWEI,
        STOCK_ANDROID
    }

    data class WhitelistStep(
        val title: String,
        val description: String,
        val intent: Intent?
    )

    /**
     * Detect the OEM type based on Build.MANUFACTURER.
     */
    fun getOemType(): OemType = when {
        Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
        Build.MANUFACTURER.equals("redmi", ignoreCase = true) ||
        Build.MANUFACTURER.equals("poco", ignoreCase = true) -> OemType.XIAOMI

        Build.MANUFACTURER.equals("samsung", ignoreCase = true) -> OemType.SAMSUNG

        Build.MANUFACTURER.equals("oppo", ignoreCase = true) ||
        Build.MANUFACTURER.equals("realme", ignoreCase = true) ||
        Build.MANUFACTURER.equals("oneplus", ignoreCase = true) -> OemType.OPPO

        Build.MANUFACTURER.equals("vivo", ignoreCase = true) -> OemType.VIVO

        Build.MANUFACTURER.equals("huawei", ignoreCase = true) ||
        Build.MANUFACTURER.equals("honor", ignoreCase = true) -> OemType.HUAWEI

        else -> OemType.STOCK_ANDROID
    }

    /**
     * Check if the app is already exempt from battery optimizations (standard Android).
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Request the standard Android battery optimization exemption dialog.
     * This is the first step; OEM-specific steps follow.
     */
    fun requestBatteryOptimizationExemption(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Get the OEM-specific whitelist steps the user needs to follow.
     * Returns an ordered list of steps with intents where possible.
     */
    fun getWhitelistSteps(context: Context): List<WhitelistStep> {
        val steps = mutableListOf<WhitelistStep>()

        // Step 1: Universal — request battery optimization exemption
        if (!isIgnoringBatteryOptimizations(context)) {
            steps.add(WhitelistStep(
                title = "Disable battery optimization",
                description = "Allow the app to run in the background without restrictions",
                intent = requestBatteryOptimizationExemption(context)
            ))
        }

        // Step 2+: OEM-specific steps
        when (getOemType()) {
            OemType.XIAOMI -> {
                steps.add(WhitelistStep(
                    title = "Enable AutoStart",
                    description = "Settings → Apps → Manage apps → Warung Tom Yam → AutoStart → Enable",
                    intent = createXiaomiAutoStartIntent()
                ))
                steps.add(WhitelistStep(
                    title = "Set battery saver to No restrictions",
                    description = "Settings → Battery → App battery saver → Warung Tom Yam → No restrictions",
                    intent = createXiaomiBatteryIntent()
                ))
                steps.add(WhitelistStep(
                    title = "Lock app in recents",
                    description = "Open Recent Apps → Long-press Warung Tom Yam → tap Lock icon",
                    intent = null
                ))
            }

            OemType.SAMSUNG -> {
                steps.add(WhitelistStep(
                    title = "Add to Never Sleeping Apps",
                    description = "Settings → Battery → Background usage limits → Never sleeping apps → Add Warung Tom Yam",
                    intent = createSamsungBatteryIntent()
                ))
                steps.add(WhitelistStep(
                    title = "Set battery to Unrestricted",
                    description = "Settings → Apps → Warung Tom Yam → Battery → Unrestricted",
                    intent = createAppBatterySettingsIntent(context)
                ))
            }

            OemType.OPPO -> {
                steps.add(WhitelistStep(
                    title = "Enable Auto Launch",
                    description = "Settings → Battery → App Launch Management → Warung Tom Yam → Manual → Enable all three toggles",
                    intent = createOppoAutoLaunchIntent()
                ))
            }

            OemType.VIVO -> {
                steps.add(WhitelistStep(
                    title = "Allow background activity",
                    description = "Settings → Battery → Background Power Consumption → Warung Tom Yam → Don't restrict",
                    intent = createVivoBackgroundIntent()
                ))
                steps.add(WhitelistStep(
                    title = "Enable AutoStart",
                    description = "i Manager → App Manager → Autostart → Enable Warung Tom Yam",
                    intent = createVivoAutoStartIntent()
                ))
            }

            OemType.HUAWEI -> {
                steps.add(WhitelistStep(
                    title = "Set App Launch to Manual",
                    description = "Settings → Battery → App launch → Warung Tom Yam → Manual → Enable all three",
                    intent = createHuaweiProtectedAppsIntent()
                ))
            }

            OemType.STOCK_ANDROID -> {
                // Standard battery optimization exemption is sufficient
                Log.i(TAG, "Stock Android — no OEM-specific steps needed")
            }
        }

        return steps
    }

    /**
     * Attempt to launch an OEM-specific intent. Returns true if the intent resolved.
     */
    fun launchSafely(context: Context, intent: Intent): Boolean {
        return try {
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                Log.w(TAG, "Intent not resolvable: ${intent.component}")
                // Fallback to app info settings
                context.startActivity(createAppBatterySettingsIntent(context))
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch intent", e)
            false
        }
    }

    // --- OEM-specific intent factories ---

    private fun createXiaomiAutoStartIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )
    }

    private fun createXiaomiBatteryIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.miui.powerkeeper",
            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
        )
    }

    private fun createSamsungBatteryIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.samsung.android.lool",
            "com.samsung.android.sm.battery.ui.BatteryActivity"
        )
    }

    private fun createOppoAutoLaunchIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.coloros.safecenter",
            "com.coloros.privacypermissionsentry.PermissionTopActivity"
        )
    }

    private fun createVivoBackgroundIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.vivo.abe",
            "com.vivo.applicationbased.ActivityApplicationWhiteList"
        )
    }

    private fun createVivoAutoStartIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        )
    }

    private fun createHuaweiProtectedAppsIntent(): Intent = Intent().apply {
        component = ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.optimize.process.ProtectActivity"
        )
    }

    private fun createAppBatterySettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}
