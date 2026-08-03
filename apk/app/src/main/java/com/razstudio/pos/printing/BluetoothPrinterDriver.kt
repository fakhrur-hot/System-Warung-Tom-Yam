package com.razstudio.pos.printing

import android.content.Context
import com.razstudio.pos.data.local.DrawerKick
import com.razstudio.pos.data.local.PrinterConfig
import com.razstudio.pos.data.local.PrinterTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bluetooth thermal printer driver.  Wraps [PrinterConnectionManager], preserving its warm
 * socket, keep-alive heartbeat, coroutine mutex and isPrinterHost() guard unchanged.
 *
 * The constructor receives [context] so [availability] can interrogate the BluetoothManager
 * without needing an Activity reference. [connectionManager] keeps every byte of the existing
 * Bluetooth connection logic; this class is purely a delegation shim. (HW-REQ-1, HW-REQ-2)
 */
@Singleton
class BluetoothPrinterDriver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: PrinterConnectionManager
) : PrinterDriver {

    override val transport: PrinterTransport = PrinterTransport.BLUETOOTH

    override suspend fun availability(context: Context): DriverAvailability {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter
        return when {
            adapter == null -> DriverAvailability(false, "Bluetooth is not available on this device")
            !adapter.isEnabled -> DriverAvailability(false, "Bluetooth is turned off")
            else -> DriverAvailability(true)
        }
    }

    override suspend fun print(config: PrinterConfig, markup: String) {
        val address = config.address
            ?: throw IllegalArgumentException(
                "Bluetooth printer '${config.name}' has no MAC address"
            )
        connectionManager.print(
            macAddress = address,
            printerName = config.name,
            paperWidth = config.paperWidth,
            payload = markup
        )
    }

    /**
     * Kick the cash drawer with an ESC/POS pulse on the printer's RJ11/RJ12 port.
     *
     * `ESC p m t1 t2` — connector 0, 25 ms on, 250 ms off. The standard sequence; the timings are
     * the widely-used defaults and are comfortably within what a solenoid needs.
     *
     * This was previously inherited as a no-op, so a drawer attached to a Bluetooth printer could
     * never open — including the Sunmi built-in printer once it is driven over this transport.
     *
     * designs.md H9 records that Sunmi's *documented* command set has no `ESC p`, and that is worth
     * keeping in mind — but this same printer honours a raw `GS V` cut sent by DantSu, so the
     * firmware clearly accepts standard ESC/POS beyond what the docs list. Whether it honours the
     * drawer pulse is the open question, and this is the only way to find out.
     */
    override suspend fun openDrawer(config: PrinterConfig) {
        if (config.drawerKick != DrawerKick.ESC_POS_RJ11) return
        val address = config.address ?: return
        connectionManager.sendRaw(address, config.name, ESC_POS_DRAWER_KICK)
    }

    override suspend fun disconnect(config: PrinterConfig) {
        val address = config.address ?: return
        connectionManager.disconnect(address)
    }

    override suspend fun disconnectAll() {
        connectionManager.disconnectAll()
    }

    private companion object {
        /** `ESC p 0 25 250` — pulse connector 0 for 25 ms, then 250 ms off. */
        val ESC_POS_DRAWER_KICK = byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA.toByte())
    }
}
