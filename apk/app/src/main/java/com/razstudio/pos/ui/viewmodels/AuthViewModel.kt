package com.razstudio.pos.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.razstudio.pos.data.AuthEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * App-scoped ViewModel that exposes the [AuthEventBus] stream to the UI layer.
 * Observing at the NavGraph level means any screen deep in the back-stack will
 * trigger re-authentication when the admin session token expires.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    val authEventBus: AuthEventBus
) : ViewModel()
