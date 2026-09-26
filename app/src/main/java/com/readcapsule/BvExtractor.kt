package com.readcapsule

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * BV 号提取器。
 *
 * 背景：无障碍拿不到剪贴板，也无法解析分享链接。唯一可行路径是读取节点树的
 * 文本 / contentDescription —— B站 App 的播放器区域会把 `BV1xx411c7xx` 写进
 * contentDescription，这是可用的稳定信号。
 *
 * 核心风险：节点树里同时存在**推荐流的其它视频**BV号。若简单取首个匹配，
 * 会造成「总结 A 视频却返回 B 视频摘要」——静默错误，产品级致命。
 *
 * 对策（三重约束）：
 *  1. 严格正则：BV + 10 位 base58 字符（B站实际格式，避免误匹配普通文本）
 *  2. 频次加权：出现次数最多的候选胜出（当前视频会被标题/分享/描述多处重复引用）
 *  3. 歧义声明：最高频次并列且候选数 > 1 时返回 Ambiguous，**不猜**
 */
object BvExtractor {

    /** B站 BV 号：固定 2 字符前缀 + 10 位 base58（无 0/O/I/l）。 */
    private val BV = Regex("""BV[1-9A-HJ-NP-Za-km-z]{10}""")

    sealed class Result {
        /** 唯一确定。 */
        data class Found(val bv: String) : Result()

        /**
         * 多个候选频次并列，无法判定当前视频。
         * 携带候选供 UI 展示，由用户决策 —— 绝不自作主张。
         */
        data class Ambiguous(val candidates: List<String>) : Result()

        /** 未命中。 */
        object NotFound : Result()
    }

    /**
     * 从文本集合中提取 BV 号。
     *
     * @param texts 已从节点树收集的文本（text + contentDescription）
     */
    fun extract(texts: List<String>): Result {
        if (texts.isEmpty()) return Result.NotFound

        // 频次统计。首次出现位置作为并列时的次序键，保证结果稳定可复现。
        val counts = HashMap<String, Int>(8)
        val firstSeen = HashMap<String, Int>(8)
        var order = 0

        for (t in texts) {
            for (m in BV.findAll(t)) {
                val bv = m.value
                counts[bv] = (counts[bv] ?: 0) + 1
                if (!firstSeen.containsKey(bv)) firstSeen[bv] = order++
            }
        }

        if (counts.isEmpty()) return Result.NotFound
        if (counts.size == 1) return Result.Found(counts.keys.first())

        val max = counts.values.max()
        val top = counts.filterValues { it == max }.keys
            .sortedBy { firstSeen[it] ?: Int.MAX_VALUE }

        // 并列即歧义。宁可让用户点一下，也不产出错误摘要。
        return if (top.size == 1) Result.Found(top.first()) else Result.Ambiguous(top.take(3))
    }

    /** 从单个 URL 中提取（备用路径：用户手动粘贴分享链接）。 */
    fun fromUrl(url: String): String? = BV.find(url)?.value
}

/**
 * B站 wbi 签名（纯函数，零依赖，可离线单测）。
 *
 * 背景：2023 年起 B站 `x/player/*` 系列接口要求 wbi 签名，缺失则返回 -403。
 * 签名流程（公开逆向结论，此处为独立实现）：
 *  1. 从 nav 接口取 img_url / sub_url，各抽 32 位 hex 拼接成 64 位 key
 *  2. 用固定乱序表重排，得到 mixin_key
 *  3. 参数按 key 排序 + 拼 mixin_key 取 MD5
 *
 * 为何独立成文件：这是全工程唯一「以算法正确性为生死线」的模块，
 * 必须能脱离 Android 环境直接单测（见 verify.sh Stage 7）。
 */
object Wbi {

    /** 官方固定重排表。顺序错了签名必然失败。 */
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    )

    /** 文件名/参数名中的特殊字符会被过滤，否则签名校验不通过。 */
    private val ILLEGAL = Regex("""[!'()*]""")

    /**
     * 由 img_url / sub_url 派生 mixin key。
     * @param imgUrl nav 接口返回的 wbi_img.img_url（形如 .../xxxx.png）
     * @param subUrl nav 接口返回的 wbi_img.sub_url
     */
    fun mixinKey(imgUrl: String, subUrl: String): String {
        val raw = basename(imgUrl) + basename(subUrl)
        require(raw.length >= 64) { "wbi source key too short: ${raw.length}" }
        val sb = StringBuilder(32)
        for (i in 0 until 32) sb.append(raw[MIXIN_KEY_ENC_TAB[i]])
        return sb.toString()
    }

    /** 从 URL 取不含扩展名的文件名。 */
    private fun basename(url: String): String {
        val seg = url.substringBefore('?').substringAfterLast('/')
        return seg.substringBeforeLast('.')
    }

    /**
     * 生成签名后的查询串。
     *
     * @param params 业务参数（不含 wts）
     * @param mixinKey [mixinKey] 产出
     * @param wts 时间戳，由调用方注入以便测试确定性
     */
    fun sign(params: Map<String, String>, mixinKey: String, wts: Long): String {
        // TreeMap：字典序排序即接口要求的 key 排序
        val sorted = TreeMap<String, String>()
        for ((k, v) in params) sorted[k] = ILLEGAL.replace(v, "")
        sorted["wts"] = wts.toString()

        val query = sorted.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
        return "$query&w_rid=${md5(query + mixinKey)}"
    }

    /** 签名参数需与最终 URL 中的编码形态一致，否则两端不一致。 */
    private fun encode(s: String): String =
        URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(32)
        for (b in d) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }
}

/**
 * 内部一致性自检。供 MainActivity「自检」按钮与 CI 复用。
 * 时间戳固定，因此结果可复现 —— 这是把算法模块从 Android 环境解耦的收益。
 */
object SelfTest {

    data class Check(val name: String, val pass: Boolean, val detail: String)

    fun run(): List<Check> {
        val out = ArrayList<Check>(6)

        // 1. BV 唯一命中
        out += v("BV 唯一命中") {
            val r = BvExtractor.extract(listOf("【4K】BV1Nx411c7xx 深度评测", "分享 BV1Nx411c7xx"))
            r is BvExtractor.Result.Found && r.bv == "BV1Nx411c7xx"
        }
        // 2. BV 歧义必须显式失败，而非取首个
        out += v("BV 歧义显式声明") {
            BvExtractor.extract(listOf("BV1Nx411c7xx", "BV1aaaaaaaaa")) is BvExtractor.Result.Ambiguous
        }
        // 3. BV 非法长度不匹配
        out += v("BV 长度校验") {
            BvExtractor.extract(listOf("BV1Nx411c7x")) is BvExtractor.Result.NotFound
        }
        // 4. wbi mixin key 长度恒为 32
        out += v("wbi mixinKey 长度") {
            val k = Wbi.mixinKey(
                "https://i0.hdslb.com/bfs/wbi/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",
                "https://i0.hdslb.com/bfs/wbi/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png"
            )
            k.length == 32
        }
        // 5. wbi 签名确定性：同输入必同输出
        out += v("wbi 签名确定性") {
            val k = Wbi.mixinKey(
                "https://i0.hdslb.com/bfs/wbi/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",
                "https://i0.hdslb.com/bfs/wbi/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png"
            )
            val a = Wbi.sign(mapOf("bvid" to "BV1Nx411c7xx"), k, 1700000000L)
            val b = Wbi.sign(mapOf("bvid" to "BV1Nx411c7xx"), k, 1700000000L)
            a == b && a.contains("w_rid=") && a.contains("wts=1700000000")
        }
        // 6. 非法字符被过滤后签名仍稳定
        out += v("wbi 非法字符过滤") {
            val k = Wbi.mixinKey(
                "https://i0.hdslb.com/bfs/wbi/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",
                "https://i0.hdslb.com/bfs/wbi/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png"
            )
            Wbi.sign(mapOf("x" to "a!b'c"), k, 1L) == Wbi.sign(mapOf("x" to "abc"), k, 1L)
        }

        return out
    }

    private inline fun v(name: String, body: () -> Boolean): Check = try {
        val ok = body()
        Check(name, ok, if (ok) "ok" else "assertion failed")
    } catch (t: Throwable) {
        Check(name, false, t.message ?: t.javaClass.simpleName)
    }
}
