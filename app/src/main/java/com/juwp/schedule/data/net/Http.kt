package com.juwp.schedule.data.net

import android.content.Context
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 可持久化的 CookieJar。
 *
 * 这个教务系统一段登录流程横跨 4 个域：
 *   eapp2.juwp.edu.cn:9443 (CAS: JSESSIONID, TGC)
 *   jiaowu.juwp.edu.cn:81  (bzb_njw)
 *   jiaowu.juwp.edu.cn:80  (sso.jsp)
 *   jiaowu.juwp.edu.cn:8080(jsxsd: bzb_jsxsd)
 *
 * 所以必须按 host 分别保存，并且**跨进程重启后仍有效**，否则每次冷启动都要重新走一遍
 * CAS + SSO（既慢又可能触发风控）。这里把 Cookie 序列化到 app 私有目录。
 */
class PersistentCookieJar(context: Context) : CookieJar {

    private val storeFile = File(context.filesDir, "jw_cookies.bin")
    private val cache = ConcurrentHashMap<String, MutableList<Cookie>>()

    init {
        load()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val key = key(url)
        val list = cache.getOrPut(key) { mutableListOf() }
        for (cookie in cookies) {
            list.removeAll { it.name == cookie.name }
            // 只保留未过期的
            if (cookie.expiresAt > System.currentTimeMillis()) {
                list.add(cookie)
            }
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "save ${url.host}:${url.port} -> ${cookies.joinToString { it.name }}")
        }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val result = mutableListOf<Cookie>()
        // OkHttp 的 Cookie.matches 会检查 domain/path/secure，所以这里用全量过滤而不是按 host 取
        for (list in cache.values) {
            val it = list.iterator()
            while (it.hasNext()) {
                val cookie = it.next()
                if (cookie.expiresAt <= now) {
                    it.remove()
                    continue
                }
                if (cookie.matches(url)) result.add(cookie)
            }
        }
        return result
    }

    /** 清空所有会话（退出登录 / 会话失效时调用） */
    @Synchronized
    fun clear() {
        cache.clear()
        persist()
    }

    /** 是否已经持有教务系统的会话 Cookie */
    @Synchronized
    fun hasJwSession(): Boolean = cache.values.flatten().any { it.name == COOKIE_JSXSD }

    @Synchronized
    fun snapshot(): Map<String, List<String>> =
        cache.mapValues { (_, v) -> v.map { "${it.name}@${it.domain}" } }

    private fun key(url: HttpUrl) = "${url.host}:${url.port}"

    // ------------------------------------------------------------ 持久化

    private fun persist() {
        runCatching {
            // ⚠️ 不能直接序列化 okhttp3.Cookie —— OkHttp 4 的 Cookie 是普通 Kotlin 类，
            // **不再实现 java.io.Serializable**（OkHttp 3 时代是可以的）。
            // 直接 writeObject 会抛 NotSerializableException，且被 runCatching 静默吞掉，
            // 表现为「每次冷启动都要重新登录」。所以这里转成自己的可序列化 DTO。
            val snapshot = cache.map { (key, cookies) ->
                key to cookies.map { it.toStored() }
            }
            ObjectOutputStream(storeFile.outputStream().buffered()).use { out ->
                out.writeObject(ArrayList(snapshot))
            }
            Log.d(TAG, "cookie 已持久化：${snapshot.sumOf { it.second.size }} 条")
        }.onFailure { Log.w(TAG, "cookie 持久化失败", it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun load() {
        if (!storeFile.exists()) return
        runCatching {
            ObjectInputStream(storeFile.inputStream().buffered()).use { input ->
                val data = input.readObject() as? List<Pair<String, List<StoredCookie>>> ?: return
                val now = System.currentTimeMillis()
                var restored = 0
                data.forEach { (k, v) ->
                    val alive = v.filter { it.expiresAt > now }.mapNotNull { it.toCookie() }
                    if (alive.isNotEmpty()) {
                        cache[k] = alive.toMutableList()
                        restored += alive.size
                    }
                }
                Log.d(TAG, "cookie 已恢复：$restored 条")
            }
        }.onFailure {
            Log.w(TAG, "cookie 读取失败，丢弃旧文件", it)
            storeFile.delete()
        }
    }

    private companion object {
        const val TAG = "JwCookieJar"
        const val COOKIE_JSXSD = "bzb_jsxsd"
    }
}

/**
 * Cookie 的可序列化快照。
 * OkHttp 的 Cookie 只需要这些字段就能完整重建。
 */
private data class StoredCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
) : Serializable

private fun Cookie.toStored(): StoredCookie = StoredCookie(
    name = name,
    value = value,
    expiresAt = expiresAt,
    domain = domain,
    path = path,
    secure = secure,
    httpOnly = httpOnly,
    hostOnly = hostOnly,
)

/** 反序列化后用 Builder 重建 Cookie；字段非法时返回 null，由调用方跳过 */
private fun StoredCookie.toCookie(): Cookie? = runCatching {
    Cookie.Builder()
        .name(name)
        .value(value)
        .expiresAt(expiresAt)
        .path(path)
        .apply {
            if (hostOnly) hostOnlyDomain(domain) else domain(domain)
            if (secure) secure()
            if (httpOnly) httpOnly()
        }
        .build()
}.getOrNull()

/** 便于把 Cookie 写到日志 */
internal fun Cookie.describe(): String = "$name=${value.take(12)}…(domain=$domain, path=$path)"

object Http {

    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    fun build(context: Context): Pair<OkHttpClient, PersistentCookieJar> {
        val jar = PersistentCookieJar(context)
        val client = OkHttpClient.Builder()
            .cookieJar(jar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)   // SSO 链路要逐跳观察，手动跟随
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
        return client to jar
    }
}
