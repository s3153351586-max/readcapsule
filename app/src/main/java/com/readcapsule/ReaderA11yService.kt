package com.readcapsule

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 速读胶囊编排层。
 *
 * 从 PriceCapsule 继承的骨架（未改动）：
 *  - TYPE_ACCESSIBILITY_OVERLAY 悬浮窗（随服务生命周期自动销毁）
 *  - 单线程 IO 执行器，优先级 MIN_PRIORITY（不与前台应用抢 CPU）
 *  - 幂等闸门 + 事件去抖
 *  - 管线级 catch(Throwable) 兜底，异常绝不逃逸到系统进程
 *  - 降级路径显式化
 *
 * 结构性差异（必须明确）：
 *  PriceCapsule 是「事件驱动、自动响应」——打开商品页自动出胶囊。
 *  ReadCapsule 是「用户驱动、按需触发」——长文/视频页只显示一个等待点击的球，
 *  点击后才走网络与 LLM。
 *
 *  为何必须改：LLM 调用有成本（token 费用）与延迟（数秒）。
 *  若沿用自动触发，用户每翻一页就产生一次计费调用 —— 这是产品级事故。
 *  所以本服务**只做感知与渲染，绝不自动发起网络请求**。
 */
class ReaderA11yService : AccessibilityService() {

    private val windowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private lateinit var store: Store

    private var overlay: CapsuleOverlay? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 单线程 IO：网络 + 数据库 + LLM 串行化。
     * 为何单线程：一次总结 = nav -> player -> subtitle -> LLM 四步，
     * 天然串行。并发化无收益，反而引入凭据竞态与限流风险。
     */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "readcapsule-io").apply { priority = Thread.MIN_PRIORITY }
    }

    /** 防重入：用户连点悬浮球时，第二次点击直接忽略。 */
    private val busy = AtomicBoolean(false)

    private var lastEventTs = 0L

    /** 当前页面的上下文快照。点击时基于此发起请求，避免点击瞬间页面已变。 */
    private var currentMode = Mode.NONE
    private var currentPkg: String? = null

    /** 缓存待总结的正文/候选 BV，避免点击时重新遍历节点树。 */
    private var pendingArticle: ArticleParser.Result.Ok? = null
    private var pendingArticleTitle: String? = null
    private var pendingBv: BvExtractor.Result? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = Store(applicationContext)
        Log.d(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        try {
            val pkg = event.packageName?.toString() ?: return
            val mode = Config.PACKAGE_MODE[pkg] ?: return

            val now = System.currentTimeMillis()
            if (now - lastEventTs < Config.DEBOUNCE_MS) return
            lastEventTs = now

            // 包名/形态变化立即重置上下文，防止把上一条视频的 BV 用到新页面
            if (pkg != currentPkg) {
                currentPkg = pkg
                pendingArticle = null
                pendingArticleTitle = null
                pendingBv = null
                busy.set(false)
            }
            currentMode = mode

            val root = rootInActiveWindow ?: return

            when (mode) {
                Mode.ARTICLE -> sniffArticle(root)
                Mode.VIDEO -> sniffVideo(root)
                Mode.NONE -> hideOverlay("mode-none")
            }
        } catch (t: Throwable) {
            // 管线级兜底：异常逃逸会杀掉无障碍服务
            Log.w(TAG, "event pipeline degraded", t)
        }
    }

    /**
     * 长文嗅探：抓正文并缓存。**此处不发网络请求**。
     * 只在抓取成功时显示待点击的球；否则隐藏（非文章页静默）。
     */
    private fun sniffArticle(root: android.view.accessibility.AccessibilityNodeInfo) {
        val title = ArticleParser.pickTitle(root)
        val res = ArticleParser.grab(root, title)

        when (res) {
            is ArticleParser.Result.Ok -> {
                // 内容指纹未变则不重建视图，避免滚动时浮窗闪烁
                if (pendingArticle?.text == res.text && overlay != null) return
                pendingArticle = res
                pendingArticleTitle = title
                pendingBv = null
                showOrUpdate(Mode.ARTICLE, CapsuleOverlay.Body.Loading(hintForArticle(res)))
            }
            is ArticleParser.Result.TooShort -> {
                pendingArticle = null
                hideOverlay("short-${res.chars}")
            }
        }
    }

    /** 视频嗅探：提取 BV 号并缓存。**此处不发网络请求**。 */
    private fun sniffVideo(root: android.view.accessibility.AccessibilityNodeInfo) {
        val texts = collectTexts(root)
        if (texts.isEmpty()) {
            hideOverlay("no-text")
            return
        }

        val title = texts.firstOrNull { it.length in 8..80 && !it.contains('\n') }
        val res = BvExtractor.extract(texts)

        // 结果未变则不重建
        if (res == pendingBv && overlay != null) return
        pendingBv = res
        pendingArticle = null
        pendingArticleTitle = title

        when (res) {
            is BvExtractor.Result.Found ->
                showOrUpdate(Mode.VIDEO, CapsuleOverlay.Body.Loading("已识别 ${res.bv}\n点击开始总结"))
            is BvExtractor.Result.Ambiguous ->
                showOrUpdate(Mode.VIDEO, CapsuleOverlay.Body.Loading("检测到 ${res.candidates.size} 个候选视频\n点击选择"))
            is BvExtractor.Result.NotFound ->
                // B站首页/推荐流没有当前视频，静默隐藏是正确行为
                hideOverlay("no-bv")
        }
    }

    private fun hintForArticle(res: ArticleParser.Result.Ok): String =
        "已抓取 ${res.text.length} 字（${res.paraCount} 段）\n点击开始总结"

    /** 广度优先收集节点文本，上限见 Config。 */
    private fun collectTexts(root: android.view.accessibility.AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>(128)
        val stack = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        stack.addLast(root)
        var n = 0
        while (stack.isNotEmpty() && n < Config.MAX_NODES) {
            val cur = stack.removeLast()
            n++
            try {
                if (cur.isVisibleToUser) {
                    cur.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                    cur.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                    val c = cur.childCount
                    for (i in 0 until c) cur.getChild(i)?.let { stack.addLast(it) }
                }
            } catch (_: Throwable) {
                // 单个节点跨进程读取失败不影响整体收集
            }
        }
        return out
    }

    // ---------------- 用户动作 ----------------

    private fun onAction(a: CapsuleOverlay.Action) {
        when (a) {
            is CapsuleOverlay.Action.Toggle -> {
                val v = overlay ?: return
                if (v.isExpanded) v.collapse() else { v.expand(); maybeAutoRun() }
            }
            is CapsuleOverlay.Action.Dismiss -> {
                overlay?.collapse()
            }
            is CapsuleOverlay.Action.PickCandidate -> {
                pendingBv = BvExtractor.Result.Found(a.bv)
                startVideoSummary(a.bv)
            }
            is CapsuleOverlay.Action.Retry -> {
                overlay?.collapse()
                maybeAutoRun()
            }
            is CapsuleOverlay.Action.CopyText -> copyCurrentSummary()
        }
    }

    /**
     * 展开后自动触发（若已具备条件且不忙）。
     * 设计取舍：展开 = 用户明确表达"我要读"，此时自动发起请求是合理的，
     * 不算"未经同意的计费调用"。收起态绝不自动触发。
     */
    private fun maybeAutoRun() {
        if (busy.get()) return
        when (currentMode) {
            Mode.ARTICLE -> {
                val art = pendingArticle ?: run {
                    overlay?.updateBodyQuiet(error("无可用正文", "请确认当前页面是完整文章页"))
                    return
                }
                startArticleSummary(art)
            }
            Mode.VIDEO -> {
                when (val bv = pendingBv) {
                    is BvExtractor.Result.Found -> startVideoSummary(bv.bv)
                    is BvExtractor.Result.Ambiguous ->
                        overlay?.updateBodyQuiet(CapsuleOverlay.Body.Ambiguous(bv.candidates))
                    else ->
                        overlay?.updateBodyQuiet(error("未识别到视频", "请在视频播放页使用，或稍后重试"))
                }
            }
            Mode.NONE -> Unit
        }
    }

    private fun startArticleSummary(art: ArticleParser.Result.Ok) {
        if (!busy.compareAndSet(false, true)) return
        setLoading("正在总结长文…", "${art.text.length} 字")

        io.execute {
            try {
                val body = summarizeCached(
                    cacheKey = Text.summaryKey("article", art.text, llmModel()),
                    bvid = "article",
                    title = pendingArticleTitle ?: "长文",
                    content = art.text,
                    isVideo = false
                )
                mainHandler.post { finishOk(body) }
            } catch (t: Throwable) {
                Log.w(TAG, "article summary failed", t)
                mainHandler.post { finishError(t) }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun startVideoSummary(bvid: String) {
        val sess = store.getCred(Store.KEY_SESSDATA)
        if (sess.isNullOrBlank()) {
            overlay?.updateBodyQuiet(
                error("未配置 B站凭据", "B站字幕是账号态资源，必须提供 SESSDATA\n请打开「速读胶囊」App 填写")
            )
            return
        }
        if (!busy.compareAndSet(false, true)) return

        setLoading("正在获取字幕…", bvid)

        io.execute {
            try {
                // 1. 字幕：先查缓存
                val doc = store.getSubtitles(bvid, System.currentTimeMillis())
                    ?: BiliClient.fetchSubtitles(bvid, sess).also { store.putSubtitles(it) }

                mainHandler.post {
                    overlay?.updateBodyQuiet(
                        CapsuleOverlay.Body.Loading("字幕已获取，正在总结…", "${doc.body.length} 字 / ${doc.lang}")
                    )
                }

                // 2. 总结：按内容指纹缓存
                val body = summarizeCached(
                    cacheKey = Text.summaryKey("video:$bvid", doc.body, llmModel()),
                    bvid = bvid,
                    title = doc.title,
                    content = doc.body,
                    isVideo = true
                )
                mainHandler.post { finishOk(body) }
            } catch (t: Throwable) {
                Log.w(TAG, "video summary failed bvid=$bvid", t)
                mainHandler.post { finishError(t) }
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * 带缓存的总结。
     *
     * 缓存键含「模型名 + 内容指纹」：
     *  - 换模型 -> key 变 -> 自动重算（不同模型摘要质量不同，复用是错的）
     *  - 同一视频重复点击 -> key 相同 -> 直接读库，**不产生 token 费用**
     */
    private fun summarizeCached(
        cacheKey: String,
        bvid: String,
        title: String,
        content: String,
        isVideo: Boolean
    ): String {
        store.getSummary(cacheKey)?.let { cached ->
            Log.d(TAG, "summary cache hit key=$cacheKey")
            return cached.body
        }

        val cfg = LlmConfig(
            baseUrl = store.getCred(Store.KEY_API_BASE) ?: LlmConfig.DEFAULT_BASE,
            apiKey = store.getCred(Store.KEY_API_KEY) ?: "",
            model = store.getCred(Store.KEY_API_MODEL) ?: LlmConfig.DEFAULT_MODEL
        )

        val result = LlmClient.summarize(content, isVideo, cfg)

        store.putSummary(
            SummaryDoc(
                key = cacheKey,
                bvid = bvid,
                title = title,
                body = result.body,
                sourceChars = result.sourceChars,
                truncated = result.truncated,
                createdAt = System.currentTimeMillis()
            )
        )
        // 截断标记随摘要一起返回，由 UI 显式呈现
        return if (result.truncated) "${result.body}\n\n---\n⚠️ 输入超出上限已抽样：${result.note}" else result.body
    }

    private fun llmModel(): String = store.getCred(Store.KEY_API_MODEL) ?: LlmConfig.DEFAULT_MODEL

    private fun finishOk(body: String) {
        val note = if (currentMode == Mode.VIDEO) "视频字幕摘要 · 缓存 7 天" else "长文摘要 · 缓存 7 天"
        overlay?.updateBodyQuiet(CapsuleOverlay.Body.Done(body, note))
    }

    private fun finishError(t: Throwable) {
        val detail = when (t) {
            is BiliClient.BiliError -> BiliClient.describe(t)
            is LlmClient.LlmError -> LlmClient.describe(t)
            is Http.HttpError -> "网络请求失败 HTTP ${t.code}\n${t.detail}"
            is Http.NetworkError -> "网络不可达\n${t.message}"
            else -> "未知错误\n${t.javaClass.simpleName}: ${t.message}"
        }
        overlay?.updateBodyQuiet(error("总结失败", detail))
    }

    private fun error(title: String, detail: String) =
        CapsuleOverlay.Body.Error(title, detail, retryable = true)

    private fun setLoading(hint: String, progress: String) {
        overlay?.updateBodyQuiet(CapsuleOverlay.Body.Loading(hint, progress))
    }

    private fun copyCurrentSummary() {
        val b = overlay?.let { currentBody } ?: return
        if (b is CapsuleOverlay.Body.Done) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("速读摘要", b.markdown))
                Log.d(TAG, "summary copied to clipboard")
            } catch (t: Throwable) {
                Log.w(TAG, "clipboard write failed", t)
            }
        }
    }

    /** 当前内容态镜像。overlay 内部持有，此处保留一份用于复制与错误判断。 */
    private var currentBody: CapsuleOverlay.Body = CapsuleOverlay.Body.Loading("准备中")

    // ---------------- 悬浮窗生命周期 ----------------

    override fun onInterrupt() {
        hideOverlay("interrupt")
    }

    override fun onDestroy() {
        io.shutdownNow()
        removeOverlay()
        if (::store.isInitialized) store.closeQuietly()
        super.onDestroy()
    }

    private fun showOrUpdate(mode: Mode, body: CapsuleOverlay.Body) {
        currentBody = body
        try {
            val view = overlay ?: CapsuleOverlay(this, ::onAction).also { fresh ->
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    // 2032：无障碍悬浮层，随服务生命周期自动销毁
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = 0
                    y = Config.OVERLAY_Y
                }

                runCatching { windowManager.removeViewImmediate(fresh) }
                windowManager.addView(fresh, params)

                overlay = fresh
                overlayParams = params
                Log.d(TAG, "overlay attach mode=$mode")
                fresh
            }
            view.render(mode, body)
        } catch (t: Throwable) {
            Log.w(TAG, "overlay attach failed", t)
            overlay = null
            overlayParams = null
        }
    }

    /** 静默更新内容态（不重建 View 树顶层）。 */
    private fun CapsuleOverlay.updateBodyQuiet(b: CapsuleOverlay.Body) {
        currentBody = b
        updateBodyQuiet(b)
    }

    private fun hideOverlay(reason: String) {
        if (overlay == null) return
        Log.d(TAG, "overlay detach: $reason")
        removeOverlay()
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        overlay = null
        overlayParams = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Throwable) {
            // 系统可能已回收该视图
        }
    }

    companion object {
        private const val TAG = "ReadCapsule"
    }
}
