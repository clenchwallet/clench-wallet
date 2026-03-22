package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.data.local.dao.UtxoMetadataDao
import net.clench.wallet.data.local.entity.UtxoMetadataEntity
import net.clench.wallet.domain.model.UtxoInfo
import net.clench.wallet.domain.repository.BitcoinRepository
import javax.inject.Inject

@HiltViewModel
class UtxoViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val utxoMetadataDao: UtxoMetadataDao
) : ViewModel() {

    data class UtxoItem(
        val txid: String,
        val vout: UInt,
        val amountSat: Long,
        val address: String?,
        val confirmations: Int,
        val isSpent: Boolean,
        val keychain: String,
        val label: String? = null,
        val isFrozen: Boolean = false,
        val isSelected: Boolean = false
    ) {
        val outpoint: String get() = "$txid:$vout"
    }

    data class UiState(
        val walletId: String = "",
        val utxos: List<UtxoItem> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val showLabelDialog: Boolean = false,
        val labelDialogOutpoint: String = "",
        val labelDialogText: String = ""
    ) {
        val selectedCount: Int get() = utxos.count { it.isSelected && !it.isFrozen }
        val selectedSats: Long get() = utxos.filter { it.isSelected && !it.isFrozen }.sumOf { it.amountSat }
        val selectedOutpoints: List<String> get() = utxos.filter { it.isSelected && !it.isFrozen }.map { it.outpoint }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun clear() {
        _uiState.update { UiState() }
    }

    fun load(walletId: String) {
        _uiState.update { it.copy(walletId = walletId, isLoading = true) }
        viewModelScope.launch {
            try {
                val utxos = bitcoinRepository.listUnspent(walletId)
                val metadata = utxoMetadataDao.getForWallet(walletId).associateBy { it.outpoint }

                val items = utxos.map { utxo ->
                    val op = "${utxo.txid}:${utxo.vout}"
                    val meta = metadata[op]
                    UtxoItem(
                        txid = utxo.txid,
                        vout = utxo.vout,
                        amountSat = utxo.amountSat,
                        address = utxo.address,
                        confirmations = utxo.confirmations,
                        isSpent = utxo.isSpent,
                        keychain = utxo.keychain,
                        label = meta?.label,
                        isFrozen = meta?.isFrozen ?: false
                    )
                }.sortedByDescending { it.amountSat }

                _uiState.update { it.copy(utxos = items, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun toggleSelection(outpoint: String) {
        _uiState.update { state ->
            state.copy(utxos = state.utxos.map {
                if (it.outpoint == outpoint && !it.isFrozen) it.copy(isSelected = !it.isSelected) else it
            })
        }
    }

    fun toggleFreeze(outpoint: String) {
        val walletId = _uiState.value.walletId
        viewModelScope.launch {
            val current = utxoMetadataDao.getByOutpoint(outpoint)
            val newFrozen = !(current?.isFrozen ?: false)
            utxoMetadataDao.upsert(
                UtxoMetadataEntity(
                    outpoint = outpoint,
                    walletId = walletId,
                    label = current?.label,
                    isFrozen = newFrozen
                )
            )
            _uiState.update { state ->
                state.copy(utxos = state.utxos.map {
                    if (it.outpoint == outpoint) it.copy(isFrozen = newFrozen, isSelected = false) else it
                })
            }
        }
    }

    fun showLabelDialog(outpoint: String) {
        val current = _uiState.value.utxos.find { it.outpoint == outpoint }
        _uiState.update { it.copy(
            showLabelDialog = true,
            labelDialogOutpoint = outpoint,
            labelDialogText = current?.label ?: ""
        ) }
    }

    fun setLabelDialogText(text: String) {
        _uiState.update { it.copy(labelDialogText = text) }
    }

    fun dismissLabelDialog() {
        _uiState.update { it.copy(showLabelDialog = false) }
    }

    fun saveLabel() {
        val outpoint = _uiState.value.labelDialogOutpoint
        val label = _uiState.value.labelDialogText.ifBlank { null }
        val walletId = _uiState.value.walletId

        viewModelScope.launch {
            val current = utxoMetadataDao.getByOutpoint(outpoint)
            utxoMetadataDao.upsert(
                UtxoMetadataEntity(
                    outpoint = outpoint,
                    walletId = walletId,
                    label = label,
                    isFrozen = current?.isFrozen ?: false
                )
            )
            _uiState.update { state ->
                state.copy(
                    showLabelDialog = false,
                    utxos = state.utxos.map {
                        if (it.outpoint == outpoint) it.copy(label = label) else it
                    }
                )
            }
        }
    }
}
