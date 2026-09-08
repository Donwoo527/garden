package me.chen.laidian

import android.content.Context
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** 只信任打包进来的 VPS 自签证书（res/raw/vps_cert.pem），别的谁都不信。 */
object Tls {
    fun client(context: Context): OkHttpClient {
        val cf = CertificateFactory.getInstance("X.509")
        val ca = context.resources.openRawResource(R.raw.vps_cert).use { cf.generateCertificate(it) }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("vps", ca)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val tm = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            // 证书 CN 是 IP，没有 SAN，标准校验会挂；只认我们自己的地址
            .hostnameVerifier { host, _ -> host == BuildConfig.SERVER_HOST }
            .pingInterval(25, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
