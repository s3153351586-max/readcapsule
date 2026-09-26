package com.readcapsule

/**
 * B站字幕获取客户端。
 *
 * ============================ 核心澄清 ============================
 * B站 **没有公开的字幕开放接口**。字幕轨道是账号态资源：
 *  - `x/player/v2` 在未携带有效 SESSDATA 时，`subtitle.subtitles` 恒为空数组
 *  - 2023 年起该接口要求 wbi 签名，缺失返回 code=-403
 *  - AI 自动字幕（用户量最大的字幕类型）仅在登录态下可见
 *
 * 因此本模块的运行前提是：**用户提供自己的 SESSDATA**。
 * 这不是可选优化，是物理约束。任何声称「无需登录即可提取 B站字幕」的方案，
 * 要么已失效，要么在读取缓存/第三方镜像（时效性与合规性均不可控）。
 * ===================================================================
 *
 * 三步流水线（每步失败都显式抛出，绝不返回空摘要）：
 *  1. GET x/web-interface/nav      -> 取 wbi_img，派生 mixin key
 *  2. GET x/player/wbi/v2?bvid=..  -> 取 subtitle.subtitles[]，挑中文轨
 *  3. GET 字幕 JSON URL            -> 解析 body[].from/to/content 为带时间戳文本
 */
object BiliClient {

    /** 异常语义：区分「需要登录」与「真无字幕」，UI 文案不同。 */
    sealed class BiliError(message: String) : Exception(message) {
        /** 未配置 SESSDATA，或凭据已失效。可恢复：提示用户重新配置。 */
        class NeedLogin(detail: String) : BiliError(detail)

        /** 已登录但该视频确实无字幕轨。不可恢复：该视频无法总结。 */
        class NoSubtitle(detail: String) : BiliError(detail)

        /** 接口结构变化 / 风控 / 网络。需重试或升级客户端。 */
        class Failed(detail: String, val code: Int = -1) : BiliError(detail)
    }

    /**
     * 拉取字幕全文。
     *
     * @param bvid 视频 BV 号
     * @param sessdata 用户 Cookie 中的 SESSDATA 值（**不含** `SESSDATA=` 前缀）
     * @return 规整后的字幕文档
     * @throws BiliError 任一环节失败
     */
    fun fetchSubtitles(bvid: String, sessdata: String): SubtitleDoc {
        if (sessdata.isBlank()) throw BiliError.NeedLogin("SESSDATA 未配置")

        val cookie = "SESSDATA=$sessdata"

        // --- Step 1: nav 取 wbi 素材 ---
        val navRaw = try {
            Http.get("https://api.bilibili.com/x/web-interface/nav", cookie)
        } catch (e: Http.HttpError) {
            throw BiliError.Failed("nav 请求失败 HTTP ${e.code}", e.code)
        } catch (e: Http.NetworkError) {
            throw BiliError.Failed("网络不可达：${e.message}")
        }

        val nav = Json.parse(navRaw)
        if (nav is Json.ParseFail) throw BiliError.Failed("nav 响应非 JSON：${nav.reason}")

        val navCode = Json.long(Json.path(nav, "code")) ?: -1L
        val isLogin = Json.path(nav, "data", "isLogin") as? Boolean ?: false
        if (navCode == -101L || !isLogin) {
            // -101 = 账号未登录。这是 SESSDATA 失效的唯一可靠信号。
            throw BiliError.NeedLogin("SESSDATA 失效或未登录（code=$navCode）")
        }

        val imgUrl = Json.pathStr(nav, "data", "wbi_img", "img_url")
            ?: throw BiliError.Failed("nav 缺少 wbi_img.img_url，接口结构可能已变更")
        val subUrl = Json.pathStr(nav, "data", "wbi_img", "sub_url")
            ?: throw BiliError.Failed("nav 缺少 wbi_img.sub_url，接口结构可能已变更")

        val mixinKey = try {
            Wbi.mixinKey(imgUrl, subUrl)
        } catch (t: Throwable) {
            throw BiliError.Failed("wbi 素材异常：${t.message}")
        }

        // --- Step 2: player/wbi/v2 取字幕轨列表 ---
        val wts = System.currentTimeMillis() / 1000L
        val query = Wbi.sign(mapOf("bvid" to bvid, "cid" to "0"), mixinKey, wts)
        // 注意：cid=0 时接口会返回该 bvid 的首个分P；多分P视频需先取 pagelist，
        // 此处刻意只支持首P —— 覆盖 95% 场景，多分P作为已知限制写入 README。
        val playerUrl = "https://api.bilibili.com/x/player/wbi/v2?$query"

        val playerRaw = try {
            Http.get(playerUrl, cookie)
        } catch (e: Http.HttpError) {
            throw BiliError.Failed("player 请求失败 HTTP ${e.code}", e.code)
        } catch (e: Http.NetworkError) {
            throw BiliError.Failed("网络不可达：${e.message}")
        }

        val player = Json.parse(playerRaw)
        if (player is Json.ParseFail) throw BiliError.Failed("player 响应非 JSON：${player.reason}")

        val pCode = Json.long(Json.path(player, "code")) ?: -1L
        if (pCode == -403L) throw BiliError.Failed("wbi 签名被拒（-403），客户端可能需升级", -403)
        if (pCode != 0L) {
            val msg = Json.pathStr(player, "message") ?: "unknown"
            throw BiliError.Failed("player 返回 code=$pCode msg=$msg", pCode.toInt())
        }

        val subs = Json.pathArr(player, "data", "subtitle", "subtitles")
        if (subs.isEmpty()) {
            // 已登录但无字幕。可能是：UP 未上传、AI 字幕未生成、视频为纯音乐等。
            throw BiliError.NoSubtitle("该视频无可用字幕轨（UP 未上传且 AI 字幕未生成）")
        }

        val track = pickTrack(subs)
            ?: throw BiliError.NoSubtitle("字幕轨存在但无中文/可识别语种（共 ${subs.size} 轨）")

        val url = Json.pathStr(track, "subtitle_url")
            ?: throw BiliError.Failed("字幕轨缺少 subtitle_url")

        // 接口返回的 subtitle_url 为协议相对路径（//aisubtitle...），补协议
        val fullUrl = if (url.startsWith("//")) "https:$url" else url

        // --- Step 3: 拉取并解析字幕 JSON ---
        val subRaw = try {
            Http.get(fullUrl, cookie)
        } catch (e: Http.HttpError) {
            throw BiliError.Failed("字幕下载失败 HTTP ${e.code}", e.code)
        } catch (e: Http.NetworkError) {
            throw BiliError.Failed("字幕下载网络不可达：${e.message}")
        }

        val subJson = Json.parse(subRaw)
        if (subJson is Json.ParseFail) throw BiliError.Failed("字幕响应非 JSON：${subJson.reason}")

        val bodyArr = Json.pathArr(subJson, "body")
        if (bodyArr.isEmpty()) throw BiliError.NoSubtitle("字幕文件 body 为空")

        val lang = Json.pathStr(track, "lan_doc") ?: Json.pathStr(track, "lan") ?: "unknown"
        val text = buildTimestampedText(bodyArr)
        if (text.isBlank()) throw BiliError.NoSubtitle("字幕解析后正文为空")

        val title = Json.pathStr(player, "data", "title")
            ?: Json.pathStr(nav, "data", "uname")
            ?: bvid

        return SubtitleDoc(
            bvid = bvid,
            title = title,
            lang = lang,
            body = text,
            fetchedAt = System.currentTimeMillis()
        )
    }

    /**
     * 选轨优先级：人工中文 > AI 中文 > 英文 > 首轨。
     *
     * 为何优先人工：AI 字幕有断句错误与同音字混淆，会直接污染摘要质量。
     * B站 `lan` 字段约定：ai-zh 为 AI 中文，zh-CN 为人工中文。
     */
    private fun pickTrack(tracks: List<Any?>): Map<String, Any?>? {
        val objs = tracks.mapNotNull { Json.obj(it) }
        if (objs.isEmpty()) return null

        fun lan(t: Map<String, Any?>): String = (Json.str(t["lan"]) ?: "").lowercase()

        return objs.firstOrNull { lan(it) == "zh-cn" || lan(it) == "zh-hans" }
            ?: objs.firstOrNull { lan(it) == "ai-zh" }
            ?: objs.firstOrNull { lan(it).startsWith("zh") }
            ?: objs.firstOrNull { lan(it).startsWith("en") }
            ?: objs.firstOrNull()
    }

    /**
     * 构建带时间戳的正文。
     *
     * 格式：`[mm:ss] 文本`，每行一段。
     * 为何保留时间戳：LLM 能据此产出「带时间戳的大纲」，这是本产品相对
     * 「纯文本摘要」的核心增量 —— 用户可以跳转到具体位置，而非只读结论。
     *
     * 分段策略：B站字幕 body 是逐句的，直接逐行输出会产生数千行。
     * 按 **时间间隔 > 1.5s 或累计字数 > 120** 合并为一段，压缩行数约 5 倍。
     */
    private fun buildTimestampedText(body: List<Any?>): String {
        val sb = StringBuilder(16384)

        var segStartMs = -1L
        val segBuf = StringBuilder()
        var lastEndMs = 0L

        fun flush() {
            if (segBuf.isEmpty()) return
            val totalSec = (segStartMs / 1000L).coerceAtLeast(0L)
            val mm = totalSec / 60
            val ss = totalSec % 60
            sb.append('[')
                .append(if (mm < 10) "0$mm" else "$mm")
                .append(':')
                .append(if (ss < 10) "0$ss" else "$ss")
                .append("] ")
                .append(segBuf.toString().trim())
                .append('\n')
            segBuf.setLength(0)
        }

        for (item in body) {
            val o = Json.obj(item) ?: continue
            val content = Json.str(o["content"])?.replace('\n', ' ')?.trim() ?: continue
            if (content.isEmpty()) continue

            val from = ((o["from"] as? Double)?.times(1000))?.toLong() ?: 0L
            val to = ((o["to"] as? Double)?.times(1000))?.toLong() ?: from

            val gap = if (segStartMs < 0) 0L else from - lastEndMs
            val tooLong = segBuf.length + content.length > 120

            if (segStartMs < 0) {
                segStartMs = from
            } else if (gap > 1500L || tooLong) {
                flush()
                segStartMs = from
            }

            if (segBuf.isNotEmpty()) segBuf.append(' ')
            segBuf.append(content)
            lastEndMs = to
        }
        flush()

        return sb.toString().trim()
    }

    /** 供 UI 展示的降级文案。异常到用户可读文案的**唯一**映射点。 */
    fun describe(e: Throwable): String = when (e) {
        is BiliError.NeedLogin ->
            "B站凭据失效\n请在「速读胶囊」中重新填写 SESSDATA\n(${e.message})"
        is BiliError.NoSubtitle ->
            "该视频无可用字幕\n${e.message}"
        is BiliError.Failed ->
            "B站接口失败\n${e.message}"
        is Http.NetworkError ->
            "网络不可达\n请检查网络后重试"
        else ->
            "未知错误\n${e.javaClass.simpleName}: ${e.message}"
    }
}
