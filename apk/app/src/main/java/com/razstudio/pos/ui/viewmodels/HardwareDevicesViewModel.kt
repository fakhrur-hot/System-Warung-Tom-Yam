package com.razstudio.pos.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.local.DrawerKick
import com.razstudio.pos.data.local.LocalPrefs
import com.razstudio.pos.data.local.PrinterConfig
import com.razstudio.pos.data.local.PrinterConfigDao
import com.razstudio.pos.data.local.PrinterTransport
import com.razstudio.pos.display.CustomerDisplayDriver
import com.razstudio.pos.display.DisplayDriverKind
import com.razstudio.pos.printing.PrinterDriver
import com.razstudio.pos.printing.sunmi.SunmiPrinterDriver
import com.razstudio.pos.ui.util.BluetoothHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Identifies a printer transport row on the Devices & Hardware screen. */
enum class PrinterDriverKind {
    BLUETOOTH,
    SUNMI_AIDL,
    USB,
    NETWORK,
}

/** Why a driver row is greyed out — resolved to localized text at the UI edge. */
enum class HardwareAvailabilityReason {
    AVAILABLE,
    BLUETOOTH_OFF,
    BLUETOOTH_UNAVAILABLE,
    SUNMI_NOT_DETECTED,
    NOT_IMPLEMENTED,
    NO_USB_DEVICE,
    NO_PRESENTATION_DISPLAY,
}

data class PrinterDriverRow(
    val kind: PrinterDriverKind,
    val availability: HardwareAvailabilityReason,
    /** Paired Bluetooth device count when [kind] is [PrinterDriverKind.BLUETOOTH] and available. */
    val pairedCount: Int? = null,
)

data class DrawerOptionRow(
    /** Null means "None". */
    val printerId: String?,
    val printerName: String?,
    val available: Boolean,
)

data class DisplayDriverRow(
    val kind: DisplayDriverKind,
    val availability: HardwareAvailabilityReason,
)

data class HardwareDevicesUiState(
    val printerDrivers: List<PrinterDriverRow> = emptyList(),
    val selectedPrinterTransport: PrinterDriverKind? = null,
    val drawerOptions: List<DrawerOptionRow> = emptyList(),
    val selectedDrawerPrinterId: String? = null,
    val displayDrivers: List<DisplayDriverRow> = emptyList(),
    val selectedDisplayDriver: DisplayDriverKind = DisplayDriverKind.NONE,
    val selectionSaved: Boolean = false,
)

@HiltViewModel
class HardwareDevicesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localPrefs: LocalPrefs,
    private val printerConfigDao: PrinterConfigDao,
    private val drivers: Set<@JvmSuppressWildcards PrinterDriver>,
    private val displayDrivers: Set<@JvmSuppressWildcards CustomerDisplayDriver>,
    private val sunmiDriver: SunmiPrinterDriver,
) : ViewModel() {

    private val bluetoothHelper = BluetoothHelper(context)

    private val _printerRows = MutableStateFlow<List<PrinterDriverRow>>(emptyList())
    private val _displayRows = MutableStateFlow(placeholderDisplayRows())
    private val _drawerExtras = MutableStateFlow<List<DrawerOptionRow>>(emptyList())
    private val _selectionSaved = MutableStateFlow(false)

    val uiState: StateFlow<HardwareDevicesUiState> = combine(
        printerConfigDao.getAllFlow(),
        _printerRows,
        _displayRows,
        _drawerExtras,
        _selectionSaved,
    ) { printers, printerRows, displayRows, drawerExtras, saved ->
        val selectedTransport = localPrefs.selectedPrinterTransport
            ?.let { runCatching { PrinterDriverKind.valueOf(it) }.getOrNull() }

        val selectedDisplay = localPrefs.selectedDisplayDriver
            ?.let { runCatching { DisplayDriverKind.valueOf(it) }.getOrNull() }
            ?: DisplayDriverKind.NONE

        HardwareDevicesUiState(
            printerDrivers = printerRows,
            selectedPrinterTransport = selectedTransport,
            drawerOptions = buildDrawerOptions(printers, drawerExtras),
            selectedDrawerPrinterId = localPrefs.selectedDrawerPrinterId,
            displayDrivers = displayRows,
            selectedDisplayDriver = selectedDisplay,
            selectionSaved = saved,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HardwareDevicesUiState())

    init {
        refreshAvailability()
    }

    fun refreshAvailability() {
        viewModelScope.launch {
            _printerRows.value = buildPrinterRows()
            _displayRows.value = buildDisplayRows()
            _drawerExtras.value = buildSunmiDrawerExtra()
        }
    }

    private suspend fun buildPrinterRows(): List<PrinterDriverRow> {
        val registered = drivers.associateBy { it.transport }
        return PrinterDriverKind.entries.map { kind ->
            val transport = kind.toTransport()
            val driver = registered[transport]
            if (driver != null) {
                val avail = driver.availability(context)
                if (avail.available) {
                    val paired = if (kind == PrinterDriverKind.BLUETOOTH) {
                        bluetoothHelper.getPairedDevices().size
                    } else null
                    PrinterDriverRow(kind, HardwareAvailabilityReason.AVAILABLE, paired)
                } else {
                    val reason = when (kind) {
                        PrinterDriverKind.BLUETOOTH -> when {
                            avail.reason?.contains("off", ignoreCase = true) == true ->
                                HardwareAvailabilityReason.BLUETOOTH_OFF
                            else -> HardwareAvailabilityReason.BLUETOOTH_UNAVAILABLE
                        }
                        PrinterDriverKind.SUNMI_AIDL -> HardwareAvailabilityReason.SUNMI_NOT_DETECTED
                        else -> HardwareAvailabilityReason.NOT_IMPLEMENTED
                    }
                    PrinterDriverRow(kind, reason)
                }
            } else {
                val reason = when (kind) {
                    PrinterDriverKind.USB -> HardwareAvailabilityReason.NO_USB_DEVICE
                    PrinterDriverKind.NETWORK -> HardwareAvailabilityReason.NOT_IMPLEMENTED
                    else -> HardwareAvailabilityReason.NOT_IMPLEMENTED
                }
                PrinterDriverRow(kind, reason)
            }
        }
    }

    /**
     * Probes each registered display driver. Every [DisplayDriverKind] gets a row even when no
     * driver is bound for it, so the café sees hardware it *could* buy with a reason attached
     * rather than an unexplained gap. (HW-REQ-6)
     */
    private suspend fun buildDisplayRows(): List<DisplayDriverRow> {
        val registered = displayDrivers.associateBy { it.kind }
        return DisplayDriverKind.entries.map { kind ->
            val driver = registered[kind]
            val reason = when {
                driver == null -> when (kind) {
                    // VFD is task 10.2 and needs hardware nobody has attached yet.
                    DisplayDriverKind.VFD_SERIAL -> HardwareAvailabilityReason.NO_USB_DEVICE
                    else -> HardwareAvailabilityReason.NOT_IMPLEMENTED
                }
                driver.availability(context).available -> HardwareAvailabilityReason.AVAILABLE
                kind == DisplayDriverKind.PRESENTATION ->
                    HardwareAvailabilityReason.NO_PRESENTATION_DISPLAY
                else -> HardwareAvailabilityReason.NOT_IMPLEMENTED
            }
            DisplayDriverRow(kind, reason)
        }
    }

    /**
     * Pre-probe placeholder. Only "None" is claimed available before the real probe lands, so the
     * screen can never briefly offer a display that turns out not to exist.
     */
    private fun placeholderDisplayRows(): List<DisplayDriverRow> = DisplayDriverKind.entries.map { kind ->
        DisplayDriverRow(
            kind,
            if (kind == DisplayDriverKind.NONE) HardwareAvailabilityReason.AVAILABLE
            else HardwareAvailabilityReason.NOT_IMPLEMENTED
        )
    }

    private suspend fun buildSunmiDrawerExtra(): List<DrawerOptionRow> {
        val printers = printerConfigDao.getAll()
        val hasSunmiConfig = printers.any {
            it.transport == PrinterTransport.SUNMI_AIDL && it.drawerKick == DrawerKick.SUNMI_AIDL
        }
        if (hasSunmiConfig) return emptyList()
        // 0 = no drawer attached, 1 = closed, 2 = open, -1 = call failed / service not bound.
        // Logged because "why is my drawer not offered" is otherwise unanswerable from a café.
        val drawerStatus = runCatching { sunmiDriver.getDrawerStatus() }.getOrDefault(-1)
        android.util.Log.i(TAG, "Sunmi getDrawerStatus() = $drawerStatus")
        return if (drawerStatus > 0) {
            listOf(DrawerOptionRow(SUNMI_DRAWER_SYNTHETIC_ID, null, available = true))
        } else {
            emptyList()
        }
    }

    private fun buildDrawerOptions(
        printers: List<PrinterConfig>,
        extras: List<DrawerOptionRow>,
    ): List<DrawerOptionRow> {
        val none = DrawerOptionRow(printerId = null, printerName = null, available = true)
        // Offer every configured printer, because on all supported transports the drawer hangs off
        // the printer — an ESC/POS kick on the RJ11 port, or openDrawer() on the Sunmi AIDL. (D3)
        //
        // This used to filter on `drawerKick != NONE`, which was circular: printers are created
        // with NONE and only this screen ever sets it, so nothing ever qualified and the section
        // showed "None" and nothing else. Capability is a property of the transport, not of a
        // choice the café has not been able to make yet.
        val printerOptions = printers
            .map { DrawerOptionRow(it.id, it.name, available = true) }
        return listOf(none) + printerOptions + extras
    }

    /** How a drawer is kicked on a given transport. (designs.md H9 — Sunmi has no `ESC p`.) */
    private fun PrinterTransport.drawerKickFor(): DrawerKick = when (this) {
        PrinterTransport.SUNMI_AIDL -> DrawerKick.SUNMI_AIDL
        PrinterTransport.BLUETOOTH,
        PrinterTransport.USB,
        PrinterTransport.NETWORK -> DrawerKick.ESC_POS_RJ11
    }

    fun selectPrinterTransport(kind: PrinterDriverKind) {
        localPrefs.selectedPrinterTransport = kind.name
        markSaved()
    }

    /**
     * Choose which printer kicks the cash drawer, or none.
     *
     * Writes **both** the device-local preference and `drawerKick` on the printer row. The pref is
     * only this screen's memory of the choice; the thing that actually opens a drawer is
     * `PrinterConfig.drawerKick`, which `SunmiPrinterDriver.openDrawer` checks before doing
     * anything and which the closing report reads to decide whether to fetch the opening count.
     * Writing only the pref left the setting completely inert — it looked saved and did nothing.
     */
    fun selectDrawer(printerId: String?) {
        localPrefs.selectedDrawerPrinterId = printerId
        viewModelScope.launch {
            try {
                printerConfigDao.getAll().forEach { printer ->
                    // Exactly one printer owns the drawer; every other row is cleared, so a café
                    // switching printers cannot leave two configured to kick the same till.
                    val kick = if (printer.id == printerId) printer.transport.drawerKickFor()
                               else DrawerKick.NONE
                    if (printer.drawerKick != kick) {
                        printerConfigDao.update(printer.copy(drawerKick = kick))
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Could not persist drawer selection", e)
            }
            markSaved()
        }
    }

    fun selectDisplayDriver(kind: DisplayDriverKind) {
        localPrefs.selectedDisplayDriver = if (kind == DisplayDriverKind.NONE) null else kind.name
        markSaved()
    }

    fun clearSavedMessage() {
        _selectionSaved.value = false
    }

    private fun markSaved() {
        _selectionSaved.value = true
    }

    private fun PrinterDriverKind.toTransport(): PrinterTransport = when (this) {
        PrinterDriverKind.BLUETOOTH -> PrinterTransport.BLUETOOTH
        PrinterDriverKind.SUNMI_AIDL -> PrinterTransport.SUNMI_AIDL
        PrinterDriverKind.USB -> PrinterTransport.USB
        PrinterDriverKind.NETWORK -> PrinterTransport.NETWORK
    }

    companion object {
        private const val TAG = "HardwareDevices"

        /** Sentinel id for Sunmi drawer when no PrinterConfig row exists yet. */
        const val SUNMI_DRAWER_SYNTHETIC_ID = "__sunmi_drawer__"
    }
}
