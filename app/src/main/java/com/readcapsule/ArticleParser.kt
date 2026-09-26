package com.readcapsule

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 长文抓取解析器。
 *
 * 目标场景：微信公众号文章、知乎回答、今日头条长文。这些页面正文是明文
 * TextView 节点，无障碍可完整读取 —— 这是无障碍的最佳适用场景。
 *
 * 抓取策略：不是「找到正文容器」，而是「节点级评分 + 长度阈值」。
 * 理由：正文容器 id 各平台各异且随改版变化，而「长文本节点 = 正文段落」
 * 是跨平台稳定的结构特征。代价是可能混入少量评论区，但通过
 * 「排除短节点 + 排除疑似导航/评论控件子树」可控。
 */
object ArticleParser {

    /** 单段过短必然不是正文（标题、按钮、时间戳）。 */
    private const val MIN_SEGMENT_LEN = 12

    /** 单段过长通常是 base64/元数据，非正文。 */
    private const val MAX_SEGMENT_LEN = 3000

    /** 疑似评论/推荐/导航的容器特征，命中则整棵子树跳过。 */
    private val BLOCK_SUBTREE = Regex("""(comment|reply|recommend|related|footer|toolbar|navbar|aside)""", RegexOption.IGNORE_CASE)

    /** 正文容器特征，命中则整棵子树优先采信。 */
    private val CONTENT_HINT = Regex("""(content|article|detail|body|rich_?text|webview)""", RegexOption.IGNORE_CASE)

    /** 采集结果。 */
    sealed class Result {
        data class Ok(val text: String, val paraCount: Int, val source: String) : Result()

        /** 正文过短，视为非文章页。显式失败，不显示胶囊。 */
        data class TooShort(val chars: Int) : Result()
    }

    /**
     * 抓取当前窗口正文。
     * @param root 活动窗口根节点
     * @param title 已识别的页面标题（可为空），用于从正文中去重
     */
    fun grab(root: AccessibilityNodeInfo?, title: String?): Result {
        root ?: return Result.TooShort(0)

        return try {
            val segments = LinkedHashSet<String>()
            val counter = intArrayOf(0)
            val webviewHits = intArrayOf(0)

            walk(root, 0, segments, counter, webviewHits, false)

            if (segments.isEmpty()) return Result.TooShort(0)

            // 标题可能被重复采集为第一段，去掉避免重复计入
            val cleaned = segments.filter { it != title }

            val sb = StringBuilder(8192)
            for (s in cleaned) {
                sb.append(s).append('\n')
            }
            val text = sb.toString().trim()

            if (text.length < Config.MIN_ARTICLE_CHARS) return Result.TooShort(text.length)

            val source = if (webviewHits[0] > 0) "webview" else "native"
            Result.Ok(text, cleaned.size, source)
        } catch (t: Throwable) {
            // 跨进程节点句柄失效等异常统一降级为「过短」，调用方静默跳过
            Result.TooShort(0)
        }
    }

    /**
     * 深度优先采集。
     *
     * @param blocked 是否处于被屏蔽子树内 —— 一旦进入评论/推荐容器，
     *                整棵子树都不采集，避免把热评当正文。
     */
    private fun walk(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: MutableSet<String>,
        counter: IntArray,
        webviewHits: IntArray,
        blocked: Boolean
    ) {
        if (node == null || depth > Config.MAX_DEPTH) return
        counter[0]++
        if (counter[0] > Config.MAX_NODES) return
        if (!node.isVisibleToUser) return

        val id = try { node.viewIdResourceName } catch (_: Throwable) { null }
        val cls = try { node.className?.toString() } catch (_: Throwable) { null }

        // 屏蔽判定优先于采集：进入评论区后其子树全部忽略
        val nowBlocked = blocked || (id != null && BLOCK_SUBTREE.containsMatchIn(id))

        if (cls != null && cls.contains("WebView")) webviewHits[0]++

        if (!nowBlocked) {
            // 优先采信正文容器：命中时直接递归，不额外过滤短文本（容器内文本皆为正文）
            val isContent = id != null && CONTENT_HINT.containsMatchIn(id)

            val t = readText(node)
            if (t != null) {
                val len = t.length
                val acceptable = if (isContent) {
                    // 正文容器内：放松长度下限，但仍有上限防元数据
                    len in 1..MAX_SEGMENT_LEN
                } else {
                    len in MIN_SEGMENT_LEN..MAX_SEGMENT_LEN
                }
                if (acceptable) out.add(t)
            }
        }

        val count = try { node.childCount } catch (_: Throwable) { 0 }
        for (i in 0 until count) {
            walk(node.getChild(i), depth + 1, out, counter, webviewHits, nowBlocked)
        }
    }

    /** 跨进程读取，异常吞掉返回 null。 */
    private fun readText(n: AccessibilityNodeInfo): String? = try {
        val t = n.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) t
        else n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    /**
     * 识别页面标题：用于 UI 展示与 LLM 上下文。
     * 取「长度在 8~80 且不含换行」的最长可见文本，通常是文章标题。
     */
    fun pickTitle(root: AccessibilityNodeInfo?): String? {
        root ?: return null
        return try {
            var best: String? = null
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            var n = 0
            while (stack.isNotEmpty() && n < 800) {
                val cur = stack.removeLast()
                n++
                val t = readText(cur)
                if (t != null && t.length in 8..80 && !t.contains('\n')) {
                    if (best == null || t.length > best.length) best = t
                }
                val c = try { cur.childCount } catch (_: Throwable) { 0 }
                for (i in 0 until c) {
                    try { cur.getChild(i)?.let { stack.addLast(it) } } catch (_: Throwable) {}
                }
            }
            best
        } catch (_: Throwable) {
            null
        }
    }
}
