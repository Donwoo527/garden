package me.chen.laidian

import android.content.Context
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** 只信任打包进来的 VPS 自签证书（res/raw/vps_cert.pem），别的谁都不信。 */
object Tls {
    // 0.25 学TG的活法：先走梯子的本地SOCKS口(v2rayNG默认10808)，梯子没开自动回退直连。
    // 直连在她的网络环境回程被掐(0910实测)，梯子隧道是实践中唯一稳的通路——跟TG一条命。
    private val proxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808)),
            Proxy.NO_PROXY,
        )
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
    }

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
            .proxySelector(proxySelector)
            .pingInterval(25, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
