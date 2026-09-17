package net.clench.wallet.data.network

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.ElectrumConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual Android native Electrum client -> IPv4 loopback relay -> disposable protocol fixture. */
@RunWith(AndroidJUnit4::class)
class NativeElectrumOfflineTest {
    private fun fixture(block: (SettingsManager, ElectrumConnectionFactory, ElectrumConfig, CompletableFuture<Socket>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "native-offline-${UUID.randomUUID()}"
        val wrapper = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences = context.getSharedPreferences(name, mode)
        }
        val settings = SettingsManager(wrapper)
        ServerSocket(0).use { server ->
            val peer = CompletableFuture<Socket>()
            val worker = Executors.newSingleThreadExecutor()
            val serving = worker.submit {
                runCatching {
                    server.accept().use { socket ->
                        peer.complete(socket)
                        val reader = socket.getInputStream().bufferedReader()
                        val output = socket.getOutputStream().bufferedWriter()
                        while (true) {
                            val line = reader.readLine() ?: break
                            val request = JSONObject(line)
                            val value = when (request.getString("method")) {
                                "server.version" -> JSONArray().put("public-fixture").put("1.4")
                                "blockchain.estimatefee" -> 0.00001
                                else -> JSONObject.NULL
                            }
                            output.write(JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", value).toString())
                            output.newLine(); output.flush()
                        }
                    }
                }
            }
            try {
                block(settings, ElectrumConnectionFactory(settings), ElectrumConfig(serverUrl = "127.0.0.1", port = server.localPort, useSsl = false), peer)
            } finally {
                peer.getNow(null)?.close(); server.close()
                serving.get(5, TimeUnit.SECONDS); worker.shutdownNow()
                context.deleteSharedPreferences(name)
            }
        }
    }

    @Test fun nativePingWorksThenOfflineClosesCapturedClientTransport() = fixture { settings, factory, config, _ ->
        factory.createConnection(config).use { connection ->
            val captured = connection.client
            captured.ping()
            settings.setOfflineMode(true)
            assertTrue(runCatching { connection.client }.isFailure)
            assertTrue(runCatching { captured.ping() }.isFailure)
            settings.setOfflineMode(false)
            assertTrue(runCatching { connection.client }.isFailure)
        }
    }

    @Test fun upstreamEofClosesNativeRelayWithoutWaitingForOuterDeadline() = fixture { _, factory, config, peer ->
        factory.createConnection(config).use { connection ->
            val captured = connection.client
            captured.ping()
            peer.get(5, TimeUnit.SECONDS).close()
            val worker = Executors.newSingleThreadExecutor()
            try {
                assertTrue(worker.submit<Boolean> { runCatching { captured.ping() }.isFailure }.get(5, TimeUnit.SECONDS))
            } finally { worker.shutdownNow() }
        }
    }
}
