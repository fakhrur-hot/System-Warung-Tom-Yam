package com.razstudio.pos.data.google

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who is signed in, and which café of theirs this device is running.
 *
 * ## Two things, deliberately separate
 *
 * The Google account and the chosen café are stored and cleared independently, because the sample
 * design distinguishes two logouts and they are not the same action:
 *
 *  - **Mode Logout** clears [selectedFolderId] and keeps the account. The owner returns to the home
 *    screen and picks a different café of theirs without re-authenticating.
 *  - **Google Logout** clears everything. No café folders are visible until somebody signs in again,
 *    possibly as a different owner.
 *
 * Collapsing them would mean a staff member ending their shift also signs the owner out of Google,
 * mid-service, on the counter phone.
 *
 * ## Why this is persisted rather than held in memory
 *
 * The avatar has to be on screen the moment the app opens, before any network call — an avatar that
 * pops in a second late reads as a glitch. And the café choice must survive a restart, or the owner
 * is asked to pick their own café every morning, which is the "cannot be undone" rule inverted.
 *
 * Plain `SharedPreferences`, not encrypted: an email address, a display name and a Drive folder id
 * are not credentials. The Drive **access token** is deliberately NOT stored — it is short-lived and
 * re-obtained from the Authorization API on demand, so there is nothing here worth stealing.
 */
@Singleton
class GoogleAccountSession @Inject constructor(
    @ApplicationContext context: Context,
) {

    data class Account(
        val email: String,
        val displayName: String,
        /** Google profile picture. Empty when the account has none — the UI falls back to an initial. */
        val photoUrl: String,
    )

    /** A café bundle the signed-in account holds, as the chooser lists it. */
    data class CafeChoice(
        val folderId: String,
        val mode: String,
        val cafeName: String,
    ) {
        /** `RAZS.POS-LAN-Tani Tom Yam` — the Drive folder name this came from. */
        val folderName: String get() = "${CafeBundleStore.FILE_PREFIX}$mode-$cafeName"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _account = MutableStateFlow(readAccount())
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _selected = MutableStateFlow(readSelected())
    val selected: StateFlow<CafeChoice?> = _selected.asStateFlow()

    val isSignedIn: Boolean get() = _account.value != null

    fun setAccount(email: String, displayName: String, photoUrl: String) {
        prefs.edit {
            putString(KEY_EMAIL, email)
            putString(KEY_NAME, displayName)
            putString(KEY_PHOTO, photoUrl)
        }
        _account.value = Account(email, displayName, photoUrl)
    }

    /**
     * Remember which café was chosen, so the next launch opens straight into it.
     *
     * This is the "cannot be undone" rule in the sample: choosing is not a filter that can be
     * changed on a whim, because choosing *loads* — it replaces what is on the device. Switching
     * requires Mode Logout, which is a deliberate act with its own confirmation.
     */
    fun setSelected(choice: CafeChoice) {
        prefs.edit {
            putString(KEY_FOLDER_ID, choice.folderId)
            putString(KEY_FOLDER_MODE, choice.mode)
            putString(KEY_FOLDER_CAFE, choice.cafeName)
        }
        _selected.value = choice
    }

    /** Mode Logout — drop the café, keep the account, so the chooser can be shown again. */
    fun clearSelectedCafe() {
        prefs.edit {
            remove(KEY_FOLDER_ID)
            remove(KEY_FOLDER_MODE)
            remove(KEY_FOLDER_CAFE)
        }
        _selected.value = null
    }

    /** Google Logout — the account and everything derived from it. */
    fun clearAll() {
        prefs.edit { clear() }
        _account.value = null
        _selected.value = null
    }

    private fun readAccount(): Account? {
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        return Account(
            email = email,
            displayName = prefs.getString(KEY_NAME, null).orEmpty().ifBlank { email },
            photoUrl = prefs.getString(KEY_PHOTO, null).orEmpty(),
        )
    }

    private fun readSelected(): CafeChoice? {
        val id = prefs.getString(KEY_FOLDER_ID, null) ?: return null
        return CafeChoice(
            folderId = id,
            mode = prefs.getString(KEY_FOLDER_MODE, null).orEmpty(),
            cafeName = prefs.getString(KEY_FOLDER_CAFE, null).orEmpty(),
        )
    }

    private companion object {
        const val PREFS = "google_account_session"
        const val KEY_EMAIL = "email"
        const val KEY_NAME = "display_name"
        const val KEY_PHOTO = "photo_url"
        const val KEY_FOLDER_ID = "selected_folder_id"
        const val KEY_FOLDER_MODE = "selected_folder_mode"
        const val KEY_FOLDER_CAFE = "selected_folder_cafe"
    }
}
