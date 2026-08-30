package net.clench.wallet.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.domain.repository.BuiltTransactionReview
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@HiltViewModel
class HardwareWalletPsbtViewModel @Inject constructor(
    private val bitcoinRepository: BitcoinRepository,
    private val psbtStore: PsbtStore
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val txid: String? = null,
        val error: String? = null,
        val isBroadcasting: Boolean = false,
        val isProcessingSignedPsbt: Boolean = false,
        val signedPsbtBase64: String? = null,
        val readyToBroadcast: Boolean = false,
        val hasCollectedSignature: Boolean = false,
        val collectedSignerReturns: Int = 0,
        val signingMessage: String? = null,
        val walletId: String = "",
        val psbtBase64: String = "",
        val deviceType: String = "",
        val transactionReview: BuiltTransactionReview? = null,
        val isReviewLoading: Boolean = false,
        val reviewAcknowledged: Boolean = false,
        val requiresHighFeeConfirmation: Boolean = false,
        val highFeeAcknowledged: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private data class SigningSession(
        val generation: Long,
        val walletId: String,
        val unsignedPsbtBase64: String
    )

    private val sessionLock = Any()
    private val sessionGeneration = AtomicLong(0L)
    private var activeSession: SigningSession? = null
    private val signingOperationActive = AtomicBoolean(false)
    private val tapsignerOperationGeneration = AtomicLong(0L)

    class TapsignerSigningToken internal constructor(
        internal val operationId: Long,
        val psbtBase64: String
    )

    private data class ReservedTapsignerOperation(
        val operationId: Long,
        val session: SigningSession,
        val currentPsbtBase64: String
    )

    private var reservedTapsignerOperation: ReservedTapsignerOperation? = null

    private fun isActiveSession(session: SigningSession): Boolean = synchronized(sessionLock) {
        activeSession === session && activeSession?.generation == session.generation
    }

    private fun assertActiveSession(session: SigningSession) {
        if (!isActiveSession(session)) {
            throw SecurityException(
                "Signer session changed before the transaction could be broadcast"
            )
        }
    }

    private inline fun updateIfActive(
        session: SigningSession,
        transform: (UiState) -> UiState
    ): Boolean = synchronized(sessionLock) {
        if (activeSession !== session || activeSession?.generation != session.generation) {
            false
        } else {
            _uiState.update { current ->
                if (activeSession === session &&
                    activeSession?.generation == session.generation &&
                    current.walletId == session.walletId &&
                    current.psbtBase64.isNotBlank()
                ) {
                    transform(current)
                } else {
                    current
                }
            }
            true
        }
    }

    /**
     * Initialize from PsbtStore — called once when the screen opens.
     */
    internal fun initFromStore(
        expectedWalletId: String,
        expectedDeviceType: String,
        pickerToken: String? = null,
        pickerPurpose: PsbtPickerPurpose? = null
    ): PsbtHandoff? {
        val initialized = synchronized(sessionLock) {
            // Never replace an authorization while signer verification or a
            // broadcast is in flight. PsbtStore remains unconsumed so a caller
            // can retry after the current operation completes.
            if (signingOperationActive.get()) {
                _uiState.update {
                    it.copy(error = "A signer operation is already in progress; retry after it completes")
                }
                return null
            }
            val data = psbtStore.consume(
                expectedWalletId = expectedWalletId,
                expectedDeviceType = expectedDeviceType,
                pickerToken = pickerToken,
                pickerPurpose = pickerPurpose
            ) ?: run {
                if (pickerToken != null || pickerPurpose != null) {
                    invalidatePickerSessionLocked("The file hand-off expired or did not match this signing session")
                }
                return null
            }
            val nextGeneration = sessionGeneration.updateAndGet { localGeneration ->
                val baseline = maxOf(localGeneration, data.sourceSessionGeneration)
                check(baseline < Long.MAX_VALUE) { "Signing session generation is exhausted" }
                baseline + 1L
            }
            val session = SigningSession(
                generation = nextGeneration,
                walletId = data.walletId,
                unsignedPsbtBase64 = data.originalUnsignedPsbtBase64
            )
            activeSession = session
            _uiState.update {
                it.copy(
                    walletId = data.walletId,
                    psbtBase64 = data.currentPsbtBase64,
                    deviceType = data.deviceType,
                    signedPsbtBase64 = null,
                    readyToBroadcast = false,
                    hasCollectedSignature = false,
                    collectedSignerReturns = 0,
                    signingMessage = null,
                    transactionReview = null,
                    isReviewLoading = true,
                    reviewAcknowledged = false,
                    requiresHighFeeConfirmation = false,
                    highFeeAcknowledged = false
                )
            }
            data to session
        }
        val (data, session) = initialized
        viewModelScope.launch {
            try {
                val review = bitcoinRepository.inspectPsbt(
                    data.walletId,
                    data.currentPsbtBase64
                )
                SendViewModel.feeSafetyError(review)?.let { error(it) }
                updateIfActive(session) {
                    it.copy(
                        transactionReview = review,
                        isReviewLoading = false,
                        requiresHighFeeConfirmation = SendViewModel.requiresHighFeeConfirmation(review)
                    )
                }
            } catch (e: Exception) {
                updateIfActive(session) {
                    it.copy(
                        isReviewLoading = false,
                        error = "Could not verify the PSBT before hardware signing: ${e.message}"
                    )
                }
            }
        }
        return data
    }

    /**
     * Preserve only the bounded PSBT hand-off in the process-scoped store while DocumentsUI
     * forces route/ViewModel disposal. The recreated route re-inspects it and requires a fresh
     * review acknowledgement before consuming the selected file URI.
     */
    internal fun stageForDocumentPicker(
        purpose: PsbtPickerPurpose,
        requestedDeviceType: String
    ): String? = synchronized(sessionLock) {
        val current = _uiState.value
        val session = activeSession
        if (signingOperationActive.get() ||
            session == null ||
            current.walletId.isBlank() ||
            current.psbtBase64.isBlank() ||
            current.deviceType.isBlank() ||
            current.deviceType != requestedDeviceType ||
            current.walletId != session.walletId
        ) {
            _uiState.update { it.copy(error = "No idle PSBT session is available for file transfer") }
            return@synchronized null
        }
        return@synchronized try {
            psbtStore.stageForPicker(
                walletId = current.walletId,
                originalUnsignedPsbtBase64 = session.unsignedPsbtBase64,
                currentPsbtBase64 = current.psbtBase64,
                deviceType = current.deviceType,
                sourceSessionGeneration = session.generation,
                purpose = purpose
            )
        } catch (_: Throwable) {
            _uiState.update { it.copy(error = "Another signing hand-off is already pending") }
            null
        }
    }

    fun selectDeviceType(requestedDeviceType: String): Boolean = synchronized(sessionLock) {
        if (requestedDeviceType.isBlank() || signingOperationActive.get() || activeSession == null) {
            return@synchronized false
        }
        _uiState.update { current -> current.copy(deviceType = requestedDeviceType) }
        true
    }

    fun discardDocumentPickerStage(token: String) {
        psbtStore.discardPickerStage(token)
    }

    fun cancelDocumentPickerRoundTrip(token: String, message: String = "File selection was cancelled") {
        synchronized(sessionLock) {
            psbtStore.discardPickerStage(token)
            invalidatePickerSessionLocked(message)
        }
    }

    private fun invalidatePickerSessionLocked(message: String) {
        activeSession = null
        sessionGeneration.incrementAndGet()
        _uiState.value = UiState(error = message)
    }

    /** Reserve the active reviewed PSBT while the screen performs authenticated NFC signing. */
    fun beginTapsignerSigning(walletId: String): TapsignerSigningToken? = synchronized(sessionLock) {
        val current = _uiState.value
        val session = activeSession
        if (walletId.isBlank() || walletId != current.walletId || session?.walletId != walletId) {
            _uiState.update { it.copy(error = "Security: TAPSIGNER request does not belong to the active wallet") }
            return@synchronized null
        }
        if (current.deviceType != "TAPSIGNER") {
            _uiState.update { it.copy(error = "Select TAPSIGNER before starting NFC signing") }
            return@synchronized null
        }
        if (!current.reviewAcknowledged || current.transactionReview == null) {
            _uiState.update { it.copy(error = "Review and approve the unsigned transaction before TAPSIGNER signing") }
            return@synchronized null
        }
        if (current.requiresHighFeeConfirmation && !current.highFeeAcknowledged) {
            _uiState.update { it.copy(error = "Acknowledge the high fee before TAPSIGNER signing") }
            return@synchronized null
        }
        if (current.psbtBase64.isBlank() || session.unsignedPsbtBase64.isBlank()) {
            _uiState.update { it.copy(error = "No PSBT is loaded") }
            return@synchronized null
        }
        if (!signingOperationActive.compareAndSet(false, true)) {
            _uiState.update { it.copy(error = "A signer operation is already in progress") }
            return@synchronized null
        }
        val operationId = tapsignerOperationGeneration.incrementAndGet()
        reservedTapsignerOperation = ReservedTapsignerOperation(
            operationId = operationId,
            session = session,
            currentPsbtBase64 = current.psbtBase64
        )
        _uiState.update { it.copy(error = null) }
        TapsignerSigningToken(operationId, current.psbtBase64)
    }

    /** Release an NFC reservation without accepting any signer material. */
    fun cancelTapsignerSigning(token: TapsignerSigningToken) {
        synchronized(sessionLock) {
            val reserved = reservedTapsignerOperation
            if (reserved?.operationId == token.operationId) {
                reservedTapsignerOperation = null
                signingOperationActive.set(false)
            }
        }
    }

    /** Consume exactly one result for the reserved session and PSBT snapshot. */
    fun completeTapsignerSigning(
        token: TapsignerSigningToken,
        signedPsbtPayload: String
    ): Boolean {
        val operation = synchronized(sessionLock) {
            val reserved = reservedTapsignerOperation
            if (reserved == null || reserved.operationId != token.operationId) {
                _uiState.update { it.copy(error = "Security: stale TAPSIGNER NFC result was discarded") }
                return false
            }
            if (
                reserved.currentPsbtBase64 != token.psbtBase64 ||
                activeSession !== reserved.session ||
                _uiState.value.psbtBase64 != reserved.currentPsbtBase64
            ) {
                // The callback belongs to the reserved operation, but its bound
                // session or PSBT snapshot is no longer valid. Release that
                // reservation so the user can start a fresh signing attempt.
                // A callback for a different operation ID must never release a
                // newer reservation, which is why that case returns above.
                reservedTapsignerOperation = null
                signingOperationActive.set(false)
                _uiState.update { it.copy(error = "Security: stale TAPSIGNER NFC result was discarded") }
                return false
            }
            reservedTapsignerOperation = null
            Triple(reserved.session, reserved.currentPsbtBase64, signedPsbtPayload)
        }
        launchSignedPsbtMerge(operation.first, operation.second, operation.third)
        return true
    }

    /**
     * Called when signer data is received from the hardware wallet (via QR, NFC, or file).
     * Merge it into the current PSBT and only enable broadcast after the policy finalizes.
     */
    fun onSignedPsbtReceived(walletId: String, signedPsbtPayload: String) {
        val operation = synchronized(sessionLock) {
            val current = _uiState.value
            val session = activeSession
            if (walletId.isBlank() || walletId != current.walletId || session?.walletId != walletId) {
                _uiState.update { it.copy(error = "Security: signer return does not belong to the active wallet") }
                return
            }
            if (!current.reviewAcknowledged || current.transactionReview == null) {
                _uiState.update { it.copy(error = "Review and approve the unsigned transaction before importing signatures") }
                return
            }
            if (current.psbtBase64.isBlank() || session.unsignedPsbtBase64.isBlank()) {
                _uiState.update { it.copy(error = "No PSBT is loaded") }
                return
            }
            if (!signingOperationActive.compareAndSet(false, true)) {
                _uiState.update { it.copy(error = "A signer operation is already in progress") }
                return
            }
            session to current.psbtBase64
        }
        val (session, currentPsbtBase64) = operation
        launchSignedPsbtMerge(session, currentPsbtBase64, signedPsbtPayload)
    }

    private fun launchSignedPsbtMerge(
        session: SigningSession,
        currentPsbtBase64: String,
        signedPsbtPayload: String
    ) {
        viewModelScope.launch {
            updateIfActive(session) {
                it.copy(
                    isProcessingSignedPsbt = true,
                    signedPsbtBase64 = null,
                    readyToBroadcast = false,
                    signingMessage = null,
                    error = null
                )
            }
            try {
                val progress = bitcoinRepository.mergeSignedPsbt(
                    unsignedPsbtBase64 = session.unsignedPsbtBase64,
                    currentPsbtBase64 = currentPsbtBase64,
                    signedPsbtPayload = signedPsbtPayload
                )
                assertActiveSession(session)
                updateIfActive(session) {
                    it.copy(
                        isProcessingSignedPsbt = false,
                        psbtBase64 = progress.psbtBase64,
                        signedPsbtBase64 = if (progress.readyToBroadcast) progress.psbtBase64 else null,
                        readyToBroadcast = progress.readyToBroadcast,
                        hasCollectedSignature = true,
                        collectedSignerReturns = it.collectedSignerReturns + 1,
                        signingMessage = progress.message,
                        error = null
                    )
                }
            } catch (e: SecurityException) {
                updateIfActive(session) {
                    it.copy(
                        isProcessingSignedPsbt = false,
                        error = "Security: ${e.message}"
                    )
                }
            } catch (e: Exception) {
                updateIfActive(session) {
                    it.copy(
                        isProcessingSignedPsbt = false,
                        error = e.message ?: "Signed PSBT import failed"
                    )
                }
            } finally {
                signingOperationActive.set(false)
            }
        }
    }

    /**
     * Validate outputs match the original unsigned PSBT, finalize, and broadcast.
     */
    fun broadcastSignedPsbt(walletId: String) {
        val operation = synchronized(sessionLock) {
            val current = _uiState.value
            val session = activeSession
            if (walletId.isBlank() || walletId != current.walletId || session?.walletId != walletId) {
                _uiState.update { it.copy(error = "Security: broadcast request does not belong to the active wallet") }
                return
            }
            val signedPsbtBase64 = current.signedPsbtBase64
            if (signedPsbtBase64.isNullOrBlank() || !current.readyToBroadcast) {
                _uiState.update { it.copy(error = "Collect enough signatures before broadcasting") }
                return
            }
            if (!signingOperationActive.compareAndSet(false, true)) {
                _uiState.update { it.copy(error = "A signer operation is already in progress") }
                return
            }
            session to signedPsbtBase64
        }
        val (session, signedPsbtBase64) = operation

        viewModelScope.launch {
            updateIfActive(session) { it.copy(isBroadcasting = true, error = null) }
            try {
                assertActiveSession(session)
                val txid = bitcoinRepository.applyAndBroadcastPsbt(
                    walletId,
                    signedPsbtBase64,
                    session.unsignedPsbtBase64,
                    assertBroadcastAuthorized = { assertActiveSession(session) }
                )
                assertActiveSession(session)
                updateIfActive(session) {
                    it.copy(
                        isBroadcasting = false,
                        signedPsbtBase64 = null,
                        readyToBroadcast = false,
                        txid = txid
                    )
                }
            } catch (e: SecurityException) {
                updateIfActive(session) { it.copy(isBroadcasting = false, error = "Security: ${e.message}") }
            } catch (e: Exception) {
                updateIfActive(session) { it.copy(isBroadcasting = false, error = e.message ?: "Broadcast failed") }
            } finally {
                signingOperationActive.set(false)
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun acknowledgeHighFee() {
        _uiState.update {
            if (it.requiresHighFeeConfirmation && it.transactionReview != null) {
                it.copy(highFeeAcknowledged = true, error = null)
            } else it
        }
    }

    fun acknowledgeReview() {
        _uiState.update {
            if (it.transactionReview != null &&
                (!it.requiresHighFeeConfirmation || it.highFeeAcknowledged)
            ) it.copy(reviewAcknowledged = true, error = null) else it
        }
    }
}
