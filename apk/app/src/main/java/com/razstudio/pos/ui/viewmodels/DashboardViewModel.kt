package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.local.DailyRevenue
import com.razstudio.pos.data.local.HourlyRevenue
import com.razstudio.pos.data.local.OrderDao
import com.razstudio.pos.data.local.PaymentMethodCount
import com.razstudio.pos.data.local.PopularItemRow
import com.razstudio.pos.data.local.TableRevenue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

data class DashboardUiState(
    val totalRevenue: Double = 0.0,
    val totalOrders: Int = 0,
    val avgOrderValue: Double = 0.0,
    val hourlyRevenue: List<HourlyRevenue> = emptyList(),
    val dailyRevenue: List<DailyRevenue> = emptyList(),
    val paymentSplit: List<PaymentMethodCount> = emptyList(),
    val bestSellers: List<PopularItemRow> = emptyList(),
    val revenueByTable: List<TableRevenue> = emptyList(),
    val cancelledCount: Int = 0,
    val cancelledRate: Float = 0f,
    val yesterdayOrders: Int = 0,
    val isLoading: Boolean = true,
)

/**
 * Live dashboard data. Reloads on init and can be refreshed manually.
 * All data is for "today" (current business day).
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val orderDao: OrderDao,
    private val apiClient: BackendGateway,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    /** Live active-order count via Room Flow. */
    val activeOrderCount: StateFlow<Int> = orderDao.getActiveOrderCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private companion object {
        const val DEFAULT_TZ = "Asia/Kuala_Lumpur"
    }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)

            val startHour = when (val r = apiClient.getSettings()) {
                is ApiResult.Success -> r.data.businessDayStartHour
                else -> 15
            }.coerceIn(0, 23)

            val tz = TimeZone.getTimeZone(DEFAULT_TZ)
            val cal = Calendar.getInstance(tz)
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).also { it.timeZone = tz }

            // Anchor to business day
            if (cal.get(Calendar.HOUR_OF_DAY) < startHour) {
                cal.add(Calendar.DAY_OF_MONTH, -1)
            }
            val todayDate = fmt.format(cal.time)
            val hh = startHour.toString().padStart(2, '0')
            val todayStart = "${todayDate}T$hh:00:00"
            val tomorrowStart = "${java.time.LocalDate.parse(todayDate).plusDays(1)}T$hh:00:00"

            // Yesterday range
            val yesterdayDate = java.time.LocalDate.parse(todayDate).minusDays(1).toString()
            val yesterdayStart = "${yesterdayDate}T$hh:00:00"

            try {
                val totalRevenue = orderDao.getTotalRevenueBetween(todayStart, tomorrowStart)
                val totalOrders = orderDao.getCompletedOrderCountBetween(todayStart, tomorrowStart)
                val avgOrder = if (totalOrders > 0) totalRevenue / totalOrders else 0.0
                val hourly = orderDao.getHourlyRevenue(todayStart, tomorrowStart)
                val paymentSplit = orderDao.getOrdersByPaymentMethod(todayStart, tomorrowStart)
                val bestSellers = orderDao.getPopularItems(todayStart, tomorrowStart).take(5)
                val revenueByTable = orderDao.getRevenueByTable(todayStart, tomorrowStart)
                val cancelled = orderDao.getCancelledOrders(todayStart, tomorrowStart)
                val cancelledCount = cancelled.size
                val allSettled = totalOrders + cancelledCount
                val cancelRate = if (allSettled > 0) cancelledCount.toFloat() / allSettled else 0f
                val yesterdayOrders = orderDao.getCompletedOrderCountBetween(yesterdayStart, todayStart)

                // Daily trend: last 7 days (including today's business day)
                val sevenDaysAgo = java.time.LocalDate.parse(todayDate).minusDays(6).toString()
                val dailyTrendStart = "${sevenDaysAgo}T$hh:00:00"
                val dailyRevenue = orderDao.getDailyRevenue(dailyTrendStart, tomorrowStart)

                _state.value = DashboardUiState(
                    totalRevenue = totalRevenue,
                    totalOrders = totalOrders,
                    avgOrderValue = avgOrder,
                    hourlyRevenue = hourly,
                    dailyRevenue = dailyRevenue,
                    paymentSplit = paymentSplit,
                    bestSellers = bestSellers,
                    revenueByTable = revenueByTable,
                    cancelledCount = cancelledCount,
                    cancelledRate = cancelRate,
                    yesterdayOrders = yesterdayOrders,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }
}
