package net.clench.wallet.data.network

import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/** Additional rejection only; ordinary certificate validation is never replaced. */
internal class CertificateDistrust(private val publicKeyHashes: Set<String>) {
    fun check(validatedChain: List<X509Certificate>) {
        if (validatedChain.isEmpty()) throw CertificateException("Missing validated server certificate path")
        if (validatedChain.any { publicKeySha256(it) in publicKeyHashes }) {
            throw CertificateException("Server certificate path contains a distrusted public key")
        }
    }

    companion object {
        // SHA-256 of complete DER SubjectPublicKeyInfo, not certificate fingerprints.
        // Public source bytes and encoding verification: docs/security/upstream/emsign-2026-10/.
        val EMSIGN = CertificateDistrust(setOf(
            "ebed6475c2c2d80fcb199f3fe8dd6e6b3ce60b77eb15d4e42c3dec0c6f5b5105",
            "49dd353dc2b919a3770645117d0e60c16115e7d1bc259eefe734b00ea434fe5f",
            "e0254c7fcdc265e2f0f8a428dabb75ab4ae039b4872d62f5ee57f0877d5a8a41",
            "5c0660a2b164910d8ff589c18e14552e9e0ec237dcefe2cb736d41a4811f81a0",
            "43bffa19d6ef651451f6841e019931e96f6f2eaeff28ab4b6f1ce4576de2717b",
            "63ecefee431d49523bc1136c4e72d0206a477086caf606cd5cf025686c109cf8",
            "ec4ffa1ad0ac88081314b7aaffef6a91f97cd57291bb5ba686c5415f5eb4f6b6",
            "92c063d67e8f14db71d288e11266ffdaf69cbf115f7bab14f3bc2f68d25e0e30",
        ))

        fun publicKeySha256(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/**
 * Keep each platform validation overload intact, including socket/engine hostname
 * and algorithm checks. Then inspect the full validated path, which can contain
 * cached intermediates and an anchor omitted from the peer's certificate message.
 */
internal class DistrustingTrustManager(
    private val delegate: X509ExtendedTrustManager,
    private val host: String,
    private val validatedPath: (Array<X509Certificate>, String, String) -> List<X509Certificate>,
    private val distrust: CertificateDistrust,
) : X509ExtendedTrustManager() {
    init { require(host.isNotBlank()) { "Missing TLS hostname" } }

    private fun checkPath(chain: Array<X509Certificate>, authType: String) {
        distrust.check(validatedPath(chain, authType, host))
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        delegate.checkServerTrusted(chain, authType)
        checkPath(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {
        delegate.checkServerTrusted(chain, authType, socket)
        checkPath(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {
        delegate.checkServerTrusted(chain, authType, engine)
        checkPath(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
        delegate.checkClientTrusted(chain, authType, socket)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
        delegate.checkClientTrusted(chain, authType, engine)
}
