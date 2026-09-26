package com.readcapsule

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极简 HTTP 客户端。基于平台 [HttpURLConnection]，零第三方依赖。
 *
 * 为何不用 OkHttp：本工程请求特征为「低频、串行、无连接复用收益」
 * （一次总结 = nav + player + subtitle 共 3 次请求，间隔数秒）。
 * OkHttp 的 1.1MB 体积、依赖树与混淆配置，换来的连接池与拦截器在这里无收益。
 *
 * 关键约束：
 *  - Cookie 仅在此模块注入，且 **不写日志**（凭据不外泄）
 *  - 全链路超时显式设置，失败即抛 [HttpError]，不做静默重试
 *  - 响应体大小上限，防止恶意/异常响应撑爆堆
 */
object Http {

    /** 单次响应体上限 8MB。字幕 JSON 最大约 2MB，留足余量。 */
    private const val MAX_BODY_BYTES = 8 * 1024 * 1024

    class HttpError(val code: Int, val detail: String) : Exception("HTTP $code: $detail")

    class NetworkError(cause: Throwable) : Exception(cause.message ?: "network error", cause)

    /**
     * GET 请求。
     *
     * @param url 完整 URL（含查询串，签名后已拼好）
     * @param cookie 可选 Cookie；**不为 null 时才会注入**
     * @return 响应体字符串
     * @throws HttpError 非 2xx
     * @throws NetworkError 连接/读取异常
     */
    fun get(url: String, cookie: String? = null): String {
        val conn = open(url, cookie)
        try {
            val code = conn.responseCode
            val body = readBody(conn, code)
            if (code !in 200..299) {
                // 截断错误体，避免把整个 HTML 错误页塞进日志
                throw HttpError(code, body.take(200))
            }
            return body
        } catch (e: HttpError) {
            throw e
        } catch (t: Throwable) {
            throw NetworkError(t)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * POST JSON 请求。
     * @param json 请求体（调用方负责构造合法 JSON）
     */
    fun postJson(url: String, json: String, headers: Map<String, String>): String {
        val conn = open(url, null).apply { requestMethod = "POST" }
        try {
            conn.doOutput = true
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val body = readBody(conn, code)
            if (code !in 200..299) throw HttpError(code, body.take(300))
            return body
        } catch (e: HttpError) {
            throw e
        } catch (t: Throwable) {
            throw NetworkError(t)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun open(url: String, cookie: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = Config.CONNECT_TIMEOUT_MS
        c.readTimeout = Config.READ_TIMEOUT_MS
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", Config.BILI_UA)
        c.setRequestProperty("Referer", Config.BILI_REFERER)
        c.setRequestProperty("Accept", "application/json, text/plain, */*")
        if (!cookie.isNullOrBlank()) {
            // 仅在此处出现凭据变量；下方 catch 块不输出该变量
            c.setRequestProperty("Cookie", cookie)
        }
        return c
    }

    /** 读取响应体。错误流与正常流都要读，否则连接无法复用。 */
    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: return ""
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
            val sb = StringBuilder(8192)
            val buf = CharArray(8192)
            var total = 0
            while (true) {
                val n = r.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_BODY_BYTES) break   // 硬上限：不信任服务端长度
                sb.append(buf, 0, n)
            }
            return sb.toString()
        }
    }
}
