package com.razstudio.opsapp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.opsapp.data.PromoTokenStore
import com.razstudio.opsapp.data.promos.PromoCatalogRepository
import com.razstudio.opsapp.data.promos.PromoProduct
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for the affiliate catalog editor.
 *
 * Ported from `apk/app`'s `com.razstudio.pos.ui.viewmodels.PromoCatalogViewModel`, with
 * `SecureStorage` swapped for the smaller, scoped [PromoTokenStore] (Requirement 7 — this app has
 * no use for `SecureStorage`'s other ~15 keys). The edited list is held here and only written on
 * **Publish**, so a mistyped field never reaches the cafés.
 */
@HiltViewModel
class PromoCatalogViewModel @Inject constructor(
    private val repository: PromoCatalogRepository,
    private val tokenStore: PromoTokenStore,
) : ViewModel() {

    data class State(
        val token: String = "",
        val subId: String = "",
        val products: List<PromoProduct> = emptyList(),
        val pastedLink: String = "",
        val busy: Boolean = false,
        val loaded: Boolean = false,
        val message: String? = null,
        val isError: Boolean = false,
    )

    private val _state = MutableStateFlow(State(token = tokenStore.getPromoToken().orEmpty()))
    val state: StateFlow<State> = _state.asStateFlow()

    private var catalog: PromoCatalogRepository.Catalog? = null

    fun setToken(value: String) { _state.value = _state.value.copy(token = value) }
    fun setSubId(value: String) { _state.value = _state.value.copy(subId = value) }
    fun setPastedLink(value: String) { _state.value = _state.value.copy(pastedLink = value) }

    fun loadIfTokenPresent() {
        if (_state.value.token.isNotBlank() && !_state.value.loaded) load()
    }

    fun saveTokenAndLoad() {
        tokenStore.savePromoToken(_state.value.token.trim())
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null, isError = false)
            repository.load(_state.value.token.trim())
                .onSuccess { loaded ->
                    catalog = loaded
                    _state.value = _state.value.copy(
                        busy = false,
                        loaded = true,
                        subId = loaded.subId,
                        products = loaded.products,
                        message = "Loaded ${loaded.products.size} placement(s) from main.",
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        busy = false,
                        message = e.message ?: "Could not load the catalog.",
                        isError = true,
                    )
                }
        }
    }

    fun resolveAndAdd() {
        val link = _state.value.pastedLink.trim()
        if (link.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null, isError = false)
            repository.resolve(link)
                .onSuccess { product ->
                    _state.value = _state.value.copy(
                        busy = false,
                        products = _state.value.products + product,
                        pastedLink = "",
                        message = if (product.img.isBlank()) {
                            "Added, but Shopee gave no image — it will show the text card."
                        } else {
                            "Added \"${product.alt}\"."
                        },
                        isError = false,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        busy = false,
                        products = _state.value.products + PromoProduct(href = link),
                        pastedLink = "",
                        message = "Added the link, but could not read its page (${e.message}). " +
                            "Fill the label and image by hand.",
                        isError = true,
                    )
                }
        }
    }

    fun addBlank() {
        _state.value = _state.value.copy(products = _state.value.products + PromoProduct(href = ""))
    }

    fun update(index: Int, product: PromoProduct) {
        _state.value = _state.value.copy(
            products = _state.value.products.toMutableList().also { it[index] = product },
        )
    }

    fun remove(index: Int) {
        _state.value = _state.value.copy(
            products = _state.value.products.toMutableList().also { it.removeAt(index) },
        )
    }

    fun move(from: Int, to: Int) {
        val list = _state.value.products
        if (to !in list.indices) return
        _state.value = _state.value.copy(
            products = list.toMutableList().also { it.add(to, it.removeAt(from)) },
        )
    }

    fun publish() {
        val current = catalog ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null, isError = false)
            val cleaned = _state.value.products.filter { it.href.isNotBlank() }
            repository.save(
                token = _state.value.token.trim(),
                catalog = current,
                products = cleaned,
                subId = _state.value.subId.trim(),
            )
                .onSuccess {
                    _state.value = _state.value.copy(
                        busy = false,
                        products = cleaned,
                        message = "Published ${cleaned.size} placement(s). Cafés pick it up within ~5 minutes.",
                        isError = false,
                    )
                    load()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        busy = false,
                        message = e.message ?: "Publish failed.",
                        isError = true,
                    )
                }
        }
    }
}
