package com.razstudio.pos.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.data.local.AppDatabase
import com.razstudio.pos.ui.util.PaymentQrPipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Detach this terminal from its café so it can be provisioned for a different one.
 *
 * ## The job this does
 *
 * "Scan a QR and the till is live" only holds for a device that has never belonged to a café. Move a
 * working terminal between cafés — a stall closes, a spare is reassigned, a device comes back from
 * repair — and scanning the new owner key used to fail in the most misleading way available: the
 * backend refuses to be repointed, the token is sent to the OLD café's Supabase, that project quite
 * correctly rejects a token it never issued, and the screen reports a bad key. The key was fine.
 *
 * This is the missing half of set-and-forget: forgetting.
 *
 * ## Why the local database goes too
 *
 * The config alone is not what makes a device belong to a café. Room still holds the previous
 * café's menu, prices, floor plan, order history and printers. A terminal that joined a new café
 * with the old one's menu still on it would take orders for items the new café does not sell, at
 * prices it never set, against tables it does not have — and the new café's own data would arrive
 * *alongside* rather than instead of it.
 *
 * `clearAllTables()` also takes the printer rows, which are genuinely device-local and would have
 * been fine to keep. Re-adding a printer takes a minute; disentangling two cafés' menus does not,
 * and there is no partial wipe that is obviously correct. The screen says so before it runs.
 *
 * ## What survives
 *
 * The device identity in [SecureStorage] — it describes the hardware, not the café. Regenerating it
 * would strand the device row on a backend this call has already forgotten how to reach, leaving an
 * un-removable ghost in the old café's device list.
 */
@HiltViewModel
class UnlinkDeviceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appConfig: AppConfigStore,
    private val secureStorage: SecureStorage,
    private val database: AppDatabase,
) : ViewModel() {

    /**
     * Wipe every café-scoped trace, then hand control back so the caller can restart the app.
     *
     * Ordered so a failure part-way through cannot leave a device that still looks signed in: the
     * session goes first, which is the thing that would otherwise let a half-unlinked terminal keep
     * talking to the old café.
     */
    fun unlink(onDone: () -> Unit) {
        viewModelScope.launch {
            // clearCloudCredentials drops the session token and API key but deliberately keeps the
            // role and device id. That is right for a mode switch, which is what it was written for;
            // here the role must go too, or a device that has forgotten its café still claims to be
            // that café's admin.
            secureStorage.clearCloudCredentials()
            secureStorage.clearRoleForUnlink()

            withContext(Dispatchers.IO) {
                // The previous café's bank QR, sitting in app-private storage. Everything else here
                // is recoverable; a stale payment code is the one that quietly sends the new café's
                // takings to the old café's account.
                PaymentQrPipeline.deleteFromInternal(context)
                database.clearAllTables()
            }

            appConfig.unlinkFromCafe()
            // Stop the keep-alive now rather than at the next launch: there is no café left to
            // keep awake, and the worker would otherwise wake up to ping a project this device
            // has just been told it no longer belongs to.
            com.razstudio.pos.data.local.KeepAliveHeartbeatWorker.cancel(context)
            onDone()
        }
    }
}
