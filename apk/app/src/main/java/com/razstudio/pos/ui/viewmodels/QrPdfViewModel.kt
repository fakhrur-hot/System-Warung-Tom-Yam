package com.razstudio.pos.ui.viewmodels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.razstudio.pos.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.BuildConfig
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.local.Table
import com.razstudio.pos.data.local.TableDao
import com.razstudio.pos.data.local.isTakeout
import com.razstudio.pos.ui.util.LogoPipeline
import com.razstudio.pos.ui.i18n.LanguageManager
import com.razstudio.pos.ui.i18n.uiStrings
import com.razstudio.pos.ui.util.QrPdfGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class QrPdfViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tableDao: TableDao,
    private val apiClient: BackendGateway,
    private val languageManager: LanguageManager,
    private val appConfig: com.razstudio.pos.data.AppConfigStore
) : ViewModel() {

    private fun str() = uiStrings(languageManager.language.value)

    /** Dine-in tables from Room (reactive). Take-out (Tapaw) slots are excluded — they have no QR card. */
    val tables: StateFlow<List<Table>> = tableDao.getAllFlow()
        .map { list -> list.filter { !it.isTakeout } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(QrPdfUiState())
    val uiState: StateFlow<QrPdfUiState> = _uiState.asStateFlow()

    init {
        // Header logo defaults to the bundled built-in logo (res/raw/qr_default_logo).
        _uiState.value = _uiState.value.copy(logoPreview = loadDefaultLogo())
        // Café name comes from the live backend branding (single source of truth); it's the
        // header in "text only" mode and the PDF file name. Not editable here — it's set in
        // Settings → Branding.
        viewModelScope.launch {
            when (val result = apiClient.getBranding()) {
                is ApiResult.Success -> {
                    val name = result.data.cafeName
                    if (name.isNotBlank()) {
                        _uiState.value = _uiState.value.copy(cafeName = name)
                    }
                }
                else -> { /* leave blank; text mode falls back to a generic file name */ }
            }
        }
    }

    /** Switch the card header between the café-name text and a logo image. */
    fun setHeaderMode(mode: QrHeaderMode) {
        _uiState.value = _uiState.value.copy(headerMode = mode)
    }

    /** Pick a logo image (jpg/png); decoded and downscaled so its longest side is ≤ 1024 px. */
    fun pickLogo(uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeAndResize(uri, MAX_LOGO_PX) }
            if (bmp != null) {
                _uiState.value = _uiState.value.copy(logoPreview = bmp, headerMode = QrHeaderMode.LOGO)
            } else {
                _uiState.value = _uiState.value.copy(error = str().failedToGeneratePdf)
            }
        }
    }

    /** Reset the header logo back to the bundled built-in logo. */
    fun resetLogo() {
        _uiState.value = _uiState.value.copy(logoPreview = loadDefaultLogo())
    }

    /**
     * Toggle selection state of a table.
     */
    fun toggleTableSelection(tableId: String) {
        val current = _uiState.value.selectedTableIds.toMutableSet()
        if (current.contains(tableId)) {
            current.remove(tableId)
        } else {
            current.add(tableId)
        }
        _uiState.value = _uiState.value.copy(selectedTableIds = current)
    }

    /**
     * Select all tables.
     */
    fun selectAll() {
        val allIds = tables.value.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(selectedTableIds = allIds)
    }

    /**
     * Deselect all tables.
     */
    fun deselectAll() {
        _uiState.value = _uiState.value.copy(selectedTableIds = emptySet())
    }

    /**
     * Generate the QR PDF for selected tables. The card header is either the café-name text
     * (TEXT mode) or the chosen logo image (LOGO mode); a fixed footer is always printed by
     * the generator. The café name is still used for the PDF file name in both modes.
     */
    fun generatePdf() {
        val state = _uiState.value
        if (state.selectedTableIds.isEmpty()) {
            _uiState.value = state.copy(error = str().selectAtLeastOneTable)
            return
        }
        if (state.headerMode == QrHeaderMode.TEXT && state.cafeName.isBlank()) {
            _uiState.value = state.copy(error = str().enterCafeName)
            return
        }
        if (state.headerMode == QrHeaderMode.LOGO && state.logoPreview == null) {
            _uiState.value = state.copy(error = str().failedToGeneratePdf)
            return
        }

        _uiState.value = state.copy(isGenerating = true, error = null)

        viewModelScope.launch {
            // Opaque per-table QR tokens (so the QR URL isn't a guessable "?table=T0006").
            // If the fetch fails we fall back to raw ids inside the generator.
            val tokenMap = (apiClient.getTableTokens() as? ApiResult.Success)?.data ?: emptyMap()

            val result = withContext(Dispatchers.IO) {
                val selectedTables = tables.value.filter { it.id in state.selectedTableIds }
                val baseUrl = appConfig.websiteUrl().ifBlank { BuildConfig.WEBSITE_URL }

                QrPdfGenerator.generatePdf(
                    context = context,
                    tables = selectedTables,
                    // TEXT mode → header text, no logo. LOGO mode → logo image, blank header text.
                    cafeName = if (state.headerMode == QrHeaderMode.TEXT) state.cafeName else "",
                    logoBitmap = if (state.headerMode == QrHeaderMode.LOGO) state.logoPreview else null,
                    baseUrl = baseUrl,
                    tokenMap = tokenMap,
                    fileNameBase = state.cafeName.ifBlank { "QR" }
                )
            }

            if (result.uri != null) {
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    generatedUri = result.uri,
                    successMessage = str().pdfGenerated.format(result.tableCount, result.pageCount)
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    error = str().failedToGeneratePdf
                )
            }
        }
    }

    /**
     * Create a share intent for the generated PDF.
     */
    fun getShareIntent(): android.content.Intent? {
        val uri = _uiState.value.generatedUri ?: return null
        return QrPdfGenerator.createShareIntent(context, uri)
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Clear success message.
     */
    fun clearSuccess() {
        _uiState.value = _uiState.value.copy(successMessage = null, generatedUri = null)
    }

    /**
     * The café's own uploaded logo (Settings → Café Profile → Change Logo) if one exists, else the
     * bundled built-in default (res/raw/qr_default_logo).
     */
    private fun loadDefaultLogo(): Bitmap? = LogoPipeline.loadJpegFromInternal(context) ?: try {
        BitmapFactory.decodeResource(context.resources, R.raw.qr_default_logo)
    } catch (e: Exception) {
        null
    }

    /** Decode an image at [uri] and downscale so its longest side is ≤ [maxPx], preserving aspect. */
    private fun decodeAndResize(uri: Uri, maxPx: Int): Bitmap? {
        return try {
            // First pass: bounds only, to compute a memory-safe inSampleSize.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxPx * 2 || bounds.outHeight / sample > maxPx * 2) {
                sample *= 2
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            // Exact fit: scale so the longest side is maxPx (never upscale).
            val scale = minOf(maxPx.toFloat() / decoded.width, maxPx.toFloat() / decoded.height, 1f)
            if (scale >= 1f) return decoded
            val out = Bitmap.createScaledBitmap(
                decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true
            )
            if (out !== decoded) decoded.recycle()
            out
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_LOGO_PX = 1024
    }
}

/** How the QR card header is rendered: the café-name text, or a logo image. */
enum class QrHeaderMode { TEXT, LOGO }

/**
 * UI state for the QR PDF generation screen.
 */
data class QrPdfUiState(
    val selectedTableIds: Set<String> = emptySet(),
    val cafeName: String = "",
    // LOGO by default (built-in Tani logo); TEXT uses the café branding name.
    val headerMode: QrHeaderMode = QrHeaderMode.LOGO,
    val logoPreview: Bitmap? = null,
    val isGenerating: Boolean = false,
    val generatedUri: Uri? = null,
    val error: String? = null,
    val successMessage: String? = null
)
