package com.razstudio.pos.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.local.PaperWidth
import com.razstudio.pos.data.local.PrinterConfig
import com.razstudio.pos.data.local.PrinterConfigDao
import com.razstudio.pos.data.local.PrinterRole
import com.razstudio.pos.data.local.PrinterTransport
import com.razstudio.pos.data.local.SunmiInnerPrinter
import com.razstudio.pos.data.local.DrawerKick
import com.razstudio.pos.printing.PrinterConnectionManager
import com.razstudio.pos.printing.PrinterDispatcher
import com.razstudio.pos.ui.i18n.LanguageManager
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.util.BluetoothHelper
import com.razstudio.pos.ui.util.DiscoveredDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class PrintersViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val printerConfigDao: PrinterConfigDao,
    private val printerDispatcher: PrinterDispatcher,
    private val connectionManager: PrinterConnectionManager,
    private val languageManager: LanguageManager,
    private val printSettingsStore: com.razstudio.pos.data.local.PrintSettingsStore,
    private val sunmiDriver: com.razstudio.pos.printing.sunmi.SunmiPrinterDriver
) : ViewModel() {

    private fun str() = uiStrings(languageManager.language.value)

    private val bluetoothHelper = BluetoothHelper(appContext)

    /** All configured printers (reactive). */
    val printers: StateFlow<List<PrinterConfig>> = printerConfigDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Discovered BT devices during scanning. */
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = bluetoothHelper.discoveredDevices

    /** Whether a BT scan is in progress. */
    val isScanning: StateFlow<Boolean> = bluetoothHelper.isScanning

    private val _uiState = MutableStateFlow(
        PrintersUiState(
            keepAliveMode = connectionManager.getMode(),
            kitchenFontSize = printSettingsStore.getKitchenFontSize(),
            receiptLogo = printSettingsStore.getReceiptLogo(),
            escAsteriskMode = printSettingsStore.getEscAsteriskImageMode(),
            receiptAutoCut = printSettingsStore.getReceiptAutoCut(),
        )
    )
    val uiState: StateFlow<PrintersUiState> = _uiState.asStateFlow()

    /** Switch the Bluetooth connection strategy (fast persistent vs eco). */
    fun setKeepAliveMode(mode: String) {
        connectionManager.setMode(mode)
        _uiState.value = _uiState.value.copy(keepAliveMode = mode)
    }

    /** Kitchen-slip menu font size (device-local) — persist + apply immediately. */
    fun updateKitchenFontSize(size: String) {
        printSettingsStore.setKitchenFontSize(size)
        _uiState.value = _uiState.value.copy(kitchenFontSize = size)
    }

    /** Logo on receipt toggle (device-local). */
    fun updateReceiptLogo(enabled: Boolean) {
        printSettingsStore.setReceiptLogo(enabled)
        _uiState.value = _uiState.value.copy(receiptLogo = enabled)
    }

    /** ESC * image mode compatibility toggle (device-local). */
    fun updateEscAsteriskMode(enabled: Boolean) {
        printSettingsStore.setEscAsteriskImageMode(enabled)
        _uiState.value = _uiState.value.copy(escAsteriskMode = enabled)
    }

    /** Receipt paper auto-cut toggle (device-local). */
    fun setReceiptAutoCut(enabled: Boolean) {
        printSettingsStore.setReceiptAutoCut(enabled)
        _uiState.value = _uiState.value.copy(receiptAutoCut = enabled)
    }

    // ── Printer transport (shared with HardwareDevicesViewModel) ─────────────────────────
    // The transport section on PrintersScreen uses HardwareDevicesViewModel directly via
    // hiltViewModel() in the composable rather than duplicating availability probes here.

    // ── Two-bucket kitchen print router (FOOD / BEVERAGE) ────────────────────
    // Each bucket routes to one printer, stored in that printer's categoryFilter (the same
    // bucket name the kitchen slip is dispatched with). The same printer may hold both.

    /** The id of the printer currently assigned to [bucket] ("FOOD"/"BEVERAGE"), or null. */
    fun printerIdForBucket(bucket: String): String? =
        printers.value.firstOrNull { p ->
            (p.categoryFilter ?: "").split(",").map { it.trim() }.any { it.equals(bucket, ignoreCase = true) }
        }?.id

    /** Route [bucket]'s slips to [printerId] (null clears the assignment). */
    fun setBucketPrinter(bucket: String, printerId: String?) {
        viewModelScope.launch {
            for (p in printerConfigDao.getAll()) {
                val buckets = (p.categoryFilter ?: "").split(",")
                    .map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
                buckets.removeAll { it.equals(bucket, ignoreCase = true) }
                if (p.id == printerId) buckets.add(bucket.uppercase())
                val newFilter = buckets.distinct().takeIf { it.isNotEmpty() }?.joinToString(",")
                if (newFilter != p.categoryFilter) {
                    printerConfigDao.update(p.copy(categoryFilter = newFilter))
                }
            }
        }
    }

    /**
     * Get the list of Bluetooth permissions required for this Android version.
     */
    fun getRequiredPermissions(): List<String> = bluetoothHelper.getRequiredPermissions()

    /**
     * Check if all required BT permissions are granted.
     */
    fun hasBluetoothPermissions(): Boolean = bluetoothHelper.hasPermissions()

    /**
     * Check if Bluetooth is enabled on the device.
     */
    fun isBluetoothEnabled(): Boolean = bluetoothHelper.isBluetoothEnabled()

    /**
     * Start scanning for Bluetooth devices (paired + discovery).
     */
    fun startBluetoothScan() {
        if (!bluetoothHelper.hasPermissions()) {
            _uiState.value = _uiState.value.copy(
                error = str().bluetoothPermissionsRequired
            )
            return
        }
        if (!bluetoothHelper.isBluetoothEnabled()) {
            _uiState.value = _uiState.value.copy(
                error = str().enableBluetoothMsg
            )
            return
        }
        bluetoothHelper.startDiscovery()
    }

    /**
     * Stop the Bluetooth scan.
     */
    fun stopBluetoothScan() {
        bluetoothHelper.stopDiscovery()
    }

    /**
     * Add a new printer from a discovered device.
     */
    /**
     * True when this terminal has a built-in printer and no row for it yet.
     *
     * A second, explicit way in. The built-in printer *does* also appear in the Bluetooth scan as
     * `InnerPrinter` — Sunmi bonds it there — and picking it from that list works, because
     * [addPrinter] recognises it and stores it on the AIDL transport. This button exists for the
     * case where the scan is not an option at all: `BLUETOOTH_CONNECT` is commonly denied on these
     * terminals, and a denied permission would otherwise leave the built-in printer unreachable.
     */
    val canAddBuiltInPrinter: StateFlow<Boolean> = printers
        .map { rows ->
            rows.none { it.transport == PrinterTransport.SUNMI_AIDL } &&
                sunmiDriver.availability(appContext).available
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Add this terminal's built-in printer. No address, because an AIDL printer has none.
     *
     * [paperWidth] is the operator's choice and is used as-is. `SunmiPrinterDriver` does ask
     * `getPrinterPaper()` after binding, but on the D3 Mini that returns 0 — neither 58 nor 80 —
     * so detection cannot be relied on to correct a wrong answer here.
     */
    fun addBuiltInPrinter(
        name: String,
        printerRole: PrinterRole,
        paperWidth: PaperWidth = PaperWidth.EIGHTY_MM,
    ) {
        viewModelScope.launch {
            printerConfigDao.insert(
                PrinterConfig(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    address = null,
                    transport = PrinterTransport.SUNMI_AIDL,
                    // The drawer is chosen separately in Devices & Hardware, same as for any other
                    // printer — adding a printer must not silently arm a till.
                    drawerKick = DrawerKick.NONE,
                    paperWidth = paperWidth,
                    printerRole = printerRole,
                    isActive = true
                )
            )
            _uiState.value = _uiState.value.copy(showAddDialog = false, selectedDevice = null)
        }
    }

    fun addPrinter(
        name: String,
        macAddress: String,
        paperWidth: PaperWidth,
        printerRole: PrinterRole
    ) {
        viewModelScope.launch {
            // The Sunmi built-in printer appears in this list as `InnerPrinter` on a placeholder
            // MAC, and it is added exactly like any other Bluetooth printer.
            //
            // It was briefly rerouted to the AIDL transport here, on the reasoning that the AIDL
            // is the only documented route to the cash drawer. In practice the AIDL path printed
            // but would not cut, and rendered every receipt as a bitmap rather than in the
            // printer's native font. The RFCOMM path does all three correctly on this hardware,
            // so it is the one used. See designs.md H11.
            val config = PrinterConfig(
                id = UUID.randomUUID().toString(),
                name = name,
                address = macAddress,
                transport = PrinterTransport.BLUETOOTH,
                drawerKick = DrawerKick.NONE,
                // Whatever the operator chose — including for the built-in printer.
                //
                // This briefly forced EIGHTY_MM for the built-in on the theory that
                // `getPrinterPaper()` would correct it after binding. On the D3 Mini that call
                // returns 0 — neither 58 nor 80 — so nothing corrected anything, and the override
                // silently moved a café from 32 to 48 characters per line. Same head, 50% more
                // characters, so every glyph came out two-thirds the size it used to be.
                //
                // With detection unavailable, the person holding the receipt is the better signal.
                paperWidth = paperWidth,
                printerRole = printerRole,
                isActive = true
            )
            printerConfigDao.insert(config)
            _uiState.value = _uiState.value.copy(
                showAddDialog = false,
                selectedDevice = null
            )
        }
    }

    /**
     * Update an existing printer configuration.
     */
    fun updatePrinter(
        id: String,
        name: String,
        paperWidth: PaperWidth,
        printerRole: PrinterRole
    ) {
        viewModelScope.launch {
            val existing = printerConfigDao.getById(id) ?: return@launch
            val updated = existing.copy(
                name = name,
                paperWidth = paperWidth,
                printerRole = printerRole
            )
            printerConfigDao.update(updated)
            _uiState.value = _uiState.value.copy(
                showEditDialog = false,
                editingPrinter = null
            )
        }
    }

    /**
     * Remove a printer configuration.
     */
    fun removePrinter(id: String) {
        viewModelScope.launch {
            printerConfigDao.deleteById(id)
            _uiState.value = _uiState.value.copy(
                showDeleteConfirm = false,
                deletingPrinterId = null
            )
        }
    }

    /**
     * Toggle the active state of a printer.
     */
    fun toggleActive(id: String) {
        viewModelScope.launch {
            val existing = printerConfigDao.getById(id) ?: return@launch
            printerConfigDao.update(existing.copy(isActive = !existing.isActive))
        }
    }

    /**
     * Send a test print to verify connectivity.
     */
    fun testPrint(printerId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(testingPrinterId = printerId)
            val result = printerDispatcher.testPrint(printerId)
            _uiState.value = if (result != null) {
                _uiState.value.copy(
                    testingPrinterId = null,
                    successMessage = str().testPrintSent.format(result.charWidth)
                )
            } else {
                _uiState.value.copy(
                    testingPrinterId = null,
                    error = str().testPrintFailed
                )
            }
        }
    }

    // --- Dialog state management ---

    fun showAddDialog(device: DiscoveredDevice) {
        _uiState.value = _uiState.value.copy(
            showAddDialog = true,
            selectedDevice = device
        )
    }

    fun dismissAddDialog() {
        _uiState.value = _uiState.value.copy(
            showAddDialog = false,
            selectedDevice = null
        )
    }

    fun showEditDialog(printer: PrinterConfig) {
        _uiState.value = _uiState.value.copy(
            showEditDialog = true,
            editingPrinter = printer
        )
    }

    fun dismissEditDialog() {
        _uiState.value = _uiState.value.copy(
            showEditDialog = false,
            editingPrinter = null
        )
    }

    fun showDeleteConfirm(printerId: String) {
        _uiState.value = _uiState.value.copy(
            showDeleteConfirm = true,
            deletingPrinterId = printerId
        )
    }

    fun dismissDeleteConfirm() {
        _uiState.value = _uiState.value.copy(
            showDeleteConfirm = false,
            deletingPrinterId = null
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearSuccess() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        bluetoothHelper.stopDiscovery()
    }
}

/**
 * UI state for the Printers management screen.
 */
data class PrintersUiState(
    val showAddDialog: Boolean = false,
    val selectedDevice: DiscoveredDevice? = null,
    val showEditDialog: Boolean = false,
    val editingPrinter: PrinterConfig? = null,
    val showDeleteConfirm: Boolean = false,
    val deletingPrinterId: String? = null,
    val testingPrinterId: String? = null,
    // Bluetooth connection strategy: "fast" (persistent + 15s keep-alive) or "eco".
    val keepAliveMode: String = PrinterConnectionManager.MODE_FAST,
    // Kitchen-slip menu-text size: "S"/"M"/"L" (see KitchenFontSize).
    val kitchenFontSize: String = "M",
    // Receipt-image settings (moved here from AdminSettingsScreen, Printing & Hardware)
    val receiptLogo: Boolean = false,
    val escAsteriskMode: Boolean = true,
    // Receipt paper auto-cut (moved from Devices & Hardware)
    val receiptAutoCut: Boolean = true,
    val error: String? = null,
    val successMessage: String? = null
)
