package com.razstudio.pos.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.razstudio.pos.data.local.BackupPreview
import com.razstudio.pos.data.local.DatabaseBackupManager
import com.razstudio.pos.ui.i18n.LanguageManager
import com.razstudio.pos.ui.i18n.uiStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: DatabaseBackupManager,
    private val languageManager: LanguageManager,
    private val orderDao: com.razstudio.pos.data.local.OrderDao,
    private val menuDao: com.razstudio.pos.data.local.MenuDao,
    private val modeRepository: com.razstudio.pos.data.ModeRepository,
) : ViewModel() {

    private fun str() = uiStrings(languageManager.language.value)

    data class UiState(
        val isLoading: Boolean = false,
        val exportUri: Uri? = null,
        val importPreview: BackupPreview? = null,
        val error: String? = null,
        val successMessage: String? = null,
        val showConfirmDialog: Boolean = false,

        /**
         * What restoring will **destroy** (task 13.2, Requirement 8.3).
         *
         * The confirm dialog already listed what the backup contains; it never said what the device
         * is about to lose. `applyImport` deletes orders, order items, menu, tables, printers and
         * settings before importing anything, and in LAN or Kiosk Mode that is the café's only copy
         * — there is no server holding a second one. Naming the counts is the difference between an
         * operator confirming a restore and an operator confirming a number they have not seen.
         */
        val currentOrderCount: Int = 0,
        val currentMenuItemCount: Int = 0,

        /** True off-cloud, where Room is authoritative and a wipe is unrecoverable. */
        val localDataIsOnlyCopy: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Holds the generated JSON for writing to SAF output stream
    private var pendingExportJson: String? = null

    // Holds the raw JSON read from import file for applying after user confirms
    private var pendingImportJson: String? = null

    /**
     * Generates the backup JSON and stores it pending the SAF file write.
     * Called before launching the CreateDocument intent.
     */
    fun prepareExport() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, exportUri = null, successMessage = null) }
            try {
                pendingExportJson = backupManager.exportToJson()
                // The UI will launch the SAF CreateDocument picker after this
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = str().exportFailed.format(e.message)) }
            }
        }
    }

    /**
     * Writes the previously generated JSON to the SAF-provided URI.
     */
    fun writeExportToUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val json = pendingExportJson
                    ?: throw IllegalStateException("No export data prepared")
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Cannot open output stream")
                pendingExportJson = null
                _uiState.update {
                    it.copy(isLoading = false, exportUri = uri, successMessage = str().databaseExported)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = str().exportFailed.format(e.message)) }
            }
        }
    }

    /**
     * Reads a JSON backup file from the given URI and presents a preview.
     */
    fun parseImportFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, importPreview = null) }
            try {
                val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                } ?: throw IllegalStateException("Cannot read file")
                pendingImportJson = json
                val preview = backupManager.importFromJson(json)
                // Count what the restore will destroy, so the dialog can name it. Off-cloud this is
                // the café's only copy — Requirement 8.3.
                val offCloud = modeRepository.currentMode() != com.razstudio.pos.data.OperatingMode.CLOUD
                val orders = orderDao.getAllOrders().size
                val menuItems = menuDao.getAll().size
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        importPreview = preview,
                        showConfirmDialog = true,
                        currentOrderCount = orders,
                        currentMenuItemCount = menuItems,
                        localDataIsOnlyCopy = offCloud,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = str().importFailed.format(e.message)) }
            }
        }
    }

    /**
     * Applies the previously parsed import, replacing all local data.
     */
    fun confirmImport() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, showConfirmDialog = false) }
            try {
                val json = pendingImportJson
                    ?: throw IllegalStateException("No import data available")
                backupManager.applyImport(json)
                pendingImportJson = null
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        importPreview = null,
                        successMessage = str().databaseRestored
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = str().importFailed.format(e.message)) }
            }
        }
    }

    /**
     * Dismisses the import preview/confirm dialog.
     */
    fun dismissImport() {
        pendingImportJson = null
        _uiState.update { it.copy(importPreview = null, showConfirmDialog = false) }
    }

    /**
     * Creates a share intent for the exported backup file.
     */
    fun createShareIntent(uri: Uri): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Clears transient messages (error/success).
     */
    fun clearMessages() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }

    /**
     * Returns true if export data is ready to be written.
     */
    fun hasExportReady(): Boolean = pendingExportJson != null
}
