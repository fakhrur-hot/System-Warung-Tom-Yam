package com.warungtomyam.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.warungtomyam.pos.data.SecureStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Device-local admin-settings PIN lock. Backed by [SecureStorage] (AES-256-GCM), shared
 * between the gate (before entering Settings) and the toggle/change UI inside Settings.
 *
 * This is a soft second factor on the already-admin device — it stops a staff member who
 * borrows the admin phone from casually changing settings. "Forgot PIN" is allowed because
 * the caller already holds a valid admin session (they are on the admin device).
 */
@HiltViewModel
class PinLockViewModel @Inject constructor(
    private val secureStorage: SecureStorage
) : ViewModel() {

    /** True when the gate should challenge before Settings opens. */
    fun isGateActive(): Boolean = secureStorage.isPinLockEnabled() && secureStorage.getAdminPin() != null

    fun isPinLockEnabled(): Boolean = secureStorage.isPinLockEnabled()

    fun hasPin(): Boolean = secureStorage.getAdminPin() != null

    fun verifyPin(pin: String): Boolean = pin == secureStorage.getAdminPin()

    /** Turn the lock on and set the initial PIN. Returns false if the PIN isn't 4 digits. */
    fun enableWithPin(pin: String): Boolean {
        if (!isValidPin(pin)) return false
        secureStorage.saveAdminPin(pin)
        secureStorage.setPinLockEnabled(true)
        return true
    }

    /** Change the PIN — requires the current one. */
    fun changePin(currentPin: String, newPin: String): Boolean {
        if (!verifyPin(currentPin) || !isValidPin(newPin)) return false
        secureStorage.saveAdminPin(newPin)
        return true
    }

    /** Turn the lock off and forget the PIN. */
    fun disable() {
        secureStorage.setPinLockEnabled(false)
        secureStorage.clearAdminPin()
    }

    /** Reset when the PIN is forgotten — permitted because this is the logged-in admin device. */
    fun resetForgotten() {
        secureStorage.clearAdminPin()
        secureStorage.setPinLockEnabled(false)
    }

    private fun isValidPin(pin: String): Boolean = pin.length == 4 && pin.all { it.isDigit() }
}
