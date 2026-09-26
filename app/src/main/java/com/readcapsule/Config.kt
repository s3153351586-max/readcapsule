package com.readcapsule

/**
 * 全局常量与形态定义。
 *
 * 集中常量的目的：投毒目标应用的 UI 结构/文案会变，全部可调参数收敛到单文件，
 * 平台改版时只需改此一处，不必在 12 个文件里搜索魔法数字。
 */
object Config {

    /** 悬浮窗纵向初始偏移（px）。避开状态栏与常见顶部导航。 */
    const val OVERLAY_Y = 320

    /** 无障碍事件去抖窗口。视频页/长文页滚动会高频触发 contentChanged。 */
    const val DEBOUNCE_MS = 250L

    /** 无障碍节点树遍历上限。防止异常深树阻塞主线程，导致目标应用掉帧。 */
    const val MAX_NODES = 6000
    const val MAX_DEPTH = 40

    /** 长文抓取的下限：短于此长度的页面视为非文章页，不显示胶囊。 */
    const val MIN_ARTICLE_CHARS = 400

    /** 送入 LLM 的正文字符上限。超出则截断并显式标注（见 LlmClient）。 */
    const val MAX_PAYLOAD_CHARS = 24_000

    /** 字幕文本超出此值时按时间轴等距抽样，而非简单截尾（保留首尾结论段）。 */
    const val SUBTITLE_SAMPLE_THRESHOLD = 24_000

    /** 磁盘缓存有效期：同一 BV 号 7 天内不重复拉取字幕，避免重复计费与限流。 */
    const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * 目标应用白名单 -> 形态。
     *
     * 视频形态只收录长视频平台：抖音/快手类 15s 快餐视频无总结刚需，
     * 纳入只会增加误命中面与无效 API 调用。
     */
    val PACKAGE_MODE: Map<String, Mode> = mapOf(
        "tv.danmaku.bili" to Mode.VIDEO,             // B站
        "com.bilibili.app.in" to Mode.VIDEO,         // B站国际版
        "com.tencent.mm" to Mode.ARTICLE,            // 微信（公众号文章）
        "com.zhihu.android" to Mode.ARTICLE,         // 知乎
        "com.ss.android.article.news" to Mode.ARTICLE, // 今日头条
        "com.tencent.mtt" to Mode.ARTICLE            // QQ浏览器（含公众号）
    )

    /** 供无障碍配置与二次校验共用，保证两处白名单不会漂移。 */
    val TARGET_PACKAGES: Set<String> = PACKAGE_MODE.keys

    /** B站字幕接口的固定参数。 */
    const val BILI_REFERER = "https://www.bilibili.com"
    const val BILI_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** 网络超时。移动网络下 10s 足够；超时即显式报错，不做无限重试。 */
    const val CONNECT_TIMEOUT_MS = 8_000
    const val READ_TIMEOUT_MS = 20_000
}

/** 胶囊形态。由前台包名决定，用户无需手动切换。 */
enum class Mode { ARTICLE, VIDEO, NONE }
