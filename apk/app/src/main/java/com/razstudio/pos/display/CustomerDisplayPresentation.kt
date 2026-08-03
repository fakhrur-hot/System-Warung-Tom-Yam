package com.razstudio.pos.display

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.razstudio.pos.ui.i18n.AppLanguage
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.theme.WarungTomYamTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * Hosts the customer-facing Compose UI on a secondary [Display].
 *
 * **Compose cannot run in a bare `Presentation`.** A `ComposeView` resolves its lifecycle,
 * saved-state and ViewModel owners from the view tree, and a `Presentation` is a `Dialog` — it
 * supplies none of them, so composition crashes with "ViewTreeLifecycleOwner not found". This class
 * therefore *is* all three owners and installs itself on the view tree before `setContent`. (H3.4)
 *
 * The lifecycle is driven by hand from the dialog callbacks. Getting this wrong is not a crash but
 * something worse: a `LifecycleRegistry` left below STARTED silently never runs effects, so the
 * screen would render once and then freeze while the till carried on — the kind of fault a café
 * only notices when a customer says the total is wrong.
 */
class CustomerDisplayPresentation(
    outerContext: Context,
    display: Display,
    private val state: StateFlow<CustomerDisplayState>,
    private val language: StateFlow<AppLanguage>,
) : Presentation(outerContext, display), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore = ViewModelStore()

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must precede super.onCreate — the controller has to be restored before anything can
        // consume the registry.
        savedStateController.performRestore(null)
        super.onCreate(savedInstanceState)

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@CustomerDisplayPresentation)
            setViewTreeViewModelStoreOwner(this@CustomerDisplayPresentation)
            setViewTreeSavedStateRegistryOwner(this@CustomerDisplayPresentation)
            setContent {
                WarungTomYamTheme {
                    val current by state.collectAsState()
                    val lang by language.collectAsState()
                    // The customer screen follows the café's language like every other surface —
                    // a Tamil-configured café must not show an English "Thank you".
                    CustomerDisplayContent(current, uiStrings(lang))
                }
            }
        }
        setContentView(composeView)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStop() {
        // DESTROYED, not CREATED: this presentation is not reused. The driver builds a fresh one
        // per show, because the display it targets is virtual and may be a different Display
        // object after its owning app restarts. (H6)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onStop()
    }
}
