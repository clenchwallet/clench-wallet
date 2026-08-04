package net.clench.wallet.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.ByteArrayOutputStream
import java.util.Base64
import net.clench.wallet.domain.repository.BitcoinRepository
import net.clench.wallet.domain.repository.BuiltTransactionReview
import net.clench.wallet.domain.repository.PsbtSigningProgress
import net.clench.wallet.ui.viewmodel.HardwareWalletPsbtViewModel
import net.clench.wallet.ui.viewmodel.PsbtHandoff
import net.clench.wallet.ui.viewmodel.PsbtPickerPurpose
import net.clench.wallet.ui.viewmodel.PsbtStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HardwareWalletPsbtViewModelTest {

    private fun handoff(walletId: String, psbt: String, device: String) = PsbtHandoff(
        walletId = walletId,
        originalUnsignedPsbtBase64 = psbt,
        currentPsbtBase64 = psbt,
        deviceType = device,
        sourceSessionGeneration = 0L
    )

    private fun validEnvelope(globalType: Int): String {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()))
        output.write(1)
        output.write(globalType)
        output.write(1)
        output.write(0)
        output.write(0)
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    private fun review(txid: String) = BuiltTransactionReview(
        txid = txid,
        feeSat = 100,
        vsize = 100,
        feeRateSatPerVbyte = 1.0,
        inputs = listOf("input-$txid"),
        outputs = emptyList()
    )

    @Test
    fun `late inspection result cannot overwrite a replacement session`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = mockk<BitcoinRepository>()
            val store = mockk<PsbtStore>()
            val firstInspection = CompletableDeferred<BuiltTransactionReview>()
            every { store.consume(any(), any(), any(), any()) } returnsMany listOf(
                handoff("wallet-a", "unsigned-a", "SEEDSIGNER"),
                handoff("wallet-b", "unsigned-b", "JADE")
            )
            coEvery { repository.inspectPsbt("wallet-a", "unsigned-a") } coAnswers {
                firstInspection.await()
            }
            coEvery { repository.inspectPsbt("wallet-b", "unsigned-b") } returns review("review-b")

            val viewModel = HardwareWalletPsbtViewModel(repository, store)
            assertNotNull(viewModel.initFromStore("wallet-a", "SEEDSIGNER"))
            runCurrent()

            assertNotNull(viewModel.initFromStore("wallet-b", "JADE"))
            runCurrent()
            assertEquals("wallet-b", viewModel.uiState.value.walletId)
            assertEquals("review-b", viewModel.uiState.value.transactionReview?.txid)

            firstInspection.complete(review("stale-review-a"))
            advanceUntilIdle()

            assertEquals("wallet-b", viewModel.uiState.value.walletId)
            assertEquals("review-b", viewModel.uiState.value.transactionReview?.txid)
            assertFalse(viewModel.uiState.value.isReviewLoading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `cancelled stale inspection cannot clear the current review`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = mockk<BitcoinRepository>()
            val store = mockk<PsbtStore>()
            val firstInspection = CompletableDeferred<BuiltTransactionReview>()
            every { store.consume(any(), any(), any(), any()) } returnsMany listOf(
                handoff("wallet-a", "unsigned-a", "SEEDSIGNER"),
                handoff("wallet-b", "unsigned-b", "JADE")
            )
            coEvery { repository.inspectPsbt("wallet-a", "unsigned-a") } coAnswers {
                firstInspection.await()
            }
            coEvery { repository.inspectPsbt("wallet-b", "unsigned-b") } returns review("review-b")

            val viewModel = HardwareWalletPsbtViewModel(repository, store)
            viewModel.initFromStore("wallet-a", "SEEDSIGNER")
            runCurrent()
            viewModel.initFromStore("wallet-b", "JADE")
            runCurrent()

            firstInspection.cancel()
            advanceUntilIdle()

            assertEquals("wallet-b", viewModel.uiState.value.walletId)
            assertEquals("review-b", viewModel.uiState.value.transactionReview?.txid)
            assertNull(viewModel.uiState.value.error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `duplicate signer callback is rejected and pending replacement is not consumed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = mockk<BitcoinRepository>()
            val store = mockk<PsbtStore>()
            val mergeStarted = CompletableDeferred<Unit>()
            val finishMerge = CompletableDeferred<Unit>()
            every { store.consume(any(), any(), any(), any()) } returnsMany listOf(
                handoff("wallet-a", "unsigned-a", "SEEDSIGNER"),
                handoff("wallet-b", "unsigned-b", "JADE")
            )
            coEvery { repository.inspectPsbt(any(), any()) } answers {
                review("review-${firstArg<String>()}")
            }
            coEvery {
                repository.mergeSignedPsbt("unsigned-a", "unsigned-a", "signed-return")
            } coAnswers {
                mergeStarted.complete(Unit)
                finishMerge.await()
                PsbtSigningProgress("signed-a", readyToBroadcast = true, message = "ready")
            }

            val viewModel = HardwareWalletPsbtViewModel(repository, store)
            viewModel.initFromStore("wallet-a", "SEEDSIGNER")
            advanceUntilIdle()
            viewModel.acknowledgeReview()

            viewModel.onSignedPsbtReceived("wallet-a", "signed-return")
            runCurrent()
            assertTrue(mergeStarted.isCompleted)

            viewModel.onSignedPsbtReceived("wallet-a", "duplicate-return")
            assertTrue(viewModel.uiState.value.error?.contains("already in progress") == true)
            assertNull(viewModel.initFromStore("wallet-b", "JADE"))
            verify(exactly = 1) { store.consume(any(), any(), any(), any()) }

            finishMerge.complete(Unit)
            advanceUntilIdle()
            assertEquals("wallet-a", viewModel.uiState.value.walletId)
            assertEquals("signed-a", viewModel.uiState.value.signedPsbtBase64)
            coVerify(exactly = 1) { repository.mergeSignedPsbt(any(), any(), any()) }

            assertNotNull(viewModel.initFromStore("wallet-b", "JADE"))
            advanceUntilIdle()
            assertEquals("wallet-b", viewModel.uiState.value.walletId)
            verify(exactly = 2) { store.consume(any(), any(), any(), any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `broadcast boundary reauthorizes session and rejects duplicate callbacks`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = mockk<BitcoinRepository>()
            val store = mockk<PsbtStore>()
            val broadcastStarted = CompletableDeferred<Unit>()
            val reachAuthorizationBoundary = CompletableDeferred<Unit>()
            var authorizationChecks = 0
            every { store.consume(any(), any(), any(), any()) } returnsMany listOf(
                handoff("wallet-a", "unsigned-a", "SEEDSIGNER"),
                handoff("wallet-b", "unsigned-b", "JADE")
            )
            coEvery { repository.inspectPsbt(any(), any()) } answers {
                review("review-${firstArg<String>()}")
            }
            coEvery {
                repository.mergeSignedPsbt("unsigned-a", "unsigned-a", "signed-return")
            } returns PsbtSigningProgress("signed-a", readyToBroadcast = true, message = "ready")
            coEvery {
                repository.applyAndBroadcastPsbt("wallet-a", "signed-a", "unsigned-a", any())
            } coAnswers {
                broadcastStarted.complete(Unit)
                reachAuthorizationBoundary.await()
                @Suppress("UNCHECKED_CAST")
                val authorize = invocation.args[3] as () -> Unit
                authorize()
                authorizationChecks += 1
                "txid-a"
            }

            val viewModel = HardwareWalletPsbtViewModel(repository, store)
            viewModel.initFromStore("wallet-a", "SEEDSIGNER")
            advanceUntilIdle()
            viewModel.acknowledgeReview()
            viewModel.onSignedPsbtReceived("wallet-a", "signed-return")
            advanceUntilIdle()

            viewModel.broadcastSignedPsbt("wallet-a")
            runCurrent()
            assertTrue(broadcastStarted.isCompleted)

            viewModel.broadcastSignedPsbt("wallet-a")
            assertTrue(viewModel.uiState.value.error?.contains("already in progress") == true)
            assertNull(viewModel.initFromStore("wallet-b", "JADE"))
            verify(exactly = 1) { store.consume(any(), any(), any(), any()) }

            reachAuthorizationBoundary.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, authorizationChecks)
            assertEquals("txid-a", viewModel.uiState.value.txid)
            assertFalse(viewModel.uiState.value.isBroadcasting)
            coVerify(exactly = 1) {
                repository.applyAndBroadcastPsbt("wallet-a", "signed-a", "unsigned-a", any())
            }

            assertNotNull(viewModel.initFromStore("wallet-b", "JADE"))
            advanceUntilIdle()
            assertEquals("wallet-b", viewModel.uiState.value.walletId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `picker reconstruction preserves original policy and advances session generation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = mockk<BitcoinRepository>()
            val store = PsbtStore(
                monotonicNanos = { 1L },
                tokenBytes = { size -> ByteArray(size) { 0x33 } }
            )
            val original = validEnvelope(1)
            val partial = validEnvelope(2)
            store.store("wallet-a", original, "COLDCARD_Q")
            coEvery { repository.inspectPsbt("wallet-a", any()) } returns review("review-a")
            coEvery {
                repository.mergeSignedPsbt(original, original, "signed-return")
            } returns PsbtSigningProgress(partial, readyToBroadcast = false, message = "partial")

            val firstViewModel = HardwareWalletPsbtViewModel(repository, store)
            assertNotNull(firstViewModel.initFromStore("wallet-a", "COLDCARD_Q"))
            advanceUntilIdle()
            firstViewModel.acknowledgeReview()
            firstViewModel.onSignedPsbtReceived("wallet-a", "signed-return")
            advanceUntilIdle()
            val firstToken = checkNotNull(
                firstViewModel.stageForDocumentPicker(
                    PsbtPickerPurpose.HARDWARE_EXPORT,
                    "COLDCARD_Q"
                )
            )

            val replacementViewModel = HardwareWalletPsbtViewModel(repository, store)
            val restored = checkNotNull(
                replacementViewModel.initFromStore(
                    "wallet-a",
                    "COLDCARD_Q",
                    firstToken,
                    PsbtPickerPurpose.HARDWARE_EXPORT
                )
            )
            assertEquals(original, restored.originalUnsignedPsbtBase64)
            assertEquals(partial, restored.currentPsbtBase64)
            advanceUntilIdle()

            val secondToken = checkNotNull(
                replacementViewModel.stageForDocumentPicker(
                    PsbtPickerPurpose.HARDWARE_EXPORT,
                    "COLDCARD_Q"
                )
            )
            val secondStage = checkNotNull(
                store.consume(
                    "wallet-a",
                    "COLDCARD_Q",
                    secondToken,
                    PsbtPickerPurpose.HARDWARE_EXPORT
                )
            )
            assertEquals(2L, secondStage.sourceSessionGeneration)
            assertEquals(original, secondStage.originalUnsignedPsbtBase64)
            assertEquals(partial, secondStage.currentPsbtBase64)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
