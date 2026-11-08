package com.hippo.ehviewer.ktor

import com.ehviewer.core.util.isAtLeastQ
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.dns.DomainFrontingSslSocketFactory
import com.hippo.ehviewer.dns.PoisonProofDns
import io.ktor.client.engine.okhttp.OkHttpConfig
import okhttp3.AsyncDns
import okhttp3.android.AndroidAsyncDns
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

fun OkHttpConfig.configureClient() {
    config {
        // Poison-proof resolution: a built-in address table plus DNS-over-HTTPS,
        // both optional and both transparent for unrelated hosts. Replaces the
        // system resolver, which hands back poisoned addresses for the
        // E-Hentai domains on a censored network.
        if (Settings.builtInHosts.value || Settings.doh.value) {
            dns(PoisonProofDns())
        } else if (isAtLeastQ) {
            dns(AsyncDns.toDns(AndroidAsyncDns.IPv4, AndroidAsyncDns.IPv6))
        }

        // Domain fronting: connect by address so the ClientHello carries no SNI
        // for the E-Hentai domains, which is what the interceptor resets.
        if (Settings.domainFronting.value) {
            val trustManager = platformTrustManager()
            sslSocketFactory(DomainFrontingSslSocketFactory(trustManager), trustManager)
        }
    }
}

/**
 * The platform default X509TrustManager, required by the non-deprecated
 * OkHttp sslSocketFactory overload. Certificate validation is unchanged.
 */
internal fun platformTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}
