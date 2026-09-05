package net.clench.wallet.audit

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.data.network.ElectrumConnectionFactory
import net.clench.wallet.data.repository.BdkBitcoinRepository
import net.clench.wallet.domain.model.ElectrumConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Defensive characterization only: finite 3 MiB input, no listener/network/OOM. */
class ElectrumBoundaryRegressionTest {
    private class SyntheticSocket(val source: InputStream) : Socket() {
        var closeObserved = false
        private val sink = ByteArrayOutputStream()
        override fun getInputStream() = source
        override fun getOutputStream() = sink
        override fun setSoTimeout(timeout: Int) {}
        override fun close() { closeObserved = true; source.close() }
    }

    @Before fun androidStubs() {
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After fun cleanup() = unmockkAll()

    private fun repository(socket: Socket): BdkBitcoinRepository {
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo()
        val settings = mockk<SettingsManager>()
        every { settings.isOfflineMode() } returns false
        every { settings.loadElectrumConfig() } returns ElectrumConfig()
        val factory = mockk<ElectrumConnectionFactory>()
        every { factory.createRawSocket(any()) } returns socket
        return BdkBitcoinRepository(
            context, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
            settings, factory, mockk(), mockk(), mockk()
        )
    }

    @Test fun tipReaderRejectsOversizedLineBeforeConsumingFiniteSource() {
        val total = 3 * 1024 * 1024
        var supplied = 0
        val source = object : InputStream() {
            override fun read(): Int = if (supplied++ < total) ' '.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = minOf(len, total - supplied)
                if (n <= 0) return -1
                b.fill(' '.code.toByte(), off, off + n)
                supplied += n
                return n
            }
        }
        val socket = SyntheticSocket(source)
        val repo = repository(socket)
        val method = repo.javaClass.declaredMethods.single { it.name.startsWith("currentTipHeightFromElectrum") }
        method.isAccessible = true
        method.invoke(repo)
        assertTrue("The real helper must stop at the line cap plus buffered read-ahead", supplied <= 72 * 1024)
        assertTrue("Tip helper closes via use", socket.closeObserved)
    }

    @Test fun batchReadFailureClosesItsSocket() {
        val socket = SyntheticSocket(object : InputStream() {
            override fun read(): Int = throw IOException("Synthetic read failure")
        })
        val repo = repository(socket)
        val method = repo.javaClass.declaredMethods.single { it.name.startsWith("batchElectrumTxLookup") }
        method.isAccessible = true
        val result = method.invoke(repo, listOf("00".repeat(32)), "unused", 100)
        assertEquals(emptyMap<String, Pair<Long, Long>>(), result)
        assertTrue("Exception path must close the socket", socket.closeObserved)
        socket.close()
    }

    @Test fun batchNormalEofClosesItsSocket() {
        val socket = SyntheticSocket(object : InputStream() { override fun read() = -1 })
        val repo = repository(socket)
        val method = repo.javaClass.declaredMethods.single { it.name.startsWith("batchElectrumTxLookup") }
        method.isAccessible = true
        method.invoke(repo, listOf("00".repeat(32)), "unused", 100)
        assertTrue("Normal path closes, isolating failure-path defect", socket.closeObserved)
    }
}
