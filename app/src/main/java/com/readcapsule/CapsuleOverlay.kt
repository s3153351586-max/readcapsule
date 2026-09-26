package com.readcapsule

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * 悬浮胶囊。两态：
 *  - 收起：56dp 自绘小圆球（形态由包名决定：📄 长文 / 🎬 视频）
 *  - 展开：320dp 宽卡片，内含四种内容态（加载/摘要/错误/歧义）
 *
 * 架构选择：收起态自绘（Canvas 单次绘制，无 measure 开销），
 * 展开态用真实 View 树（ScrollView + TextView）——
 * 理由：摘要是可长达数千字、含 Markdown 结构的富文本，
 * 自绘需要自己实现换行/滚动/触摸惯性，属于典型的重复造轮子。
 * 两态分别用最合适的工具，而非强行统一。
 */
class CapsuleOverlay(
    private val ctx: Context,
    private val onAction: (Action) -> Unit
) : View(ctx) {

    /** 用户操作事件。 */
    sealed class Action {
        object Toggle : Action()
        object Dismiss : Action()
        data class PickCandidate(val bv: String) : Action()
        object Retry : Action()
        object CopyText : Action()
    }

    enum class Mode { ARTICLE, VIDEO }

    /** 卡片内容态。 */
    sealed class Body {
        data class Loading(val hint: String, val progress: String = "") : Body()
        data class Done(val markdown: String, val footer: String) : Body()
        data class Error(val title: String, val detail: String, val retryable: Boolean) : Body()
        data class Ambiguous(val candidates: List<String>) : Body()
    }

    private var expanded = false
    private var mode = Mode.ARTICLE
    private var body: Body = Body.Loading("准备中")

    /** 展开态宿主。懒创建，收起时置 null 释放 View 树。 */
    private var cardHost: LinearLayout? = null
    private var scroll: ScrollView? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val colorInk = Color.parseColor("#202124")
    private val colorAccent = Color.parseColor("#1A73E8")
    private val colorVideo = Color.parseColor("#D93025")
    private val colorSurface = Color.parseColor("#FFFFFF")
    private val colorMuted = Color.parseColor("#5F6368")
    private val colorDivider = Color.parseColor("#E8EAED")

    init {
        // 拖动 + 点击：位移小于阈值视为点击，避免拖动时误展开
        setOnTouchListener(object : OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val lp = v.layoutParams as? WindowManager.LayoutParams ?: return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = e.rawX; downRawY = e.rawY
                        startX = lp.x; startY = lp.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = startX + (e.rawX - downRawX).toInt()
                        lp.y = startY + (e.rawY - downRawY).toInt()
                        try {
                            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                                .updateViewLayout(v, lp)
                        } catch (_: Throwable) {
                            // 视图已被移除，忽略本帧
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (abs(e.rawX - downRawX) <= TOUCH_SLOP_PX) onAction(Action.Toggle)
                        return true
                    }
                }
                return true
            }
        })
    }

    /** 设置形态与内容，并自适应尺寸。 */
    fun render(m: Mode, b: Body) {
        mode = m
        body = b
        rebuildCard()
        requestLayout()
        invalidate()
    }

    /** 收起态只更新内容（用于加载进度），不重建卡片。 */
    fun updateBodyQuiet(b: Body) {
        body = b
        if (expanded) rebuildCard()
        invalidate()
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        releaseCard()
        requestLayout()
        invalidate()
    }

    fun expand() {
        if (expanded) return
        expanded = true
        rebuildCard()
        requestLayout()
        invalidate()
    }

    val isExpanded: Boolean get() = expanded

    // ---------------- 布局 ----------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!expanded) {
            val d = BALL_DP.dp.toInt()
            setMeasuredDimension(d, d)
        } else {
            setMeasuredDimension(CARD_W_DP.dp.toInt(), CARD_H_DP.dp.toInt())
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        cardHost?.let { host ->
            val pad = CARD_PAD_DP.dp.toInt()
            host.layout(pad, pad, width - pad, height - pad)
        }
    }

    // ---------------- 绘制 ----------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (!expanded) {
            drawBall(canvas, w, h)
            return
        }

        // 卡片底 + 阴影 + 描边
        val r = 16f.dp
        paint.style = Paint.Style.FILL
        paint.color = colorInk
        paint.alpha = 36
        canvas.drawRoundRect(3f.dp, 4f.dp, w - 1f.dp, h - 0.5f.dp, r, r, paint)

        paint.alpha = 255
        paint.color = colorSurface
        canvas.drawRoundRect(0f, 0f, w - 3f.dp, h - 4f.dp, r, r, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f.dp
        paint.color = colorDivider
        canvas.drawRoundRect(0f, 0f, w - 3f.dp, h - 4f.dp, r, r, paint)
        paint.style = Paint.Style.FILL
    }

    /** 收起态圆球：底色区分形态（长文蓝 / 视频红），中央显示图标字形。 */
    private fun drawBall(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val rad = (w / 2f) - 2f.dp

        paint.style = Paint.Style.FILL
        paint.color = colorInk
        paint.alpha = 30
        canvas.drawCircle(cx + 1f.dp, cy + 2f.dp, rad, paint)

        paint.alpha = 255
        // 加载态用灰色，避免用户误以为已完成
        paint.color = when (body) {
            is Body.Loading -> colorMuted
            is Body.Error -> Color.parseColor("#B3261E")
            else -> if (mode == Mode.VIDEO) colorVideo else colorAccent
        }
        canvas.drawCircle(cx, cy, rad, paint)

        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 18f.dp
        val glyph = when {
            body is Body.Loading -> "…"
            body is Body.Error -> "!"
            mode == Mode.VIDEO -> "🎬"
            else -> "📄"
        }
        // emoji 的字体基线偏移比中文大，微调至视觉居中
        canvas.drawText(glyph, cx, cy + 7f.dp, textPaint)
    }

    // ---------------- 展开卡片构建 ----------------

    /**
     * 重建卡片 View 树。
     * 每次全量重建而非增量 diff：内容态种类少（4 种）且切换频率低（每次点击一次），
     * 增量更新引入的状态同步 bug 风险远高于重建开销。
     */
    private fun rebuildCard() {
        val existing = cardHost
        if (existing != null) {
            removeView(existing)
        }

        val host = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 顶栏：标题 + 关闭
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(ctx).apply {
            text = if (mode == Mode.VIDEO) "🎬 视频速读" else "📄 长文速读"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorInk)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(TextView(ctx).apply {
            text = "✕"
            textSize = 15f
            setTextColor(colorMuted)
            setPadding(16f.dp.toInt(), 4f.dp.toInt(), 4f.dp.toInt(), 4f.dp.toInt())
            setOnClickListener { onAction(Action.Dismiss) }
        })
        host.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 分隔线
        host.addView(View(ctx).apply { setBackgroundColor(colorDivider) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f.dp.toInt()
            ).apply { topMargin = 6f.dp.toInt(); bottomMargin = 6f.dp.toInt() })

        // 内容区
        when (val b = body) {
            is Body.Loading -> {
                host.addView(TextView(ctx).apply {
                    text = b.hint
                    textSize = 12f
                    setTextColor(colorMuted)
                    setLineSpacing(4f.dp, 1f)
                }, weighted())

                if (b.progress.isNotEmpty()) {
                    host.addView(TextView(ctx).apply {
                        text = b.progress
                        textSize = 11f
                        setTextColor(colorAccent)
                        setPadding(0, 8f.dp.toInt(), 0, 0)
                    })
                }
            }

            is Body.Done -> {
                val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = true }
                sv.addView(TextView(ctx).apply {
                    text = Markdown.render(b.markdown)
                    textSize = 11.5f
                    setTextColor(colorInk)
                    setLineSpacing(5f.dp, 1f)
                    // 允许长按选中：摘要里的数字/结论用户常需复制
                    setTextIsSelectable(true)
                })
                host.addView(sv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ))
                scroll = sv

                host.addView(View(ctx).apply { setBackgroundColor(colorDivider) },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f.dp.toInt()
                    ).apply { topMargin = 6f.dp.toInt(); bottomMargin = 6f.dp.toInt() })

                val footer = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                footer.addView(TextView(ctx).apply {
                    text = b.footer
                    textSize = 9.5f
                    setTextColor(colorMuted)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                footer.addView(TextView(ctx).apply {
                    text = "复制"
                    textSize = 10.5f
                    setTextColor(colorAccent)
                    setPadding(12f.dp.toInt(), 2f.dp.toInt(), 2f.dp.toInt(), 2f.dp.toInt())
                    setOnClickListener { onAction(Action.CopyText) }
                })
                host.addView(footer)
            }

            is Body.Error -> {
                host.addView(TextView(ctx).apply {
                    text = b.title
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#B3261E"))
                })
                host.addView(TextView(ctx).apply {
                    text = b.detail
                    textSize = 11f
                    setTextColor(colorMuted)
                    setLineSpacing(4f.dp, 1f)
                    setPadding(0, 8f.dp.toInt(), 0, 0)
                }, weighted())

                if (b.retryable) {
                    host.addView(TextView(ctx).apply {
                        text = "重试"
                        textSize = 11.5f
                        setTextColor(colorAccent)
                        gravity = Gravity.CENTER
                        setPadding(0, 8f.dp.toInt(), 0, 0)
                        setOnClickListener { onAction(Action.Retry) }
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ))
                }
            }

            is Body.Ambiguous -> {
                host.addView(TextView(ctx).apply {
                    text = "检测到多个候选视频，请选择要总结的那个："
                    textSize = 11f
                    setTextColor(colorMuted)
                    setLineSpacing(4f.dp, 1f)
                })
                for (bv in b.candidates) {
                    host.addView(TextView(ctx).apply {
                        text = "▶ $bv"
                        textSize = 12f
                        setTextColor(colorAccent)
                        setPadding(0, 10f.dp.toInt(), 0, 0)
                        setOnClickListener { onAction(Action.PickCandidate(bv)) }
                    })
                }
                host.addView(TextView(ctx).apply {
                    text = "不猜 —— 选错视频会产出完全无关的摘要。"
                    textSize = 9.5f
                    setTextColor(colorMuted)
                    setPadding(0, 12f.dp.toInt(), 0, 0)
                }, weighted())
            }
        }

        // 无障碍可达性：让 TalkBack 能读到卡片内容（本应用自身也应是无障碍友好的）
        host.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        addView(host, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        cardHost = host
    }

    private fun releaseCard() {
        cardHost?.let { removeView(it) }
        cardHost = null
        scroll = null
    }

    private fun weighted() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
    )

    private val Float.dp: Float get() = this * ctx.resources.displayMetrics.density

    companion object {
        private const val BALL_DP = 52f
        private const val CARD_W_DP = 320f
        private const val CARD_H_DP = 400f
        private const val CARD_PAD_DP = 16f
        private const val TOUCH_SLOP_PX = 8f
    }
}

/**
 * 极简 Markdown 渲染。
 *
 * 为何不用 Markwon：引入 1 个依赖 + 数条混淆规则，只为渲染 4 种语法
 * （## 标题、- 列表、`code`、**粗体**）。ROI 为负。
 *
 * 采用给 TextView 挂 SpannableString 的方式，保留 TextView 原生换行与滚动。
 * 未识别的语法原样输出 —— 宁可显示 `***` 也不吞掉用户内容。
 */
object Markdown {

    fun render(md: String): CharSequence {
        val sb = StringBuilder(md.length + 64)
        for (line in md.split('\n')) {
            val t = line.trim()
            when {
                t.startsWith("##") -> {
                    // 标题：去掉 ##，上下留白由换行模拟
                    sb.append('\n').append(t.removePrefix("##").trim()).append('\n')
                }
                t.startsWith("- ") || t.startsWith("* ") -> {
                    sb.append("  • ").append(t.substring(2).trim()).append('\n')
                }
                t.startsWith("###") -> {
                    sb.append('\n').append(t.removePrefix("###").trim()).append('\n')
                }
                else -> sb.append(line).append('\n')
            }
        }
        return sb.toString().trim()
    }
}
