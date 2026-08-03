package com.razstudio.pos.display

import android.content.Context
import com.razstudio.pos.printing.DriverAvailability
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The default: this device drives no customer display.
 *
 * A **null object**, not a null. `CustomerDisplayManager` always resolves to some driver, so every
 * call site is an unconditional `display.show(state)` with no null check and no "is a display
 * configured" branch scattered through the order flow. MultiPOS reaches the same conclusion with
 * its `DefaultPrinter()`, and the existing `PrinterDriver` set works the same way. (designs.md D8)
 *
 * Always available — "no display" is a valid configuration on every device, and a café must be able
 * to turn a screen back off.
 */
@Singleton
class NoDisplayDriver @Inject constructor() : CustomerDisplayDriver {

    override val kind = DisplayDriverKind.NONE

    override suspend fun availability(context: Context) = DriverAvailability(available = true)

    override suspend fun show(state: CustomerDisplayState) = Unit

    override suspend fun clear() = Unit
}
