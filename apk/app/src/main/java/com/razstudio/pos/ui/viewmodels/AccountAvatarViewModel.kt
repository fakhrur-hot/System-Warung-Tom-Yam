package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.data.google.GoogleAccountSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Backs [com.razstudio.pos.ui.components.AccountAvatar] — the account, and the two ways to leave it.
 *
 * Deliberately thin: the avatar appears on the home screen, the admin till and the staff table view,
 * and a ViewModel that also knew about cafés or navigation would drag all of that into three
 * unrelated screens.
 */
@HiltViewModel
class AccountAvatarViewModel @Inject constructor(
    private val session: GoogleAccountSession,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    val account: StateFlow<GoogleAccountSession.Account?> = session.account

    /**
     * Mode Logout — leave this café, stay signed in to Google.
     *
     * Clears the device's own session as well as the café choice. Both matter: without the café
     * choice the home screen shows the chooser again, and without clearing the local session the
     * app would route straight back into the till it was just asked to leave.
     *
     * The Google account is untouched, so the owner's other cafés stay listed and switching costs
     * no re-authentication.
     */
    fun modeLogout() {
        session.clearSelectedCafe()
        secureStorage.clearAll()
    }

    /**
     * Google Logout — clear the account entirely.
     *
     * Every café folder disappears from this device until someone signs in again, possibly as a
     * different owner. Offered only on the home screen: doing this from the counter mid-service is
     * a much bigger action than its one-word label suggests.
     */
    fun googleLogout() {
        session.clearAll()
        secureStorage.clearAll()
    }
}
