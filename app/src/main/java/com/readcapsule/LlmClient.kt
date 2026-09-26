package com.readcapsule

/**
 * LLM 总结客户端。OpenAI 兼容 `/chat/completions` 协议。
 *
 * 为何选 OpenAI 兼容协议而非某一家 SDK：
 *  DeepSeek、Moonshot、智谱、通义、本地 Ollama/LM Studio 全部实现该协议。
 *  换供应商 = 改 base_url + model 两个字符串，零代码改动。
 *  引任一厂商 SDK 则把自己焊死在单一供应商上，ROI 为负。
 *
 * 降级契约（不可妥协）：
 *  - 输入超限：**截断并在 UI 显式标注「已截断」**，绝不假装总结完整内容
 *  - 无凭据：抛出 [LlmError.NoKey]，由 UI 提示配置，而非返回空字符串
 *  - 服务端报错：原样上抛状态码与错误体摘要
 */
object LlmClient {

    sealed class LlmError(message: String) : Exception(message) {
        class NoKey : LlmError("LLM API Key 未配置")
        class Http(val code: Int, val detail: String) : LlmError("LLM 请求失败 HTTP $code: $detail")
        class Malformed(detail: String) : LlmError("LLM 响应结构异常：$detail")
        class Network(detail: String) : LlmError("网络不可达：$detail")
    }

    /** 总结产物。 */
    data class Summary(
        val body: String,
        val truncated: Boolean,
        val sourceChars: Int,
        val note: String
    )

    private const val SYSTEM_PROMPT_VIDEO =
        """你是视频内容速读助手。用户会给你一份带 [mm:ss] 时间戳的视频字幕全文。
你的任务是把数万字字幕压缩成用户 20 秒内能读完的卡片。

严格按以下 Markdown 结构输出，不要添加任何前言、结语或额外标题：

## 核心结论
（一句话，40 字以内，直接回答「这视频到底讲了什么」）

## 关键要点
- （3 到 5 条，每条 25 字以内，只保留信息增量，剔除寒暄与重复）

## 时间线
- `mm:ss` 主题（挑 3 到 6 个真正有信息量的节点，必须使用字幕中出现过的时间戳）
- `mm:ss` 主题

## 适用建议
（一到两句：什么情况下值得看原片，什么情况下看完摘要就够了）

约束：
1. 时间戳必须来自给定字幕，严禁编造。
2. 若字幕内容明显不完整或与主题无关，在「适用建议」中直接说明，不要强行编造要点。
3. 不要输出「根据字幕内容」之类的元话术。"""

    private const val SYSTEM_PROMPT_ARTICLE =
        """你是长文速读助手。用户会给你一篇长文正文（可能混杂评论区残留文本）。
你的任务是把数千字长文压缩成用户 20 秒内能读完的卡片。

严格按以下 Markdown 结构输出，不要添加任何前言、结语或额外标题：

## 核心结论
（一句话，40 字以内）

## 关键要点
- （3 到 5 条，每条 25 字以内，只保留信息增量）

## 关键数据
（若正文含具体数字、结论、引用来源，列出 2 到 4 条；若确实没有，整段写「无」）

## 适用建议
（一到两句：这文章是否值得精读）

约束：
1. 若正文明显是残片（如只有评论区内容、明显不连贯），在「适用建议」中说明，不要编造。
2. 不要输出「根据文章内容」之类的元话术。"""

    /**
     * 发起总结。
     *
     * @param content 已抓取的正文（长文或带时间戳字幕）
     * @param isVideo 决定 system prompt
     * @param cfg LLM 端点配置
     */
    fun summarize(content: String, isVideo: Boolean, cfg: LlmConfig): Summary {
        if (cfg.apiKey.isBlank()) throw LlmError.NoKey()
        if (content.isBlank()) throw LlmError.Malformed("输入正文为空")

        val truncated = content.length > Config.MAX_PAYLOAD_CHARS
        val payload = if (truncated) samplePreserving(content, Config.MAX_PAYLOAD_CHARS) else content

        val system = if (isVideo) SYSTEM_PROMPT_VIDEO else SYSTEM_PROMPT_ARTICLE

        // 请求体手工构造：字段固定 4 个，引入序列化库不划算
        val req = buildString(payload.length + 1024) {
            append('{')
            append("\"model\":").append(Json.quote(cfg.model)).append(',')
            append("\"temperature\":0.3,")          // 低温：摘要任务需要稳定、少发挥
            append("\"stream\":false,")
            append("\"messages\":[")
            append("{\"role\":\"system\",\"content\":").append(Json.quote(system)).append("},")
            append("{\"role\":\"user\",\"content\":").append(Json.quote(payload)).append('}')
            append(']')
            append('}')
        }

        val url = cfg.baseUrl.trimEnd('/') + "/chat/completions"

        val raw = try {
            Http.postJson(
                url = url,
                json = req,
                headers = mapOf(
                    "Authorization" to "Bearer ${cfg.apiKey}",
                    "Content-Type" to "application/json"
                )
            )
        } catch (e: Http.HttpError) {
            throw LlmError.Http(e.code, e.detail)
        } catch (e: Http.NetworkError) {
            throw LlmError.Network(e.message ?: "unknown")
        }

        val parsed = Json.parse(raw)
        if (parsed is Json.ParseFail) throw LlmError.Malformed(parsed.reason)

        // 部分供应商错误时返回 200 + error 对象，需显式识别
        Json.pathStr(parsed, "error", "message")?.let { throw LlmError.Http(200, it.take(160)) }

        val text = Json.pathStr(parsed, "choices", "0", "message", "content")
            ?: throw LlmError.Malformed("缺少 choices[0].message.content")

        if (text.isBlank()) throw LlmError.Malformed("模型返回空内容")

        val note = buildString {
            append("输入 ")
            append(content.length)
            append(" 字")
            if (truncated) {
                append("（超 ")
                append(Config.MAX_PAYLOAD_CHARS)
                append(" 字上限，已抽样压缩）")
            }
        }

        return Summary(
            body = text.trim(),
            truncated = truncated,
            sourceChars = content.length,
            note = note
        )
    }

    /**
     * 超长输入抽样：保留头部与尾部，中段按比例抽取。
     *
     * 为何不用简单截尾：长文的结论往往在末尾，视频的总结段也常在最后几分钟。
     * 截尾会丢掉最有价值的部分。抽样比例 45% 头 / 10% 中 / 45% 尾，
     * 且**只在段落边界切分**，避免截断到句子中间。
     */
    private fun samplePreserving(text: String, limit: Int): String {
        val lines = text.split('\n')
        if (lines.size < 10) return text.take(limit)

        val headQuota = limit * 45 / 100
        val midQuota = limit * 10 / 100
        val tailQuota = limit - headQuota - midQuota

        val head = StringBuilder()
        var i = 0
        while (i < lines.size && head.length + lines[i].length < headQuota) {
            head.append(lines[i]).append('\n'); i++
        }

        val tail = StringBuilder()
        var j = lines.size - 1
        while (j > i && tail.length + lines[j].length < tailQuota) {
            tail.insert(0, lines[j]).append('\n'); j--
        }

        val mid = StringBuilder()
        if (j > i) {
            val step = ((j - i) / 8).coerceAtLeast(1)   // 中段最多抽 8 行
            var k = i
            while (k < j && mid.length < midQuota) {
                mid.append(lines[k]).append('\n')
                k += step
            }
        }

        return "$head\n…（此处省略中段 ${(j - i).coerceAtLeast(0)} 行）…\n$mid$tail"
    }

    /** 异常到用户可读文案的唯一映射点。 */
    fun describe(e: Throwable): String = when (e) {
        is LlmError.NoKey -> "LLM API Key 未配置\n请在「速读胶囊」中填写"
        is LlmError.Http -> "模型服务报错\n${e.detail}\n(HTTP ${e.code})"
        is LlmError.Malformed -> "模型返回异常\n${e.message}"
        is LlmError.Network -> "网络不可达\n${e.message}"
        else -> "未知错误\n${e.javaClass.simpleName}: ${e.message}"
    }
}

/** LLM 端点配置。 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String
) {
    /** 默认端点：DeepSeek，性价比适合本场景（长文本摘要）。 */
    companion object {
        const val DEFAULT_BASE = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"

        /** 已知兼容端点，供 MainActivity 一键填充。 */
        val PRESETS = listOf(
            Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
            Preset("Moonshot", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
            Preset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
            Preset("本地 Ollama", "http://127.0.0.1:11434/v1", "qwen2.5:7b")
        )
    }

    data class Preset(val label: String, val base: String, val model: String)
}
