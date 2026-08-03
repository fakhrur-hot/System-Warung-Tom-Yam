package com.razstudio.pos.di

import com.razstudio.pos.display.CustomerDisplayDriver
import com.razstudio.pos.display.NoDisplayDriver
import com.razstudio.pos.display.PresentationDisplayDriver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Registers every [CustomerDisplayDriver] into a [Set] so
 * [com.razstudio.pos.display.CustomerDisplayManager] can pick one at runtime from the café's
 * device-local choice.
 *
 * Compiled-in bindings, exactly as for printers — no reflection and no dynamic loading. MultiPOS
 * names its drivers by class string in JSON config and loads them reflectively, which is more
 * flexible but breaks under R8: a class with no static reference is stripped or renamed, so the
 * driver works in `assembleDebug` and vanishes in `assembleRelease`. (designs.md D8)
 *
 * Adding a driver is one `@Binds @IntoSet` line and no change to callers. (HW-REQ-1, HW-REQ-4)
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CustomerDisplayModule {

    /**
     * The null-object default. Must always be bound — [CustomerDisplayManager] falls back to it
     * when a café's stored selection names a driver this build does not contain.
     */
    @Binds
    @IntoSet
    abstract fun bindNoDisplay(driver: NoDisplayDriver): CustomerDisplayDriver

    /** Android second screen via the `Presentation` API — the D3 Mini's 800 × 480 customer screen. */
    @Binds
    @IntoSet
    abstract fun bindPresentationDisplay(driver: PresentationDisplayDriver): CustomerDisplayDriver
}
