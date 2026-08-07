package com.razstudio.pos.data.local

import android.util.Log
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.BackendGateway
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls the café's floor plan down from the backend, for whichever role is asking.
 *
 * ## The two bugs this replaces
 *
 * There used to be one method, `rehydrateTablesIfEmpty()`, living on the admin ViewModel and
 * guarded by `if (tableDao.getCount() > 0) return`. It failed in two different ways on two
 * different devices on the same afternoon:
 *
 *  - **Ordering staff never got tables at all.** The only caller was `AdminHomeScreen`. Staff land
 *    on `StaffTableViewScreen`, which never invoked it, so a freshly-joined staff phone had no code
 *    path to fetch the floor plan — it showed an empty grid forever while the menu synced fine
 *    through its own separate path.
 *
 *  - **A count is not provenance.** On an admin till, three leftover rows from Demo Mode — which
 *    seeds straight into the real Room database — made the count non-zero, so the guard concluded
 *    the device already knew this café's tables and returned. The café's real thirty-six were never
 *    fetched, and the till showed "Table 1/2/3" belonging to nothing.
 *
 * ## What replaces the guard
 *
 * A stored marker naming the café the local tables were last synced *for*. Tables whose origin does
 * not match the café now signed in are foreign — whether they came from Demo Mode, a previous café,
 * or a build before this marker existed — and are replaced rather than trusted.
 *
 * ## Why nothing is deleted before the fetch succeeds
 *
 * The replace happens only after the backend has actually returned a floor plan. A café that is
 * offline, or whose backend is briefly unreachable, keeps the tables it has: the old code's failure
 * mode was showing the wrong tables, and the fix must not introduce a worse one where a network
 * blip empties the screen mid-service.
 */
@Singleton
class TableSync @Inject constructor(
    private val backend: BackendGateway,
    private val tableDao: TableDao,
    private val settingsDao: SettingsDao,
    private val appConfigStore: AppConfigStore,
    private val localPrefs: LocalPrefs,
) {

    /**
     * Fetch and adopt the floor plan when this device does not already hold this café's.
     *
     * Safe to call on every entry to a table screen: when the marker matches and tables exist it
     * does nothing at all, so it costs one comparison on the common path.
     */
    suspend fun syncIfNeeded() {
        val cafeKey = cafeKey()
        val syncedFor = localPrefs.tablesSyncedForCafe
        val haveTables = tableDao.getCount() > 0

        // Tables of unknown or foreign origin are not evidence that this device knows the café.
        // A null marker means "written before this marker existed, or by Demo Mode" — both of which
        // are exactly the cases that produced the wrong floor plan.
        val originTrusted = syncedFor != null && syncedFor == cafeKey
        if (originTrusted && haveTables) return

        when (val result = backend.getTables()) {
            is ApiResult.Success -> {
                if (result.data.isEmpty() && haveTables) {
                    // A café that genuinely has no tables yet is indistinguishable here from a
                    // backend that answered oddly. Keeping what we have is the safer read: an admin
                    // can always delete tables deliberately, but nobody can undo a wipe mid-service.
                    Log.w(TAG, "Backend returned no tables; keeping the ${tableDao.getCount()} local ones")
                    return
                }
                adopt(result.data)
                localPrefs.tablesSyncedForCafe = cafeKey
                Log.i(TAG, "Adopted ${result.data.size} tables for $cafeKey")
            }
            else -> {
                // Best-effort. An admin can still add tables by hand, and the next screen entry
                // retries — neither of which is true if this throws.
                Log.w(TAG, "Could not fetch tables; leaving local state untouched")
            }
        }
    }

    /** Replace the local floor plan wholesale, then keep table numbering ahead of it. */
    private suspend fun adopt(tables: List<Pair<String, String>>) {
        tableDao.deleteAll()
        tables.forEachIndexed { index, (id, label) ->
            tableDao.insert(Table(id = id, label = label, sortOrder = index))
        }

        // A fresh install's nextTableNumber restarts at 1 — bump it past the highest adopted
        // "T####" so the next Add Table cannot collide with one just pulled from the server.
        val highest = tables
            .mapNotNull { (id, _) -> Regex("""^T(\d+)$""").find(id)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        if (highest > 0) {
            val settings = settingsDao.get() ?: SystemSettings()
            if (settings.nextTableNumber <= highest) {
                settingsDao.upsert(settings.copy(nextTableNumber = highest + 1))
            }
        }
    }

    /**
     * What "this café" means for provenance.
     *
     * The Supabase URL is the café's identity on a cloud device and is already stored. Off-cloud it
     * is blank, so the café name stands in — imperfect as a key, but LAN and Kiosk tills serve one
     * café and never migrate between them, which is the only thing this marker guards against.
     */
    private fun cafeKey(): String =
        appConfigStore.supabaseUrl().ifBlank { appConfigStore.cafeName() }.ifBlank { "unconfigured" }

    private companion object {
        const val TAG = "TableSync"
    }
}
