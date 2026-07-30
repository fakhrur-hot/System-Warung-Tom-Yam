package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.AppConfigStore
import com.razstudio.pos.data.local.AmbientSettingsStore
import com.razstudio.pos.data.local.Order
import com.razstudio.pos.data.local.OrderDao
import com.razstudio.pos.data.local.TableDao
import com.razstudio.pos.ui.tableview.TableState
import com.razstudio.pos.ui.tableview.TableUiStatus
import com.razstudio.pos.ui.tableview.toTableUiStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs Ambient (screensaver) mode.
 *
 * Reads the SAME reactive Room flows as the live Table View — [TableDao.getAllFlow] +
 * [OrderDao.getActiveOrdersFlow] reduced into the shared [TableState] / [TableUiStatus] model — so
 * ambient mode can never disagree with what staff see when they tap back in. It deliberately owns
 * no fetching of its own: orders from BOTH the customer web and ordering staff arrive in Room via
 * `RealtimeService`'s catch-up poll, and this view-model simply observes the result.
 *
 * Note on latency: in the field the Realtime WebSocket connects but does not deliver NEW_ORDER
 * frames, so an order surfaces here when the catch-up poll writes it to Room (see
 * `RealtimeService.POLL_INTERVAL_MS`, which tightens while ambient mode is on).
 */
@HiltViewModel
class AmbientViewModel @Inject constructor(
    private val tableDao: TableDao,
    private val orderDao: OrderDao,
    private val ambientSettings: AmbientSettingsStore,
    private val appConfig: AppConfigStore,
) : ViewModel() {

    /** Live table occupancy — identical derivation to `TableViewViewModel.tableStates`. */
    val tableStates: StateFlow<List<TableState>> = combine(
        tableDao.getAllFlow(),
        orderDao.getActiveOrdersFlow()
    ) { tables, orders ->
        tables.map { table ->
            val order = orders.find { it.tableId == table.id }
            TableState(
                table = table,
                status = order?.status?.toTableUiStatus() ?: TableUiStatus.FREE,
                order = order,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The most recent freshly-arrived order, surfaced as a transient celebration card. */
    private val _newOrder = MutableStateFlow<Order?>(null)
    val newOrder: StateFlow<Order?> = _newOrder.asStateFlow()

    /** Café name for the ambient header; blank falls back to the app name at the call site. */
    val cafeName: String get() = appConfig.cafeName()

    fun isCustomerFacing(): Boolean = ambientSettings.isCustomerFacing()

    init {
        observeNewOrders()
    }

    /**
     * Announce only orders that appear AFTER ambient mode starts observing. The first emission
     * primes the seen-set, so re-entering ambient mode never re-announces the tables that were
     * already occupied.
     */
    private fun observeNewOrders() {
        viewModelScope.launch {
            val seen = mutableSetOf<String>()
            var primed = false
            orderDao.getActiveOrdersFlow().collect { orders ->
                if (!primed) {
                    orders.forEach { seen.add(it.id) }
                    primed = true
                    return@collect
                }
                val fresh = orders.filter { seen.add(it.id) }
                if (fresh.isNotEmpty()) {
                    _newOrder.value = fresh.maxByOrNull { it.createdAt }
                }
            }
        }
    }

    /** Dismiss the new-order card (called by the UI after its display window elapses). */
    fun clearNewOrder() {
        _newOrder.value = null
    }
}
