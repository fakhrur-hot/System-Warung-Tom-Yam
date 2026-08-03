package com.razstudio.pos.di

import com.razstudio.pos.printing.BluetoothPrinterDriver
import com.razstudio.pos.printing.PrinterDriver
import com.razstudio.pos.printing.sunmi.SunmiPrinterDriver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt module that registers every [PrinterDriver] implementation into a [Set] so
 * [com.razstudio.pos.printing.PrinterDispatcher] can select the right driver at runtime by
 * matching [com.razstudio.pos.data.local.PrinterTransport].
 *
 * Adding a new driver is one extra `@Binds @IntoSet` binding here — no change to callers.
 * (HW-REQ-1, HW-REQ-2)
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PrinterDriverModule {

    @Binds
    @IntoSet
    abstract fun bindBluetoothDriver(driver: BluetoothPrinterDriver): PrinterDriver

    /**
     * Sunmi internal printer — AIDL-based driver for the D3 Mini built-in thermal printer
     * and cash drawer. Disabled automatically on phones via availability() returning false
     * when the Sunmi service package is absent. (HW-REQ-2, Task 2.1)
     */
    @Binds
    @IntoSet
    abstract fun bindSunmiDriver(driver: SunmiPrinterDriver): PrinterDriver
}
