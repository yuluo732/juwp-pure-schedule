package com.juwp.schedule.data.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/** 登录结果 */
sealed interface LoginResult {
    /** 登录成功，[studentId] 为学号 */
    data class Success(val studentId: String) : LoginResult

    /** 账号或密码错误（CAS 明确返回） */
    data object BadCredentials : LoginResult

    /** 网络/服务器异常 */
    data class Failure(val message: String, val cause: Throwable? = null) : LoginResult
}

/**
 * 统一身份认证（Apereo CAS）客户端。
 *
 * 实测要点：
 *  - 登录页 https://eapp2.juwp.edu.cn:9443/cas/login?service=<urlencoded>
 *  - 表单字段：username / password / execution / _eventId=submit
 *  - 隐藏域 execution 每次不同，**必须现场解析**
 *  - 页面里 `var encrypt = "false"` → 密码明文提交（若将来变 true 需要改成 AES-ECB，
 *    代码里已留出 [encryptPasswordIfNeeded] 钩子）
 *  - 成功返回 302 + Location，并下发 TGC Cookie
 *  - 失败返回 200 且仍是登录表单（页面含 name="execution"）
 */
class CasClient(private val client: OkHttpClient) {

    suspend fun login(username: String, password: String, service: String): LoginResult =
        withContext(Dispatchers.IO) {
            try {
                val loginUrl = buildLoginUrl(service)
                val page = get(loginUrl)
                    ?: return@withContext LoginResult.Failure("无法访问统一身份认证，请检查网络")

                val execution = JwUrls.EXECUTION_REGEX.find(page)
                    ?.groupValues?.get(1)
                    ?: return@withContext LoginResult.Failure(
                        "未能解析 CAS 登录页（execution 缺失），认证系统可能已改版"
                    )

                val body = FormBody.Builder()
                    .add("username", username)
                    .add("password", encryptPasswordIfNeeded(password, page))
                    .add("execution", execution)
                    .add("_eventId", "submit")
                    .add("geolocation", "")
                    .build()

                val request = Request.Builder()
                    .url(loginUrl)
                    .header("User-Agent", Http.UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Origin", JwUrls.CAS_BASE)
                    .header("Referer", loginUrl)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val location = response.header("Location")
                    val text = response.body?.string().orEmpty()
                    Log.i(TAG, "CAS 登录 status=${response.code} location=$location")

                    when {
                        // 成功：302 到 service（带 ticket）
                        response.code in 300..399 && location != null ->
                            LoginResult.Success(username)

                        // 失败：仍是登录表单
                        text.contains("name=\"execution\"") || text.contains("name=execution") -> {
                            val msg = extractError(text)
                            if (msg != null) {
                                Log.w(TAG, "CAS 错误提示: $msg")
                                LoginResult.BadCredentials
                            } else {
                                LoginResult.Failure("登录失败：认证系统未返回票据")
                            }
                        }

                        else -> LoginResult.Failure("登录失败（HTTP ${response.code}）")
                    }
                }
            } catch (e: IOException) {
                LoginResult.Failure("网络异常：${e.message}", e)
            } catch (e: Exception) {
                LoginResult.Failure("登录异常：${e.message}", e)
            }
        }

    fun buildLoginUrl(service: String): String =
        "${JwUrls.CAS_BASE}/cas/login?service=${URLEncoder.encode(service, "UTF-8")}"

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.UA)
            .header("Accept", "text/html,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .get()
            .build()
        return client.newCall(request).execute().use { it.body?.string() }
    }

    /** 从 CAS 错误页里抠出提示文案 */
    private fun extractError(html: String): String? {
        // 注意：firstNotNullOfOrNull 是内联函数，必须显式标注返回类型，
        // 否则编译器会报 "Missing return statement"。
        return ERROR_PATTERNS.firstNotNullOfOrNull { regex ->
            regex.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * 目前站点 `var encrypt = "false"`，密码明文提交。
     * 若将来改成 true（页面会用 CryptoJS.AES ECB + Pkcs7 加密），在这里补实现，
     * 并同步解析页面里的 key。
     */
    private fun encryptPasswordIfNeeded(password: String, page: String): String {
        val encryptFlag = Regex("""var\s+encrypt\s*=\s*"([^"]*)"""").find(page)?.groupValues?.get(1)
        if (encryptFlag == "true") {
            Log.w(TAG, "站点已启用密码前端加密，当前实现未支持，将按明文提交")
        }
        return password
    }

    private companion object {
        const val TAG = "CasClient"
        val ERROR_PATTERNS = listOf(
            Regex("""id="msg"[^>]*>([^<]+)<"""),
            Regex("""class="[^"]*errors[^"]*"[^>]*>([\s\S]{0,200}?)<"""),
            Regex("""id="errorDiv"[^>]*>([\s\S]{0,200}?)<"""),
        )
    }
}
