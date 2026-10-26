package net.clench.wallet.data.network

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

class CertificateDistrustTest {
    private fun certificate(bytes: ByteArray): X509Certificate = mockk<X509Certificate>().also { cert ->
        every { cert.publicKey } returns KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    private fun freshCertificate(): X509Certificate = certificate(
        KeyPairGenerator.getInstance("EC").apply { initialize(384) }.generateKeyPair().public.encoded)

    @Test fun `all eight independently retained public keys are distrusted`() {
        val files = File("../docs/security/upstream/emsign-2026-10").listFiles { f -> f.name.endsWith(".spki.der") }!!
        assertEquals(8, files.size)
        for (file in files) assertThrows(CertificateException::class.java) {
            CertificateDistrust.EMSIGN.check(listOf(certificate(file.readBytes())))
        }
        CertificateDistrust.EMSIGN.check(listOf(freshCertificate()))
    }

    @Test fun `certificate reissue does not evade public key identity`() {
        val bytes = File("../docs/security/upstream/emsign-2026-10/EMDVTLSCAG3B.spki.der").readBytes()
        for (cert in listOf(certificate(bytes), certificate(bytes))) {
            // These distinct certificate objects share only the compromised public key.
            assertThrows(CertificateException::class.java) { CertificateDistrust.EMSIGN.check(listOf(cert)) }
        }
    }

    private class RecordingDelegate(private val cert: X509Certificate) : X509ExtendedTrustManager() {
        var calls = mutableListOf<String>()
        var failure: CertificateException? = null
        private fun record(name: String) { calls += name; failure?.let { throw it } }
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = record("server/basic")
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String, s: Socket) = record("server/socket")
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String, e: SSLEngine) = record("server/engine")
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = record("client/basic")
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String, s: Socket) = record("client/socket")
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String, e: SSLEngine) = record("client/engine")
        override fun getAcceptedIssuers() = arrayOf(cert)
    }

    @Test fun `every server overload keeps delegate validation and checks completed path`() {
        val leaf = freshCertificate()
        val cachedIntermediate = freshCertificate()
        val delegate = RecordingDelegate(leaf)
        val policy = CertificateDistrust(setOf(CertificateDistrust.publicKeySha256(cachedIntermediate)))
        var pathCalls = 0
        val manager = DistrustingTrustManager(delegate, "expected.invalid", { peer, auth, host ->
            assertEquals(1, peer.size); assertSame(leaf, peer.single())
            assertEquals("RSA", auth); assertEquals("expected.invalid", host)
            assertEquals(pathCalls + 1, delegate.calls.size) // Original validation happened first.
            pathCalls++
            listOf(leaf, cachedIntermediate)
        }, policy)
        val peer = arrayOf(leaf)
        Socket().use { socket ->
            val engine = SSLContext.getDefault().createSSLEngine()
            assertThrows(CertificateException::class.java) { manager.checkServerTrusted(peer, "RSA") }
            assertThrows(CertificateException::class.java) { manager.checkServerTrusted(peer, "RSA", socket) }
            assertThrows(CertificateException::class.java) { manager.checkServerTrusted(peer, "RSA", engine) }
        }
        assertEquals(listOf("server/basic", "server/socket", "server/engine"), delegate.calls)
        assertEquals(3, pathCalls)
    }

    @Test fun `normal validation and full path resolution failures never become success`() {
        val cert = freshCertificate()
        val delegate = RecordingDelegate(cert)
        val original = CertificateException("Original platform rejection")
        delegate.failure = original
        val manager = DistrustingTrustManager(delegate, "expected.invalid", { _, _, _ ->
            fail("Do not bypass the failed platform check"); emptyList()
        }, CertificateDistrust.EMSIGN)
        Socket().use { socket ->
            val engine = SSLContext.getDefault().createSSLEngine()
            assertSame(original, assertThrows(CertificateException::class.java) { manager.checkServerTrusted(arrayOf(cert), "RSA") })
            assertSame(original, assertThrows(CertificateException::class.java) { manager.checkServerTrusted(arrayOf(cert), "RSA", socket) })
            assertSame(original, assertThrows(CertificateException::class.java) { manager.checkServerTrusted(arrayOf(cert), "RSA", engine) })
        }
        delegate.failure = null
        val missingPath = DistrustingTrustManager(delegate, "expected.invalid", { _, _, _ -> emptyList() }, CertificateDistrust.EMSIGN)
        assertThrows(CertificateException::class.java) { missingPath.checkServerTrusted(arrayOf(cert), "RSA") }
        val failedPath = DistrustingTrustManager(delegate, "expected.invalid", { _, _, _ -> throw original }, CertificateDistrust.EMSIGN)
        assertSame(original, assertThrows(CertificateException::class.java) { failedPath.checkServerTrusted(arrayOf(cert), "RSA") })
    }

    @Test fun `valid unrelated paths and original client validation stay usable`() {
        val cert = freshCertificate()
        val delegate = RecordingDelegate(cert)
        var pathCalls = 0
        val manager = DistrustingTrustManager(delegate, "expected.invalid", { _, _, _ -> pathCalls++; listOf(cert) }, CertificateDistrust.EMSIGN)
        val peer = arrayOf(cert)
        Socket().use { socket ->
            val engine = SSLContext.getDefault().createSSLEngine()
            manager.checkServerTrusted(peer, "RSA")
            manager.checkServerTrusted(peer, "RSA", socket)
            manager.checkServerTrusted(peer, "RSA", engine)
            manager.checkClientTrusted(peer, "RSA")
            manager.checkClientTrusted(peer, "RSA", socket)
            manager.checkClientTrusted(peer, "RSA", engine)
        }
        assertEquals(3, pathCalls)
        assertEquals(listOf("server/basic", "server/socket", "server/engine", "client/basic", "client/socket", "client/engine"), delegate.calls)
        assertArrayEquals(delegate.acceptedIssuers, manager.acceptedIssuers)
    }
}
