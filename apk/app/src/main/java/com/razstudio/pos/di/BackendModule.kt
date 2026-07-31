package com.razstudio.pos.di

import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.BackendGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [BackendGateway] to its implementation (task 3.1, Requirement 4.2).
 *
 * Today there is exactly one implementation, [ApiClient], so this is a plain `@Binds`. It is a
 * deliberate no-behaviour-change step: nothing injects `BackendGateway` yet — every existing call site
 * still asks for `ApiClient` directly — so Cloud Mode is bit-for-bit unaffected. What this buys is the
 * ability to introduce a second implementation without touching consumers.
 *
 * **This binding becomes mode-aware in task 3.3**, at which point it selects between:
 *
 * - `CLOUD`, and a LAN **Client** device → [ApiClient] (HTTP; the LAN client differs only in base URL)
 * - `LAN` **Server** device, and `KIOSK`  → `LocalBackend` (in-process, Room-backed; task 4)
 *
 * At that point `@Binds` is no longer enough — selecting an implementation at runtime needs a
 * `@Provides` that reads `ModeRepository.currentMode()`. Left as `@Binds` until then rather than
 * pre-building the switch, because a `@Provides` returning one of two implementations while the second
 * does not exist yet would be dead code that reads as if the feature were finished.
 *
 * `@Singleton` matches [ApiClient]'s own scope, so injecting the interface and injecting the class
 * yield the same instance — important while both styles coexist during the migration, since
 * [ApiClient] holds a shared OkHttp client and 401-handling state that must not be duplicated.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackendModule {

    @Binds
    @Singleton
    abstract fun bindBackendGateway(impl: ApiClient): BackendGateway
}
