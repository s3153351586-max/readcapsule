package com.readcapsule

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.Intent

/**
 * 配置与自检界面。
 *
 * 手动步骤：5 步
 *  1. 安装 APK
 *  2. 开启无障碍服务
 *  3. 填写 B站 SESSDATA
 *  4. 填写 LLM API Key（可选预设）
 *  5. 完成（返回目标应用即生效）
 *
 * UI 以代码构建而非 XML inflate：控件数 ~12，直接 new 省去
 * inflate 的反射开销与 findViewById 查找。scrollView 承载以适配小屏。
 */
class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var statusView: TextView
    private lateinit var sessInput: EditText
    private lateinit var baseInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var testResult: TextView

    /** 诊断输出区。内容来自服务进程内存，见 buildDiag()。 */
    private lateinit var diagView: TextView

    private val density by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(applicationContext)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        // 系统不提供无障碍授权回调，只能回到前台时主动读取真实状态
        refreshStatus()
    }

    override fun onDestroy() {
        store.closeQuietly()
        super.onDestroy()
    }

    // ---------------- UI 构建 ----------------

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(20), pad(32), pad(20), pad(32))
        }

        root.addView(title("速读胶囊"))
        root.addView(caption("长文提取 + B站长视频字幕总结 · 同一个悬浮球自动切换形态"))
        root.addView(space(16))

        // --- 状态区 ---
        root.addView(sectionLabel("运行状态"))
        statusView = TextView(this).apply {
            textSize = 13f
            setLineSpacing(6f * density, 1f)
        }
        root.addView(statusView)
        root.addView(space(8))
        root.addView(makeButton("开启无障碍服务") { openAccessibilitySettings() })
        root.addView(space(24))

        // --- 诊断区（本版新增）---
        // 目的：不用连电脑、不用 logcat，直接在手机上看到「服务有没有收到事件、
        // 正文抓到了几个字、因为什么被放弃」。这是定位「不弹球」唯一可靠的依据。
        root.addView(sectionLabel("诊断 · 抓取轨迹"))
        root.addView(caption(
            "先在微信里打开一篇公众号文章，停留 2 秒，再回到本页。\n" +
                "下面若出现「article FAIL: …」即说明事件已到达、是正文抓取环节的问题；\n" +
                "若一片空白且显示「服务未连接」，则问题在服务本身没跑起来。"
        ))
        diagView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setLineSpacing(4f * density, 1f)
            setTextIsSelectable(true)   // 便于长按复制发给我
        }
        root.addView(diagView)
        root.addView(space(8))
        root.addView(makeButton("刷新诊断") { refreshStatus() })
        root.addView(space(8))

        // 手动测试球：二分诊断的核心工具。
        // 不依赖任何事件或嗅探，直接尝试 addView —— 用来区分
        // 「overlay 机制坏了」与「事件/嗅探环节没走到」。
        root.addView(caption(
            "下面两个按钮绕过所有事件与嗅探逻辑，直接尝试显示悬浮球。\n" +
                "能显示 → 问题在事件或正文抓取；不能显示 → 问题在悬浮窗本身。"
        ))
        root.addView(makeButton("手动显示测试球") {
            val svc = ReadingServiceHolder.get()
            testResult.text = if (svc == null) {
                "服务未连接 —— 无障碍服务没有运行，无法测试悬浮窗。\n" +
                    "请先确认设置里开关是「已开启」，然后回到本页重试。"
            } else {
                svc.showTestBall()
            }
            testResult.setTextColor(Color.parseColor("#202124"))
        })
        root.addView(space(6))
        root.addView(makeButton("隐藏测试球") {
            val svc = ReadingServiceHolder.get()
            testResult.text = svc?.hideTestBall() ?: "服务未连接"
            testResult.setTextColor(Color.parseColor("#202124"))
        })
        root.addView(space(24))

        // --- B站凭据 ---
        root.addView(sectionLabel("B站凭据（字幕提取必需）"))
        root.addView(caption(
            "B站字幕是账号态资源，无公开接口。必须填入你自己的 SESSDATA。\n" +
                "获取方式：浏览器登录 bilibili.com → F12 → Application → Cookies → " +
                "复制 SESSDATA 的值（不含 “SESSDATA=” 前缀）。\n" +
                "凭据仅存于本机应用私有数据库，且本应用无读取本机其它数据的权限。"
        ))
        sessInput = input("粘贴 SESSDATA 值", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        root.addView(sessInput)
        root.addView(space(8))
        root.addView(makeButton("保存 B站凭据") { saveSess() })
        root.addView(space(24))

        // --- LLM 配置 ---
        root.addView(sectionLabel("大模型配置（OpenAI 兼容协议）"))
        root.addView(caption("任意实现 /chat/completions 的服务均可。点下方预设可一键填充。"))
        root.addView(presetRow())
        root.addView(space(8))

        baseInput = input("Base URL，如 https://api.deepseek.com/v1", InputType.TYPE_TEXT_VARIATION_URI)
        keyInput = input("API Key", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        modelInput = input("模型名，如 deepseek-chat", InputType.TYPE_CLASS_TEXT)
        root.addView(baseInput)
        root.addView(space(6))
        root.addView(keyInput)
        root.addView(space(6))
        root.addView(modelInput)
        root.addView(space(8))
        root.addView(makeButton("保存模型配置") { saveLlm() })
        root.addView(space(24))

        // --- 自检 ---
        root.addView(sectionLabel("自检"))
        root.addView(caption("离线校验 BV 提取、wbi 签名、缓存读写与凭据形态。不发起网络请求。"))
        root.addView(space(8))
        root.addView(makeButton("运行离线自检") { runSelfTest() })
        testResult = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setLineSpacing(5f * density, 1f)
            setPadding(0, pad(12), 0, 0)
        }
        root.addView(testResult)
        root.addView(space(16))

        // --- 缓存管理 ---
        root.addView(sectionLabel("缓存"))
        root.addView(makeButton("清空字幕与摘要缓存") {
            store.purgeAll()
            refreshStatus()
        })
        root.addView(space(24))

        // --- 已知限制 ---
        root.addView(sectionLabel("已知限制"))
        root.addView(caption(
            "· 多分P视频只总结第一个分P（覆盖多数场景）。\n" +
                "· 无字幕的视频（含纯音乐、UP 未上传且 AI 未生成）无法总结，会显式报错。\n" +
                "· BV 号依赖播放器内容描述，B站改版可能导致识别率下降。\n" +
                "· SESSDATA 有效期由 B站决定，失效后需重新填写。"
        ))

        return ScrollView(this).apply { addView(root) }
    }

    private fun presetRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (p in LlmConfig.PRESETS) {
            row.addView(TextView(this).apply {
                text = p.label
                textSize = 12f
                setTextColor(Color.parseColor("#1A73E8"))
                setPadding(pad(6), pad(8), pad(12), pad(8))
                setOnClickListener {
                    baseInput.setText(p.base)
                    modelInput.setText(p.model)
                }
            })
        }
        return row
    }

    // ---------------- 动作 ----------------

    private fun saveSess() {
        val v = sessInput.text.toString().trim()
        if (v.isEmpty()) {
            toast("SESSDATA 不能为空")
            return
        }
        if (!Text.looksLikeSessdata(v)) {
            // 常见误操作：把整条 Cookie 粘进来。显式纠正，不静默截取。
            toast("格式不正确：请只填值，不要带 “SESSDATA=” 与前后的其它字段")
            return
        }
        store.putCred(Store.KEY_SESSDATA, v)
        sessInput.setText("")
        toast("已保存 B站凭据（${Text.mask(v)}）")
        refreshStatus()
    }

    private fun saveLlm() {
        val base = baseInput.text.toString().trim()
        val key = keyInput.text.toString().trim()
        val model = modelInput.text.toString().trim()

        if (key.isEmpty()) { toast("API Key 不能为空"); return }
        if (base.isEmpty()) { toast("Base URL 不能为空"); return }
        if (!base.startsWith("http")) { toast("Base URL 需以 http(s):// 开头"); return }

        store.putCred(Store.KEY_API_BASE, base.trimEnd('/'))
        store.putCred(Store.KEY_API_KEY, key)
        store.putCred(Store.KEY_API_MODEL, model.ifEmpty { LlmConfig.DEFAULT_MODEL })

        keyInput.setText("")
        toast("已保存模型配置")
        refreshStatus()
    }

    /** 离线自检：不触网，验证纯逻辑正确性 + 存储读写。 */
    private fun runSelfTest() {
        val checks = ArrayList<SelfTest.Check>()

        // 算法层
        checks.addAll(SelfTest.run())

        // 存储层：写入 → 读回 → 断言一致
        checks.add(storageCheck())

        // 凭据形态校验器
        checks.add(SelfTest.Check(
            "SESSDATA 形态校验",
            Text.looksLikeSessdata("abcd1234efgh5678ijkl") &&
                !Text.looksLikeSessdata("SESSDATA=abcd1234efgh5678") &&
                !Text.looksLikeSessdata("abc"),
            "ok"
        ))

        val sb = StringBuilder()
        var fail = 0
        for (c in checks) {
            if (!c.pass) fail++
            sb.append(if (c.pass) "✓ " else "✗ ").append(c.name)
            if (!c.pass) sb.append("  → ").append(c.detail)
            sb.append('\n')
        }
        sb.append("\n").append(if (fail == 0) "全部通过 (${checks.size})" else "失败 $fail / ${checks.size}")

        testResult.text = sb.toString()
        testResult.setTextColor(if (fail == 0) Color.parseColor("#1E8E3E") else Color.parseColor("#B3261E"))
    }

    /** 存储往返校验。刻意用不会与真实数据冲突的键。 */
    private fun storageCheck(): SelfTest.Check = try {
        val probe = "__selftest__"
        val val1 = "v-${System.currentTimeMillis()}"
        store.putCred(probe, val1)
        val ok = store.getCred(probe) == val1
        store.clearCred(probe)
        SelfTest.Check("存储读写往返", ok, if (ok) "ok" else "read-back mismatch")
    } catch (t: Throwable) {
        SelfTest.Check("存储读写往返", false, t.message ?: t.javaClass.simpleName)
    }

    // ---------------- 状态渲染 ----------------

    private fun refreshStatus() {
        val a11y = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(packageName) == true

        val sess = store.getCred(Store.KEY_SESSDATA)
        val key = store.getCred(Store.KEY_API_KEY)
        val base = store.getCred(Store.KEY_API_BASE)
        val model = store.getCred(Store.KEY_API_MODEL)

        val (subs, sums, bytes) = store.stats()

        // 预填已保存的非敏感值，敏感值只显示掩码
        if (!base.isNullOrBlank() && baseInput.text.isNullOrBlank()) baseInput.setText(base)
        if (!model.isNullOrBlank() && modelInput.text.isNullOrBlank()) modelInput.setText(model)

        // 版本横幅放最前面：一眼确认手机上装的是哪一版。
        // 之前 versionCode 恒为 1，导致无法分辨实际安装的包，
        // 排查时反复对着旧包找问题 —— 这是必须消除的不确定性。
        val apk = packageManager.getPackageInfo(packageName, 0)
        statusView.text = buildString {
            append("⚙ 已安装版本：v${apk.versionName}（build ${apk.longVersionCode}）")
            append('\n')
            append("构建标识：DIAG-3")
            append('\n')
            append(if (a11y) "无障碍服务：已开启 ✓" else "无障碍服务：未开启 ✗  点下方按钮开启")
            append('\n')
            append("B站凭据：")
            append(if (sess != null) "已配置（${Text.mask(sess)}）✓" else "未配置 ✗  视频总结不可用")
            append('\n')
            append("模型：")
            append(if (key != null) "${model ?: LlmConfig.DEFAULT_MODEL} @ ${hostOf(base)} ✓" else "未配置 ✗  总结不可用")
            append('\n')
            append("缓存：字幕 $subs 条 / 摘要 $sums 条 / ${Text.humanBytes(bytes)}")
            append('\n')
            append("权限：仅 INTERNET（应用运行期唯一网络能力）")
        }

        // 诊断段：把解析器的内部状态直接摊在这里。
        // 手机上没有 logcat 可用，而「为什么没出球」的答案只存在于服务进程内存里。
        diagView.text = buildDiag()
    }

    /**
     * 渲染诊断文本。
     *
     * 服务未连接时 ReadingServiceHolder 为空 —— 这本身就是一条关键信息：
     * 说明无障碍服务确实没有在跑（用户以为开了，实际没开，或已被系统杀掉）。
     */
    private fun buildDiag(): CharSequence {
        val svc = ReadingServiceHolder.get()
            ?: return "服务未连接：无障碍服务未运行，或刚开启尚未生效（开关一次即可重新连接）。\n" +
                "此时不会有任何悬浮球 —— 这就是原因。"

        val (brief, fail) = svc.lastGrab()
        val sb = StringBuilder()
        sb.append("最近一次抓取：")
        sb.append(if (fail.isEmpty()) "成功\n" else "失败（$fail）\n")
        sb.append(brief).append('\n')
        sb.append("──────── 轨迹（最近在后）────────\n")
        val lines = svc.traceDump()
        if (lines.isEmpty()) {
            sb.append("（暂无。请切到微信打开文章页，停留 2 秒，再回到这里。）")
        } else {
            for (l in lines.takeLast(25)) sb.append(l).append('\n')
        }
        return sb.toString()
    }

    private fun hostOf(base: String?): String {
        if (base.isNullOrBlank()) return "?"
        return base.removePrefix("https://").removePrefix("http://").substringBefore('/')
    }

    /**
     * 跳转无障碍设置。
     * 部分 ROM 忽略直达参数，失败时回退到服务列表页。
     */
    private fun openAccessibilitySettings() {
        val args = Bundle().apply { putString("extra_component_name", packageName) }
        val direct = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", packageName)
            putExtra(":settings:show_fragment_args", args)
        }
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
    }

    // ---------------- 控件工厂 ----------------

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#202124"))
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#202124"))
        setPadding(0, 0, 0, pad(6))
    }

    private fun caption(t: String) = TextView(this).apply {
        text = t
        textSize = 11.5f
        setTextColor(Color.parseColor("#5F6368"))
        setLineSpacing(4f * density, 1f)
        setPadding(0, 0, 0, pad(6))
    }

    private fun input(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        textSize = 13f
        inputType = type
        setSingleLine(true)
    }

    private fun makeButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setOnClickListener { onClick() }
    }

    private fun space(dp: Int) = TextView(this).apply {
        text = ""
        height = pad(dp)
    }

    private fun pad(dp: Int) = (dp * density).toInt()

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }
}
