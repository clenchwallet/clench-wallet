package net.clench.wallet.ui

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import net.clench.wallet.BuildConfig
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.ui.components.NfcIntentDecoder
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual activity callbacks and Android NDEF objects; no production wallets or remote NFC. */
@RunWith(AndroidJUnit4::class)
class NfcIntentBoundaryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val psbt = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte(), 1, 0, 1, 0, 0)

    @Before fun disposableOnly() {
        check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(InstrumentationRegistry.getArguments().getString("clenchDisposableEmulator") == "YES")
        SettingsManager(context).apply {
            setOfflineMode(true); setBtcPriceEnabled(false); setExternalFeeLookupEnabled(false)
            setOnboarded(); setAppLockMode("none")
        }
    }

    private fun external(bytes: ByteArray) = NdefRecord.createExternal("bitcoin.org", "psbt", bytes)
    private fun intent(vararg records: NdefRecord) = Intent(context, MainActivity::class.java)
        .setAction(NfcAdapter.ACTION_NDEF_DISCOVERED)
        .putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(NdefMessage(records)))

    @Test fun malformedWarmIntentsAreContainedAndValidInputStillWorks() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = android.os.SystemClock.uptimeMillis() + 20_000
            var ready = false
            while (!ready && android.os.SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { ready = it.captureSensitiveAuthenticationSession() != null }
                if (!ready) android.os.SystemClock.sleep(100)
            }
            assertTrue("Unlocked foreground must be ready", ready)
            var payloads = 0
            var collector: Job? = null
            scenario.onActivity { activity ->
                collector = MainScope().launch(start = CoroutineStart.UNDISPATCHED) {
                    activity.nfcPsbtFlow.collect { payloads++ }
                }
                val malformed = listOf(
                    intent(external(psbt), external(psbt)),
                    intent(external(byteArrayOf())),
                    intent(NdefRecord.createMime("text/plain", "https://example.invalid/#t=%".toByteArray())),
                    Intent(context, MainActivity::class.java).setAction(NfcAdapter.ACTION_NDEF_DISCOVERED)
                        .putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(NdefMessage(external(psbt)), NdefMessage(external(psbt))))
                )
                malformed.forEach { incoming ->
                    assertEquals(NfcIntentDecoder.Result.Rejected, NfcIntentDecoder.decode(incoming))
                    instrumentation.callActivityOnNewIntent(activity, incoming)
                }
                assertEquals(0, payloads)
                instrumentation.callActivityOnNewIntent(activity, intent(external(psbt)))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(1, payloads); collector?.cancel() }
        }
    }

    @Test fun malformedColdAndLockedWarmIntentsRemainContained() {
        SettingsManager(context).setAppLockMode("pin")
        ActivityScenario.launch<MainActivity>(intent(external(psbt), external(psbt))).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                var payloads = 0
                val collector = MainScope().launch(start = CoroutineStart.UNDISPATCHED) {
                    activity.nfcPsbtFlow.collect { payloads++ }
                }
                instrumentation.callActivityOnNewIntent(activity, intent(external(psbt)))
                instrumentation.callActivityOnNewIntent(activity, intent(external(byteArrayOf())))
                assertEquals(0, payloads)
                collector.cancel()
            }
            scenario.recreate()
        }
    }
}
