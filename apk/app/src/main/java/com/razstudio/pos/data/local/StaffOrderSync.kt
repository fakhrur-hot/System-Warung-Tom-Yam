package com.razstudio.pos.data.local

import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.OrderDto
import com.razstudio.pos.data.json.toEntity
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The staff device's single path for pulling orders from the cloud into Room.
 *
 * ### Why this is one shared object and not a method on the ViewModel
 *
 * Two things now ask for a sync: the ViewModel's poll, and — once the LAN push socket is wired — the
 * foreground service the moment a push frame arrives. If each kept its own fetch-and-reconcile, they
 * would each carry their own `since` watermark and their own idea of what has been seen, and the two
 * would drift. That is the same trap `RealtimeService` documents for the admin side: a push must
 * trigger *the same* function the poll runs, never a second path with its own de-duplication.
 *
 * So the watermark lives here, guarded by a mutex, and both callers go through [syncNow].
 *
 * ### The watermark
 *
 * First call reaches back 24h; later ones ask only for changes since the **server's** clock reading,
 * not the phone's — a device whose clock runs fast would otherwise skip orders created in the gap.
 * A failed call leaves the watermark untouched, so nothing is missed on the retry.
 *
 * ### Why a poll still exists at all
 *
 * The poll is authoritative and the push is latency. Every push can be lost — socket down, frame
 * dropped, app backgrounded — and the floor still converges on the next tick.
 */
@Singleton
class StaffOrderSync @Inject constructor(
    private val apiClient: BackendGateway,
    private val orderDao: OrderDao,
) {
    private val mutex = Mutex()
    private var lastSyncIso: String? = null

    /**
     * Pull and reconcile. Serialized: a push frame arriving while the poll is mid-flight waits rather
     * than issuing a second overlapping fetch against the same watermark.
     *
     * Returns true when the server answered, false on error/offline — the caller decides whether
     * that is worth reporting; neither case throws.
     */
    suspend fun syncNow(): Boolean = mutex.withLock {
        val since = lastSyncIso ?: Instant.now().minusSeconds(86_400).toString()
        when (val result = apiClient.getOrdersSinceAsStaff(since)) {
            is ApiResult.Success -> {
                for (dto in result.data.orders) reconcile(dto)
                lastSyncIso = result.data.serverTime
                true
            }
            is ApiResult.Error -> false
            is ApiResult.NetworkError -> false
        }
    }

    /**
     * Insert-or-update in place. Terminal orders matter as much as active ones: the endpoint returns
     * COMPLETED/CANCELLED orders after `since`, and writing them is what makes a settled table drop
     * out of `getActiveOrdersFlow` and turn green on the floor.
     */
    private suspend fun reconcile(dto: OrderDto) {
        orderDao.insertOrder(dto.toEntity())
        if (dto.items.isNotEmpty()) {
            // Replace, never append — the local copy of an order this device created (split
            // shares especially) carries different line ids than the server's copy of the same
            // lines, so a plain insert duplicated every line. See RealtimeService.reconcileOrder.
            orderDao.deleteItemsForOrder(dto.id)
            orderDao.insertOrderItems(dto.items.map { it.toEntity(dto.id) })
        }
    }
}
