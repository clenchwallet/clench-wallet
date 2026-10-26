package net.clench.wallet.data.network

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.clench.wallet.data.local.SettingsManager
import net.clench.wallet.domain.model.ElectrumConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.*
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/** Disposable loopback endpoints only. Public keys in androidTest assets are synthetic.
 * Custom root trust is explicitly injected into test clients only;
 * the release platform trust configuration and the phone trust store are never modified.
 */
@RunWith(AndroidJUnit4::class)
class NetworkTrustAuditTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun bytes(name: String) = instrumentation.context.assets.open("network-trust/$name").use { it.readBytes() }
    private fun cert(name: String) = CertificateFactory.getInstance("X.509")
        .generateCertificate(bytes("$name.der").inputStream()) as X509Certificate
    private fun settings(block: (SettingsManager) -> Unit) {
        val context = instrumentation.targetContext
        val name = "network-trust-${UUID.randomUUID()}"
        val wrapper = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences = context.getSharedPreferences(name, mode)
        }
        try { block(SettingsManager(wrapper)) } finally { context.deleteSharedPreferences(name) }
    }
    private fun trustManager(vararg roots: X509Certificate): X509ExtendedTrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); roots.forEachIndexed { index, root -> setCertificateEntry("root-$index", root) }
        }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return tm.trustManagers.filterIsInstance<X509ExtendedTrustManager>().single()
    }
    private fun provider(
        manager: X509ExtendedTrustManager,
        policy: CertificateDistrust = CertificateDistrust.EMSIGN,
    ) = TlsSocketFactoryProvider { host, pinned ->
        val delegate = if (pinned == null) manager else trustManager(
            CertificateFactory.getInstance("X.509").generateCertificate(pinned.inputStream()) as X509Certificate)
        distrustingSocketFactory(host, delegate, policy)
    }

    private inner class Endpoint(name: String, issuers: List<String>? = null) : AutoCloseable {
        val received = CompletableFuture<String>()
        val firstApplicationByte = CompletableFuture<Int>()
        private var peer: Socket? = null
        val server: SSLServerSocket
        init {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes("$name.key")))
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                val chain = issuers?.let { listOf(name) + it }
                    ?: if (name in listOf("unknown", "expired-self")) listOf(name) else listOf(name, "root")
                load(null); setKeyEntry("fixture", key, charArrayOf(), chain.map { cert(it) }.toTypedArray())
            }
            val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, charArrayOf()) }
            val ctx = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
            server = ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            Thread {
                try {
                    server.accept().use { socket ->
                        peer = socket; socket.soTimeout = 5000
                        val input = socket.getInputStream()
                        val byte = input.read()
                        firstApplicationByte.complete(byte)
                        val first = if (byte < 0) null else byte.toChar() + input.bufferedReader().readLine().orEmpty()
                        received.complete(first ?: "EOF")
                        if (first?.startsWith("GET ") == true) {
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
                        } else socket.getOutputStream().write("OK\n".toByteArray())
                        socket.getOutputStream().flush()
                    }
                } catch (e: Exception) {
                    firstApplicationByte.completeExceptionally(e)
                    received.completeExceptionally(e)
                }
            }.apply { isDaemon = true; start() }
        }
        override fun close() { peer?.close(); server.close() }
    }
    /** SOCKS domain is captured before forwarding to loopback; fixture.invalid cannot resolve publicly. */
    private inner class Socks(private val targetPort: Int) : AutoCloseable {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val destination = CompletableFuture<String>()
        private var local: Socket? = null
        private var upstream: Socket? = null
        init {
            Thread {
                try {
                    val s = server.accept(); local = s; s.soTimeout = 5000
                    val input = java.io.DataInputStream(s.getInputStream()); val out = s.getOutputStream()
                    check(input.readUnsignedByte() == 5); val methods = input.readUnsignedByte(); repeat(methods) { input.readUnsignedByte() }
                    out.write(byteArrayOf(5, 0)); out.flush()
                    check(input.readUnsignedByte() == 5); check(input.readUnsignedByte() == 1); check(input.readUnsignedByte() == 0)
                    check(input.readUnsignedByte() == 3) { "Expected unresolved domain, not client DNS" }
                    val host = ByteArray(input.readUnsignedByte()); input.readFully(host); input.readUnsignedShort()
                    destination.complete(String(host, Charsets.US_ASCII))
                    val remote = Socket("127.0.0.1", targetPort); upstream = remote
                    out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0)); out.flush()
                    Thread { runCatching { remote.getInputStream().copyTo(out) }; runCatching { s.shutdownOutput() } }.apply { isDaemon=true; start() }
                    input.copyTo(remote.getOutputStream())
                } catch (e: Exception) { destination.completeExceptionally(e) }
            }.apply { isDaemon = true; start() }
        }
        override fun close() { local?.close(); upstream?.close(); server.close() }
    }
    private fun route(settings: SettingsManager, endpoint: Endpoint, tor: Boolean, block: (String, Int) -> Unit) {
        if (!tor) block("127.0.0.1", endpoint.server.localPort)
        else Socks(endpoint.server.localPort).use { proxy ->
            settings.setTorProxyHost("127.0.0.1"); settings.setTorProxyPort(proxy.server.localPort); settings.setTorEnabled(true)
            block("fixture.invalid", endpoint.server.localPort)
            assertEquals("fixture.invalid", proxy.destination.get(5, TimeUnit.SECONDS))
        }
    }
    private fun assertTlsRejection(label: String, failure: Throwable?) {
        assertNotNull("Expected TLS rejection: $label", failure)
        val causes = generateSequence(failure) { it.cause }.take(12).toList()
        assertTrue("Must reject at TLS/certificate boundary: $label: ${failure?.javaClass?.simpleName}",
            causes.any { it is SSLHandshakeException || it is SSLPeerUnverifiedException || it is java.security.cert.CertificateException })
    }
    private fun raw(name: String, pin: String?, accepted: Boolean, tor: Boolean) = settings { settings ->
        Endpoint(name).use { endpoint -> route(settings, endpoint, tor) { host, port ->
            val result = runCatching {
                ElectrumConnectionFactory(settings).createRawSocket(ElectrumConfig(serverUrl=host, port=port, useSsl=true,
                    pinnedCert=pin?.let { Base64.getEncoder().encodeToString(bytes("$it.der")) })).use { socket ->
                    socket.soTimeout=5000; socket.getOutputStream().write("probe\n".toByteArray()); socket.getOutputStream().flush()
                    assertEquals("OK", socket.getInputStream().bufferedReader().readLine())
                }
            }
            if (accepted) result.getOrThrow() else assertTlsRejection("$name/$pin/tor=$tor", result.exceptionOrNull())
        } }
    }
    @Test fun pinnedExpectedLeafWorksDirectAndThroughSocks() { for (tor in listOf(false,true)) raw("valid","valid",true,tor) }
    @Test fun pinnedWrongLeafIsRejectedDirectAndThroughSocks() { for (tor in listOf(false,true)) raw("other","valid",false,tor) }
    @Test fun pinnedWrongHostnameIsRejectedDirectAndThroughSocks() { for (tor in listOf(false,true)) raw("wrong-host","wrong-host",false,tor) }
    @Test fun expiredPinnedLeafIsRejectedIncludingSelfSignedAnchor() {
        for (tor in listOf(false,true)) for (name in listOf("expired","expired-self")) raw(name,name,false,tor)
    }
    @Test fun platformTrustRejectsUnknownCaDirectAndThroughSocks() {
        // A broken/unsupported default manager must not masquerade as expected CA rejection.
        assertNotNull(PlatformTlsTrust.create("fixture.invalid", null))
        for (tor in listOf(false,true)) raw("unknown",null,false,tor)
    }
    @Test fun pinIsExactCertificateNotPermissionForAnotherLeaf() { for (tor in listOf(false,true)) raw("valid","root",false,tor) }
    @Test fun httpPlatformChainExpiryHostnameAndProxyDns() {
            // Same production wrapper, with standard Android trust restricted to this synthetic CA.
            val tls = provider(trustManager(cert("root")))
            for (tor in listOf(false,true)) for (name in listOf("valid","expired","wrong-host","unknown")) settings { settings ->
                Endpoint(name).use { endpoint -> route(settings, endpoint, tor) { host, port ->
                    val result=runCatching { TorAwareHttpClient(settings, tls).fetchText("https://$host:$port/",5000,5000) }
                    if (name=="valid") assertEquals("OK",result.getOrThrow())
                    else assertTlsRejection("HTTP $name/tor=$tor",result.exceptionOrNull())
                } }
            }
    }

    private fun exchange(settings: SettingsManager, tls: TlsSocketFactoryProvider, host: String,
                         port: Int, https: Boolean, pin: ByteArray? = null) {
        if (https) {
            assertEquals("OK", TorAwareHttpClient(settings, tls).fetchText("https://$host:$port/", 5000, 5000))
        } else {
            ElectrumConnectionFactory(settings, tls).createRawSocket(ElectrumConfig(
                serverUrl=host, port=port, useSsl=true,
                pinnedCert=pin?.let { Base64.getEncoder().encodeToString(it) },
            )).use { socket ->
                socket.soTimeout=5000
                socket.getOutputStream().write("probe\n".toByteArray()); socket.getOutputStream().flush()
                assertEquals("OK", socket.getInputStream().bufferedReader().readLine())
            }
        }
    }

    private fun rejectedBeforeApplicationData(endpoint: Endpoint, failure: Throwable?) {
        assertTlsRejection("distrusted validated path", failure)
        try {
            assertEquals("No decrypted HTTP or Electrum byte may precede rejection",
                -1, endpoint.firstApplicationByte.get(6, TimeUnit.SECONDS).toInt())
        } catch (e: java.util.concurrent.ExecutionException) {
            // Peer TLS-alert or reset is normal after the client's certificate rejection.
            // Do not accept an endpoint timeout, incomplete line, or arbitrary exception.
            val cause = e.cause
            assertTrue("Unexpected endpoint failure: $cause", cause is SSLException || cause is SocketException)
        }
    }

    @Test fun distrustRejectsLeafIntermediateAndOmittedAnchorBeforeApplicationData() {
        for (tor in listOf(false,true)) for (https in listOf(false,true)) {
            for (blocked in listOf(null, "path-leaf", "path-intermediate", "path-root")) settings { settings ->
                val policy = if (blocked == null) CertificateDistrust.EMSIGN
                    else CertificateDistrust(setOf(CertificateDistrust.publicKeySha256(cert(blocked))))
                val tls = provider(trustManager(cert("path-root")), policy)
                // The root is deliberately NOT supplied by the peer.
                Endpoint("path-leaf", listOf("path-intermediate")).use { endpoint -> route(settings, endpoint, tor) { host, port ->
                    val result = runCatching { exchange(settings, tls, host, port, https) }
                    if (blocked == null) result.getOrThrow()
                    else rejectedBeforeApplicationData(endpoint, result.exceptionOrNull())
                } }
            }
        }
    }

    @Test fun distrustRejectsReissuedAndCrossSignedIntermediateKeys() {
        val blocked = CertificateDistrust(setOf(CertificateDistrust.publicKeySha256(cert("path-intermediate"))))
        for (tor in listOf(false,true)) for (https in listOf(false,true)) {
            for (issuer in listOf("path-intermediate-reissued", "path-intermediate-cross")) {
                assertEquals(CertificateDistrust.publicKeySha256(cert("path-intermediate")), CertificateDistrust.publicKeySha256(cert(issuer)))
                assertFalse(cert("path-intermediate").encoded.contentEquals(cert(issuer).encoded))
                for (reject in listOf(false,true)) settings { settings ->
                    val tls = provider(trustManager(cert("path-root"), cert("path-other-root")),
                        if (reject) blocked else CertificateDistrust.EMSIGN)
                    Endpoint("path-leaf", listOf(issuer)).use { endpoint -> route(settings, endpoint, tor) { host, port ->
                        val result = runCatching { exchange(settings, tls, host, port, https) }
                        if (reject) rejectedBeforeApplicationData(endpoint, result.exceptionOrNull()) else result.getOrThrow()
                    } }
                }
            }
        }
    }

    @Test fun distrustRejectsCachedIntermediateOmittedByPeer() {
        for (tor in listOf(false,true)) for (https in listOf(false,true)) settings { settings ->
            val manager = trustManager(cert("path-root"))
            val normal = provider(manager)
            val blocked = provider(manager, CertificateDistrust(setOf(CertificateDistrust.publicKeySha256(cert("path-intermediate")))))
            // Prime Android's intermediate cache, then prove omission is normally accepted.
            for (issuers in listOf(listOf("path-intermediate"), emptyList())) {
                Endpoint("path-leaf", issuers).use { endpoint -> route(settings, endpoint, tor) { host, port ->
                    exchange(settings, normal, host, port, https)
                } }
            }
            Endpoint("path-leaf", emptyList()).use { endpoint -> route(settings, endpoint, tor) { host, port ->
                val result = runCatching { exchange(settings, blocked, host, port, https) }
                rejectedBeforeApplicationData(endpoint, result.exceptionOrNull())
            } }
        }
    }

    @Test fun exactLeafPinCannotOverridePublicKeyDistrust() {
        for (tor in listOf(false,true)) settings { settings ->
            val tls = provider(trustManager(cert("root")), CertificateDistrust(setOf(CertificateDistrust.publicKeySha256(cert("valid")))))
            Endpoint("valid").use { endpoint -> route(settings, endpoint, tor) { host, port ->
                val result = runCatching { exchange(settings, tls, host, port, false, bytes("valid.der")) }
                rejectedBeforeApplicationData(endpoint, result.exceptionOrNull())
            } }
        }
    }

    @Test fun unavailableSocksCannotFallBackToDirectElectrum() = settings { settings ->
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { target ->
            val proxyPort = ServerSocket(0).use { it.localPort }
            settings.setTorEnabled(true)
            settings.setTorProxyHost("127.0.0.1")
            settings.setTorProxyPort(proxyPort)
            val failure = runCatching {
                ElectrumConnectionFactory(settings).createRawSocket(
                    ElectrumConfig(serverUrl="127.0.0.1", port=target.localPort, useSsl=false))
            }.exceptionOrNull()
            assertTrue(failure is ElectrumConnectionException.TorProxyUnavailable)
            target.soTimeout=300
            assertTrue(runCatching { target.accept().close() }.exceptionOrNull() is SocketTimeoutException)
        }
    }

    @Test fun offlineInterruptsTlsHandshakeAndFreshConnectionRecovers() = settings { settings ->
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { stalled ->
            val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
            val factory=ElectrumConnectionFactory(settings)
            try {
                val pending=executor.submit<Boolean> {
                    runCatching { factory.createRawSocket(ElectrumConfig(
                        serverUrl="127.0.0.1",port=stalled.localPort,useSsl=true)).close() }.isFailure
                }
                stalled.soTimeout=5000
                stalled.accept().use { peer ->
                    peer.soTimeout=5000
                    assertTrue("TLS ClientHello reached the fixture",peer.getInputStream().read()>=0)
                    settings.setOfflineMode(true)
                    assertTrue(pending.get(3,TimeUnit.SECONDS))
                }
                settings.setOfflineMode(false)
                Endpoint("valid").use { endpoint ->
                    factory.createRawSocket(ElectrumConfig(serverUrl="127.0.0.1",port=endpoint.server.localPort,
                        useSsl=true,pinnedCert=Base64.getEncoder().encodeToString(bytes("valid.der")))).use { socket ->
                        socket.soTimeout=5000
                        socket.getOutputStream().write("fresh\n".toByteArray());socket.getOutputStream().flush()
                        assertEquals("OK",socket.getInputStream().bufferedReader().readLine())
                    }
                }
            } finally { executor.shutdownNow() }
        }
    }
}
