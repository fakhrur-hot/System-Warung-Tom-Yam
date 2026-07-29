package com.warungtomyam.pos.ui.viewmodels

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.warungtomyam.pos.BuildConfig
import com.warungtomyam.pos.data.ApiClient
import com.warungtomyam.pos.data.ApiResult
import com.warungtomyam.pos.data.local.Table
import com.warungtomyam.pos.data.local.TableDao
import com.warungtomyam.pos.ui.i18n.LanguageManager
import com.warungtomyam.pos.ui.i18n.uiStrings
import com.warungtomyam.pos.ui.util.QrPdfGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class QrPdfViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tableDao: TableDao,
    private val apiClient: ApiClient,
    private val languageManager: LanguageManager
) : ViewModel() {

    private fun str() = uiStrings(languageManager.language.value)

    /** All tables from Room (reactive). */
    val tables: StateFlow<List<Table>> = tableDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(QrPdfUiState())
    val uiState: StateFlow<QrPdfUiState> = _uiState.asStateFlow()

    init {
        // Pre-fill café name from the live backend branding (single source of truth),
        // so a rename in Settings shows up on QR cards. The admin can still edit the
        // field before generating. (Previously read a local branding_name.txt that was
        // never written, so the name was always blank.)
        viewModelScope.launch {
            when (val result = apiClient.getBranding()) {
                is ApiResult.Success -> {
                    val name = result.data.cafeName
                    if (name.isNotBlank()) {
                        _uiState.value = _uiState.value.copy(cafeName = name)
                    }
                }
                else -> { /* leave blank; admin can type it */ }
            }
        }
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
     * Update café name input.
     */
    fun updateCafeName(name: String) {
        _uiState.value = _uiState.value.copy(cafeName = name)
    }

    /**
     * Generate the QR PDF for selected tables.
     */
    fun generatePdf() {
        val state = _uiState.value
        if (state.selectedTableIds.isEmpty()) {
            _uiState.value = state.copy(error = str().selectAtLeastOneTable)
            return
        }
        if (state.cafeName.isBlank()) {
            _uiState.value = state.copy(error = str().enterCafeName)
            return
        }

        _uiState.value = state.copy(isGenerating = true, error = null)

        viewModelScope.launch {
            // Opaque per-table QR tokens (so the QR URL isn't a guessable "?table=T0006").
            // If the fetch fails we fall back to raw ids inside the generator.
            val tokenMap = (apiClient.getTableTokens() as? ApiResult.Success)?.data ?: emptyMap()

            val result = withContext(Dispatchers.IO) {
                val selectedTables = tables.value.filter { it.id in state.selectedTableIds }

                // Load logo bitmap from internal storage (the full JPEG, not the mono)
                val logoBitmap = loadLogoBitmap()

                val baseUrl = BuildConfig.WEBSITE_URL

                QrPdfGenerator.generatePdf(
                    context = context,
                    tables = selectedTables,
                    cafeName = state.cafeName,
                    logoBitmap = logoBitmap,
                    baseUrl = baseUrl,
                    tokenMap = tokenMap
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
     * Load the full JPEG logo from internal storage.
     */
    private fun loadLogoBitmap(): android.graphics.Bitmap? {
        return try {
            val logoFile = File(context.filesDir, "cafe_logo.jpg")
            if (logoFile.exists()) {
                BitmapFactory.decodeFile(logoFile.absolutePath)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * UI state for the QR PDF generation screen.
 */
data class QrPdfUiState(
    val selectedTableIds: Set<String> = emptySet(),
    val cafeName: String = "",
    val isGenerating: Boolean = false,
    val generatedUri: Uri? = null,
    val error: String? = null,
    val successMessage: String? = null
)
