package com.razstudio.pos.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletAppTest {

    @Test
    fun fromPackage_returnsCorrectApp_forKnownPackages() {
        assertEquals(WalletApp.TNG_EWALLET, WalletApp.fromPackage("my.com.tngdigital.ewallet"))
        assertEquals(WalletApp.TNG_MERCHANT, WalletApp.fromPackage("com.tng.merchant"))
        assertEquals(WalletApp.BOOST, WalletApp.fromPackage("my.com.axiata.boostapp"))
        assertEquals(WalletApp.GRABPAY_MERCHANT, WalletApp.fromPackage("com.grab.merchant"))
        assertEquals(WalletApp.GRABPAY_MERCHANT, WalletApp.fromPackage("com.grabtaxi.passenger"))
        assertEquals(WalletApp.SHOPEEPAY, WalletApp.fromPackage("com.shopee.my"))
        assertEquals(WalletApp.SHOPEEPAY, WalletApp.fromPackage("com.shopee.my.merchant"))
        assertEquals(WalletApp.MAYBANK_MAE, WalletApp.fromPackage("com.maybank2u.life"))
        assertEquals(WalletApp.DUITNOW_CIMB, WalletApp.fromPackage("my.com.cimbclicks.activities"))
        assertEquals(WalletApp.DUITNOW_RHB, WalletApp.fromPackage("com.rhbgroup.rhbmobile"))
        assertEquals(WalletApp.DUITNOW_AMBANK, WalletApp.fromPackage("com.anz.android.gomoney"))
    }

    @Test
    fun fromPackage_returnsNull_forUnknownPackage() {
        assertNull(WalletApp.fromPackage("com.unknown.app"))
        assertNull(WalletApp.fromPackage(""))
    }

    @Test
    fun allPackages_containsEveryDeclaredPackage() {
        val all = WalletApp.allPackages()
        WalletApp.entries.forEach { app ->
            app.packages.forEach { pkg ->
                assertTrue("allPackages() should contain $pkg", all.contains(pkg))
            }
        }
    }

    @Test
    fun allPackages_sizeMatchesTotalDeclaredPackages() {
        val expected = WalletApp.entries.sumOf { it.packages.size }
        assertEquals(expected, WalletApp.allPackages().size)
    }

    @Test
    fun everyEntryHasNonEmptyDisplayName() {
        WalletApp.entries.forEach { app ->
            assertTrue(
                "${app.name} must have a non-empty displayName",
                app.displayName.isNotBlank(),
            )
        }
    }

    @Test
    fun everyEntryHasAtLeastOnePackage() {
        WalletApp.entries.forEach { app ->
            assertTrue(
                "${app.name} must declare at least one package",
                app.packages.isNotEmpty(),
            )
        }
    }
}
