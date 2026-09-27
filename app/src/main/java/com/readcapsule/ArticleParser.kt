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
 *
 * ============================ 本版改动（诊断版） ============================
 * 原实现把「抓不到正文」的所有原因压成一个 TooShort(chars)，调用方无从区分
 * 「页面本来就不是文章」与「是文章但解析器抓不到」。后者是缺陷，前者是正常。
 * 本次拆出 Fail 维度，并把统计量一并带回，使调用方能区分四种失败路径：
 *
 *   NO_ROOT      rootInActiveWindow 为空（服务未正确连接 / 窗口权限不足）
 *   NOT_VISIBLE  根节点本身 isVisibleToUser=false（前台窗口认错了）
 *   EMPTY        走完整棵树没采到任何一段（多数是 isVisibleToUser 全 false）
 *   TOO_SHORT    采到了段落但累计不足阈值（节点上限/深度上限被截断）
 *
 * 这四者的修法完全不同，之前全部表现为同一个「无悬浮球」，无法定位。
 *
 * 另外：原 walk 遇 isVisibleToUser=false 直接 return，等于剪掉整棵子树。
 * 微信/知乎把正文放在 WebView 内，部分 ROM 下 WebView 内部节点会批量上报
 * 不可见，一处 return 就能把整篇正文剪掉。现在改为「只跳过本节点、仍下钻
 * 子节点」，并把剪枝次数计入统计，便于判断该改动是否真的生效。
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

    /** 失败原因。用于把「静默不显示」变成「可解释不显示」。 */
    enum class Fail { NO_ROOT, NOT_VISIBLE, EMPTY, TOO_SHORT }

    /**
     * 遍历统计。诊断用 —— 决定该调大哪个上限、该不该放开可见性判定。
     *
     * @param visited       实际进入 walk 的节点数
     * @param visible       其中 isVisibleToUser=true 的节点数
     * @param invisibleCut  上报为不可见的节点数（整棵子树曾会被剪掉）
     * @param textNodes     读到了非空 text/contentDescription 的节点数
     * @param rejectedShort 因短于 MIN_SEGMENT_LEN 被丢弃的节点数
     * @param rejectedLong  因长于 MAX_SEGMENT_LEN 被丢弃的节点数
     * @param blockedHits   命中 BLOCK_SUBTREE 的子树数
     * @param webviewHits   遇到的 WebView 类节点数
     * @param maxDepthSeen  实际到达的最大深度
     * @param truncated     是否触发了 MAX_NODES / MAX_DEPTH 提前返回
     */
    data class Stats(
        var visited: Int = 0,
        var visible: Int = 0,
        var invisibleCut: Int = 0,
        var textNodes: Int = 0,
        var rejectedShort: Int = 0,
        var rejectedLong: Int = 0,
        var blockedHits: Int = 0,
        var webviewHits: Int = 0,
        var maxDepthSeen: Int = 0,
        var truncated: Boolean = false
    ) {
        /** 单行摘要，便于塞进悬浮球状态栏。 */
        fun brief(): String =
            "节点 $visited / 可见 $visible / 不可见 $invisibleCut / 文本 $textNodes" +
                " / 淘汰 短$rejectedShort 长$rejectedLong" +
                " / WebView $webviewHits / 深 $maxDepthSeen" +
                if (truncated) " / ⚠截断" else ""
    }

    /** 采集结果。 */
    sealed class Result {
        data class Ok(val text: String, val paraCount: Int, val source: String, val stats: Stats) : Result()

        /** 正文过短，视为非文章页。显式失败，不显示胶囊。 */
        data class TooShort(val chars: Int, val fail: Fail, val stats: Stats) : Result()
    }

    /**
     * 抓取当前窗口正文。
     * @param root 活动窗口根节点
     * @param title 已识别的页面标题（可为空），用于从正文中去重
     */
    fun grab(root: AccessibilityNodeInfo?, title: String?): Result {
        val stats = Stats()
        root ?: return Result.TooShort(0, Fail.NO_ROOT, stats)

        return try {
            // 根节点自身不可见 = 拿到的是别的窗口（如输入法/弹窗），直接报出来
            val rootVisible = try { root.isVisibleToUser } catch (_: Throwable) { false }
            if (!rootVisible) return Result.TooShort(0, Fail.NOT_VISIBLE, stats)

            val segments = LinkedHashSet<String>()

            walk(root, 0, segments, stats, false)

            if (segments.isEmpty()) return Result.TooShort(0, Fail.EMPTY, stats)

            // 标题可能被重复采集为第一段，去掉避免重复计入
            val cleaned = segments.filter { it != title }

            val sb = StringBuilder(8192)
            for (s in cleaned) {
                sb.append(s).append('\n')
            }
            val text = sb.toString().trim()

            if (text.length < Config.MIN_ARTICLE_CHARS) {
                return Result.TooShort(text.length, Fail.TOO_SHORT, stats)
            }

            val source = if (stats.webviewHits > 0) "webview" else "native"
            Result.Ok(text, cleaned.size, source, stats)
        } catch (t: Throwable) {
            // 跨进程节点句柄失效等异常统一降级为「过短」，调用方静默跳过
            Result.TooShort(0, Fail.EMPTY, stats)
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
        st: Stats,
        blocked: Boolean
    ) {
        if (node == null) return
        if (depth > Config.MAX_DEPTH) {
            st.truncated = true
            return
        }
        st.visited++
        if (st.visited > Config.MAX_NODES) {
            st.truncated = true
            return
        }
        if (depth > st.maxDepthSeen) st.maxDepthSeen = depth

        // 原实现此处是 `if (!node.isVisibleToUser) return` —— 一处不可见就剪掉
        // 整棵子树。微信/知乎正文在 WebView 内，部分 ROM 下 WebView 子节点会批量
        // 上报不可见，于是整篇正文被一次性剪掉，表现为「什么都没抓到」。
        // 现改为：跳过本节点自身，但继续下钻子节点，并统计剪枝次数。
        val visible = try { node.isVisibleToUser } catch (_: Throwable) { false }
        if (!visible) {
            st.invisibleCut++
            val c0 = try { node.childCount } catch (_: Throwable) { 0 }
            for (i in 0 until c0) {
                val ch = try { node.getChild(i) } catch (_: Throwable) { null }
                walk(ch, depth + 1, out, st, blocked)
            }
            return
        }
        st.visible++

        val id = try { node.viewIdResourceName } catch (_: Throwable) { null }
        val cls = try { node.className?.toString() } catch (_: Throwable) { null }

        // 屏蔽判定优先于采集：进入评论区后其子树全部忽略
        val nowBlocked = blocked || (id != null && BLOCK_SUBTREE.containsMatchIn(id))
        if (nowBlocked && !blocked) st.blockedHits++

        if (cls != null && cls.contains("WebView")) st.webviewHits++

        if (!nowBlocked) {
            // 优先采信正文容器：命中时直接递归，不额外过滤短文本（容器内文本皆为正文）
            val isContent = id != null && CONTENT_HINT.containsMatchIn(id)

            val t = readText(node)
            if (t != null) {
                st.textNodes++
                val len = t.length
                val acceptable = if (isContent) {
                    // 正文容器内：放松长度下限，但仍有上限防元数据
                    len in 1..MAX_SEGMENT_LEN
                } else {
                    len in MIN_SEGMENT_LEN..MAX_SEGMENT_LEN
                }
                if (acceptable) {
                    out.add(t)
                } else if (len < MIN_SEGMENT_LEN) {
                    st.rejectedShort++
                } else {
                    st.rejectedLong++
                }
            }
        }

        val count = try { node.childCount } catch (_: Throwable) { 0 }
        for (i in 0 until count) {
            val ch = try { node.getChild(i) } catch (_: Throwable) { null }
            walk(ch, depth + 1, out, st, nowBlocked)
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
