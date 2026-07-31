package com.razstudio.pos.di

import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.OperatingMode
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.data.local.LocalBackend
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Chooses the [BackendGateway] implementation for this device (task 3.3, Requirement 4.2).
 *
 * | Mode    | Device         | Implementation                                              |
 * |---------|----------------|-------------------------------------------------------------|
 * | `CLOUD` | any            | [ApiClient] — HTTPS to Supabase, exactly as before          |
 * | `LAN`   | Server (admin) | [LocalBackend] — in-process, Room-backed                    |
 * | `LAN`   | Client (staff) | [ApiClient], base URL pointed at `http://<server>:<port>`   |
 * | `KIOSK` | admin          | [LocalBackend]                                              |
 *
 * The LAN split is by **role**, and that asymmetry is the point: a Client reuses the HTTP
 * implementation verbatim against a different host, so the whole ordering-staff feature set works in
 * LAN Mode with no client-side changes. Only the Server device swaps its backend for a local one.
 */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {

    /**
     * Note this resolves **once**, when the singleton is first requested — a mode change therefore
     * takes effect on the next app start, not immediately. That is not a limitation to work around:
     * Requirement 10.1 already makes a mode change a deliberate, confirmed operation, and the Setup
     * flow prompts for a restart afterwards (the same restart-required dialog the café-rename feature
     * uses). Re-resolving live would mean tearing down the OkHttp client, the realtime services and
     * every ViewModel's captured reference mid-session, for no benefit.
     *
     * [LocalBackend] is injected as a [Lazy] so Cloud Mode never constructs it. Today it is a stub
     * whose every method throws; without `Lazy` that stub would still be instantiated on every
     * existing install, which is harmless but pointless — and once it owns Room handles and an
     * embedded HTTP server it would stop being harmless.
     */
    @Provides
    @Singleton
    fun provideBackendGateway(
        modeRepository: ModeRepository,
        remote: ApiClient,
        local: Lazy<LocalBackend>,
        secureStorage: SecureStorage,
    ): BackendGateway = when (modeRepository.currentMode()) {
        OperatingMode.CLOUD -> remote

        // Only the admin device is the LAN Server. Ordering staff are Clients and keep speaking HTTP,
        // just to a phone on the local network instead of Supabase.
        OperatingMode.LAN ->
            if (secureStorage.getRole() == SecureStorage.Role.ADMIN) local.get() else remote

        OperatingMode.KIOSK -> local.get()
    }
}
