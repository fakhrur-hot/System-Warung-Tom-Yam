package com.razstudio.pos.notification

/**
 * Supported Malaysian eWallet and banking apps for payment notification monitoring.
 * Each entry maps a wallet app to one or more Android package names that may post
 * payment-received notifications.
 */
enum class WalletApp(
    val displayName: String,
    val packages: List<String>,
) {
    TNG_EWALLET(
        displayName = "Touch 'n Go eWallet",
        packages = listOf("my.com.tngdigital.ewallet"),
    ),
    TNG_MERCHANT(
        displayName = "TNG Merchant",
        packages = listOf("com.tng.merchant"),
    ),
    BOOST(
        displayName = "Boost",
        packages = listOf("my.com.axiata.boostapp"),
    ),
    GRABPAY_MERCHANT(
        displayName = "GrabPay Merchant",
        packages = listOf("com.grab.merchant", "com.grabtaxi.passenger"),
    ),
    SHOPEEPAY(
        displayName = "ShopeePay",
        packages = listOf("com.shopee.my", "com.shopee.my.merchant"),
    ),
    MAYBANK_MAE(
        displayName = "Maybank MAE",
        packages = listOf("com.maybank2u.life"),
    ),
    DUITNOW_CIMB(
        displayName = "CIMB (DuitNow)",
        packages = listOf("my.com.cimbclicks.activities"),
    ),
    DUITNOW_RHB(
        displayName = "RHB (DuitNow)",
        packages = listOf("com.rhbgroup.rhbmobile"),
    ),
    DUITNOW_AMBANK(
        displayName = "AmBank (DuitNow)",
        packages = listOf("com.anz.android.gomoney"),
    );

    companion object {
        /** Reverse lookup: package name → WalletApp. Lazily built for efficiency. */
        private val packageIndex: Map<String, WalletApp> by lazy {
            entries.flatMap { app -> app.packages.map { pkg -> pkg to app } }.toMap()
        }

        /** Returns the WalletApp associated with the given Android package name, or null if unknown. */
        fun fromPackage(packageName: String): WalletApp? = packageIndex[packageName]

        /** All package names across all supported apps (for the notification filter). */
        fun allPackages(): Set<String> = entries.flatMap { it.packages }.toSet()
    }
}
