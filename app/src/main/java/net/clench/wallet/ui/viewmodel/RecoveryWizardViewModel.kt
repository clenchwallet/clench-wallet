package net.clench.wallet.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.clench.wallet.data.backup.ClenchStateBackupManager
import javax.inject.Inject

@HiltViewModel
class RecoveryWizardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: ClenchStateBackupManager
) : ViewModel() {

    data class UiState(
        val isImportingBackup: Boolean = false,
        val importStatus: String? = null,
        val importError: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun importStateBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImportingBackup = true, importStatus = null, importError = null) }
            try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("Could not read backup file")
                }
                val result = withContext(Dispatchers.IO) {
                    backupManager.importStateBackupJson(json)
                }
                _uiState.update {
                    it.copy(
                        isImportingBackup = false,
                        importStatus = result.toUserMessage(),
                        importError = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImportingBackup = false,
                        importStatus = null,
                        importError = "Backup import failed: ${e.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    fun clearStatus() {
        _uiState.update { it.copy(importStatus = null, importError = null) }
    }
}
