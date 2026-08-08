package com.razstudio.opsapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.opsapp.data.local.ConnectedCafeDao
import com.razstudio.opsapp.data.local.ConnectedCafeEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Home-screen ViewModel: the Cafés Management list + the two "add a café" entry points. */
@HiltViewModel
class CafesHomeViewModel @Inject constructor(
    private val dao: ConnectedCafeDao,
) : ViewModel() {

    val connectedCafes: StateFlow<List<ConnectedCafeEntity>> =
        dao.listAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Local delete only — Requirement 2.5 */
    fun disconnect(cafeId: String) {
        viewModelScope.launch {
            dao.deleteById(cafeId)
        }
    }
}
