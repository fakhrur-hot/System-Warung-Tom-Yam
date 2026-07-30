package com.warungtomyam.pos.data.demo

/**
 * Process-global flag marking whether the app is currently running in Demo Mode.
 *
 * Demo Mode runs the REAL admin / ordering / customer screens against a local, in-memory
 * dummy dataset shared across all three surfaces — nothing touches the network or the live
 * café. The flag lives here (not behind Hilt) so hot paths that must stay demo-aware without
 * taking a dependency on the demo graph — chiefly [com.warungtomyam.pos.data.ApiClient] and the
 * service-start call sites — can read it with a single volatile load.
 *
 * Lifecycle is owned by [DemoController]: [DemoController.enter] flips it on after seeding the
 * shared dataset; [DemoController.exit] flips it off and wipes that dataset when the user confirms
 * returning to the main page. Never mutate [active] directly outside [DemoController].
 */
object DemoSession {
    @Volatile
    var active: Boolean = false
        internal set
}
