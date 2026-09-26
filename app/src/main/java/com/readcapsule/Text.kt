package com.readcapsule

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 文本归一化与指纹。
 *
 * 从 PriceCapsule 继承的核心结论：SHA-256 截断为 16 字节（32 hex）即可，
 * 碰撞概率约 1e-19，存储减半。
 *
 * 新增用途：为「摘要缓存」生成幂等键。键必须覆盖
 * 「内容 + 模型」两个维度 —— 任一变化都必须产生新键，
 * 否则会读到用旧模型/旧内容生成的摘要，属于静默错误。
 */
object Text {

    private val WHITESPACE = Regex("\\s+")

    /**
     * 通用指纹。
     * @param parts 参与指纹的组成部分，以 0x1F（单元分隔符）拼接避免歧义碰撞
     */
    fun fingerprint(vararg parts: String): String {
        val joined = parts.joinToString("\u001F")
        val d = MessageDigest.getInstance("SHA-256")
            .digest(joined.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(32)
        for (i in 0 until 16) {
            val v = d[i].toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    /**
     * 摘要缓存键 = 域(长文/视频BV) + 内容指纹 + 模型名。
     *
     * 对内容先做长度+首尾采样再哈希：24 万字字幕全文哈希需要数毫秒且占内存，
     * 而「长度 + 首 8K + 尾 8K」已足以在任何实际场景下区分不同内容。
     */
    fun summaryKey(domain: String, content: String, model: String): String {
        val sample = if (content.length <= 16_000) content else
            content.take(8_000) + "\u001E" + content.length + "\u001E" + content.takeLast(8_000)
        return fingerprint(domain, model, sample)
    }

    /**
     * 长文正文清洗：折叠空白、去掉连续空行。
     * 不做过度清洗 —— 中文标点、数字、英文缩写都可能是语义载体。
     */
    fun cleanBody(raw: String): String =
        raw.replace(Regex("[ \t\u00A0]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()

    /**
     * SESSDATA 校验（**不做网络验证**，仅做形态检查）。
     *
     * 有效性只能由一次真实请求确认（见 BiliClient 的 -101 分支）。
     * 这里只拦明显错误的输入，避免用户把整条 Cookie 粘进来 —— 那是常见误操作，
     * 而多余的 Cookie 字段会被 B站风控视为异常。
     */
    fun looksLikeSessdata(v: String): Boolean {
        val s = v.trim()
        if (s.contains('=') || s.contains(';')) return false   // 误粘整条 Cookie
        if (s.length < 16) return false
        return s.all { it.isLetterOrDigit() || it == '%' || it == ',' }
    }

    /** 供 UI 展示的脱敏值：绝不完整显示凭据。 */
    fun mask(secret: String): String =
        if (secret.length <= 8) "*".repeat(secret.length)
        else secret.take(4) + "…" + secret.takeLast(4)

    /** 字节数转可读。 */
    fun humanBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${b / 1024} KB"
        else -> "${b / (1024 * 1024)} MB"
    }
}
