package com.juwp.schedule.data.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder

/** SSO 链路执行结果 */
data class SsoResult(
    val ok: Boolean,
    val finalUrl: String,
    val hops: List<String>,
    val message: String? = null,
)

/**
 * 教务系统（jsxsd）客户端。
 *
 * 负责：
 *  1. 走 SSO 链路建立 jsxsd 会话
 *  2. 校验会话是否有效
 *  3. 抓取课表 HTML
 */
class JwClient(
    private val client: OkHttpClient,
    private val cookieJar: PersistentCookieJar,
) {

    /**
     * SSO 链路（实测 4 跳）：
     *   1. GET  https://jiaowu.juwp.edu.cn:81/sso.jsp
     *           → 302 https://eapp2.juwp.edu.cn:9443/cas/login?service=<SSO_SERVICE>
     *      （该响应同时下发 cookie: bzb_njw）
     *   2. CAS 登录（由 [CasClient] 完成）→ 302 http://jiaowu.juwp.edu.cn/sso.jsp?ticket=ST-xxx
     *   3. GET  http://jiaowu.juwp.edu.cn/sso.jsp?ticket=... → 302 /sso.jsp（服务端校验票据）
     *   4. GET  http://jiaowu.juwp.edu.cn/sso.jsp → 302 :8080/jsxsd/xk/LoginToXk?method=jwxt&ticket1=...
     *   5. GET  ...LoginToXk?... → 302 :8080/jsxsd/framework/xsMainV.htmlx（到这里会话建立）
     *
     * 这里从第 3 步开始手动逐跳跟随，避免 OkHttp 在 HTTP→HTTPS 跨协议跳转时丢 Cookie。
     */
    suspend fun followSso(ticketLocation: String): SsoResult = withContext(Dispatchers.IO) {
        val hops = mutableListOf<String>()
        var current = ticketLocation
        try {
            for (hop in 0 until MAX_HOPS) {
                val response = execute(current)
                val next = response.header("Location")
                hops.add("[$hop] ${response.code} $current" + if (next != null) " → $next" else "")
                response.close()

                if (next.isNullOrBlank()) {
                    val ok = current.contains("/jsxsd/framework/") || current.contains("xsMainV")
                    return@withContext SsoResult(ok, current, hops)
                }
                current = resolve(current, next)
            }
            SsoResult(false, current, hops, "SSO 跳转次数过多（超过 $MAX_HOPS 跳），可能存在重定向循环")
        } catch (e: IOException) {
            SsoResult(false, current, hops, "SSO 过程网络异常：${e.message}")
        }
    }

    /** 会话是否仍然有效 */
    suspend fun isSessionAlive(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val html = get(JwUrls.JW_HOME)
            html != null && !html.contains(JwUrls.LOGIN_PAGE_MARKER)
        }.getOrDefault(false)
    }

    /** 抓取整学期课表 HTML（viweType=0 才是真正的表格，外层只是 iframe 壳） */
    suspend fun fetchScheduleHtml(termId: String? = null): String = withContext(Dispatchers.IO) {
        val referer = JwUrls.KB_SHELL
        val html = if (termId.isNullOrBlank()) {
            get(JwUrls.KB_DATA, referer)
        } else {
            postForm(JwUrls.KB_DATA, referer, mapOf(JwUrls.TERM_PARAM to termId))
        } ?: throw IOException("课表页面返回为空")

        if (html.contains(JwUrls.LOGIN_PAGE_MARKER)) {
            throw SessionExpiredException()
        }
        html
    }

    /** 拉取首页 HTML（用于取学号、姓名等） */
    suspend fun fetchHomeHtml(): String? = withContext(Dispatchers.IO) { get(JwUrls.JW_HOME) }

    // ------------------------------------------------------------------ 基础

    private fun execute(url: String): Response {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .get()
            .build()
        return client.newCall(request).execute()
    }

    private fun get(url: String, referer: String? = null): String? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", Http.UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .get()
        if (referer != null) builder.header("Referer", referer)
        return client.newCall(builder.build()).execute().use { response ->
            Log.d(TAG, "GET $url -> ${response.code} (${response.body?.contentLength() ?: -1})")
            response.body?.string()
        }
    }

    private fun postForm(url: String, referer: String?, params: Map<String, String>): String? {
        val body = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.UA)
            .header("Accept", "text/html,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", referer ?: url)
            .post(body)
            .build()
        return client.newCall(request).execute().use { response ->
            Log.d(TAG, "POST $url -> ${response.code}")
            response.body?.string()
        }
    }

    /** 相对 Location 补全成绝对地址 */
    private fun resolve(base: String, location: String): String {
        if (location.startsWith("http://") || location.startsWith("https://")) return location
        val schemeEnd = base.indexOf("://")
        val scheme = base.substring(0, schemeEnd)
        val rest = base.substring(schemeEnd + 3)
        val hostEnd = rest.indexOf('/')
        val authority = if (hostEnd >= 0) rest.substring(0, hostEnd) else rest
        return if (location.startsWith("/")) "$scheme://$authority$location"
        else "$scheme://$authority/$location"
    }

    /** URL 编码（给需要手动拼接的场景用） */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val TAG = "JwClient"
        const val MAX_HOPS = 12
    }
}

/** 会话失效（Cookie 过期或被踢） */
class SessionExpiredException : IOException("教务系统会话已失效，请重新登录")
