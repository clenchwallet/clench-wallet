package net.clench.wallet.data.network

import android.net.http.X509TrustManagerExtensions
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/** Internal fixture seam; production constructors always select PlatformTlsTrust. */
internal fun interface TlsSocketFactoryProvider {
    fun create(host: String, pinnedCertDer: ByteArray?): SSLSocketFactory
}

internal object PlatformTlsTrust : TlsSocketFactoryProvider {
    override fun create(host: String, pinnedCertDer: ByteArray?): SSLSocketFactory {
        val pinnedStore = pinnedCertDer?.let { der ->
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()) as X509Certificate
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("pinned", certificate)
            }
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(pinnedStore) // null preserves the platform/app's normal trust configuration.
        val delegate = factory.trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
            as? X509ExtendedTrustManager
            ?: throw CertificateException("Platform TLS trust manager is unsupported")
        return distrustingSocketFactory(host, delegate)
    }
}

internal fun distrustingSocketFactory(
    host: String,
    delegate: X509ExtendedTrustManager,
    distrust: CertificateDistrust = CertificateDistrust.EMSIGN,
): SSLSocketFactory {
    val extensions = X509TrustManagerExtensions(delegate)
    val manager = DistrustingTrustManager(delegate, host, { chain, authType, hostname ->
        extensions.checkServerTrusted(chain, authType, hostname)
    }, distrust)
    return SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(manager), null)
    }.socketFactory
}
