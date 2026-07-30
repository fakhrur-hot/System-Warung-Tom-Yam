package com.warungtomyam.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warungtomyam.pos.data.demo.DemoBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives Demo Mode entry/exit from the navigation layer.
 *
 * Demo Mode runs the REAL admin / ordering / customer screens against one shared, local dummy
 * dataset ([DemoBackend]). This view model is hoisted at the NavHost scope so a single global
 * "DEMO" banner + exit-confirm dialog can cover every screen, and so "Try Demo" can seed the
 * dataset before routing into the real admin home.
 */
@HiltViewModel
class DemoModeViewModel @Inject constructor(
    private val demoBackend: DemoBackend
) : ViewModel() {

    /** True while the app is in Demo Mode; observed to show the global demo banner. */
    val active: StateFlow<Boolean> = demoBackend.activeFlow

    /** Seed the shared demo dataset, then invoke [onReady] to navigate into the real screens. */
    fun enter(onReady: () -> Unit) {
        viewModelScope.launch {
            demoBackend.enter()
            onReady()
        }
    }

    /** Destroy the shared demo dataset, then invoke [onDone] to return to the main page. */
    fun exit(onDone: () -> Unit) {
        viewModelScope.launch {
            demoBackend.exit()
            onDone()
        }
    }
}
