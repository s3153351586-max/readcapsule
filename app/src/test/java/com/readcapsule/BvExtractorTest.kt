package com.readcapsule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BV 号提取单元测试。
 *
 * 最关键的断言是「歧义必须显式失败」——因为这里的失败模式是
 * **静默产出错误摘要**（总结 A 视频却返回 B 视频内容），
 * 用户无法从摘要本身察觉。宁可多一次点击，不可产出错误结果。
 */
class BvExtractorTest {

    private fun found(texts: List<String>): String? =
        (BvExtractor.extract(texts) as? BvExtractor.Result.Found)?.bv

    private fun isAmbiguous(texts: List<String>): Boolean =
        BvExtractor.extract(texts) is BvExtractor.Result.Ambiguous

    private fun isNotFound(texts: List<String>): Boolean =
        BvExtractor.extract(texts) is BvExtractor.Result.NotFound

    // ---------- 正常路径 ----------

    @Test
    fun `单次出现即可命中`() {
        assertEquals("BV1Nx411c7xx", found(listOf("BV1Nx411c7xx")))
    }

    @Test
    fun `从标题中嵌入的 BV 号命中`() {
        val t = listOf("【4K HDR】BV1Nx411c7xx 深度评测：这可能是今年最值得买的手机")
        assertEquals("BV1Nx411c7xx", found(t))
    }

    @Test
    fun `频次加权：出现更多的候选胜出`() {
        // 当前视频通常在标题/描述/分享文案中重复出现，推荐位只出现一次
        val texts = listOf(
            "推荐：BV1aaaaaaaaa",          // 推荐流，1 次
            "BV1Nx411c7xx",                 // 当前视频，3 次
            "标题里也有 BV1Nx411c7xx",
            "描述里还有 BV1Nx411c7xx"
        )
        assertEquals("BV1Nx411c7xx", found(texts))
    }

    @Test
    fun `并列时保持首次出现顺序稳定`() {
        // 频次并列 -> Ambiguous，但候选顺序必须可复现（首次出现序）
        val r1 = BvExtractor.extract(listOf("BV1aaaaaaaaa", "BV1bbbbbbbbb"))
        val r2 = BvExtractor.extract(listOf("BV1aaaaaaaaa", "BV1bbbbbbbbb"))
        assertEquals(r1, r2)
    }

    @Test
    fun `编码字符集接受 base58 全字符`() {
        // BV 号字符集为 base58（无 0 O I l）
        assertEquals("BV1Zy9wXabcd", found(listOf("BV1Zy9wXabcd")))
    }

    // ---------- 歧义必须显式失败 ----------

    @Test
    fun `两个候选各出现一次时判定为歧义`() {
        assertTrue(isAmbiguous(listOf("BV1aaaaaaaaa", "BV1bbbbbbbbb")))
    }

    @Test
    fun `歧义携带候选供用户选择`() {
        val r = BvExtractor.extract(listOf("BV1aaaaaaaaa", "BV1bbbbbbbbb")) as BvExtractor.Result.Ambiguous
        assertEquals(2, r.candidates.size)
        assertTrue(r.candidates.containsAll(listOf("BV1aaaaaaaaa", "BV1bbbbbbbbb")))
    }

    @Test
    fun `歧义候选数上限为 3`() {
        // 推荐流可能一次带 10+ 个 BV 号，全列出来会让卡片无法阅读
        val many = (0 until 10).map { "BV1${('a' + it)}aaaaaaaa" }
        val r = BvExtractor.extract(many)
        assertTrue(r is BvExtractor.Result.Ambiguous)
        assertTrue("候选未截断: ${(r as BvExtractor.Result.Ambiguous).candidates.size}",
            r.candidates.size <= 3)
    }

    // ---------- 长度与字符集校验 ----------

    @Test
    fun `9 位不匹配`() {
        assertTrue(isNotFound(listOf("BV1Nx411c7x")))
    }

    @Test
    fun `11 位不匹配`() {
        // 关键：11 位时正则必须整体拒绝，而非从长串中截出前 10 位
        assertTrue(isNotFound(listOf("BV1Nx411c7xxz")))
    }

    @Test
    fun `含数字 0 不匹配`() {
        // base58 无 '0'
        assertTrue(isNotFound(listOf("BV01Nx411c7")))
    }

    @Test
    fun `含大写 O 不匹配`() {
        assertTrue(isNotFound(listOf("BVO1Nx411c7")))
    }

    @Test
    fun `缺 BV 前缀不匹配`() {
        assertTrue(isNotFound(listOf("1Nx411c7xx")))
    }

    @Test
    fun `小写 bv 前缀不匹配`() {
        // B站实际格式为大写 BV
        assertTrue(isNotFound(listOf("bv1Nx411c7xx")))
    }

    @Test
    fun `空输入`() {
        assertTrue(isNotFound(emptyList()))
    }

    @Test
    fun `无 BV 号的普通文本`() {
        assertTrue(isNotFound(listOf("这是一个普通视频标题", "分享给你")))
    }

    // ---------- 边界 ----------

    @Test
    fun `同一 BV 号重复出现只计一个候选`() {
        val r = BvExtractor.extract(listOf("BV1Nx411c7xx", "BV1Nx411c7xx"))
        assertTrue(r is BvExtractor.Result.Found)
    }

    @Test
    fun `紧邻的长串被完整匹配而非截断`() {
        // 确保正则不会在 10 位处停下导致尾随字符被忽略
        assertTrue(isNotFound(listOf("xBV1Nx411c7xxy")))
    }

    @Test
    fun `多个不同 BV 号但一个明显高频`() {
        val texts = listOf(
            "BV1aaaaaaaaa", "BV1bbbbbbbbb", "BV1cccccccccc",
            "BV1Nx411c7xx", "BV1Nx411c7xx", "BV1Nx411c7xx", "BV1Nx411c7xx"
        )
        assertEquals("BV1Nx411c7xx", found(texts))
    }

    // ---------- fromUrl ----------

    @Test
    fun `从标准视频 URL 提取`() {
        assertEquals("BV1Nx411c7xx", BvExtractor.fromUrl("https://www.bilibili.com/video/BV1Nx411c7xx"))
    }

    @Test
    fun `从带分P参数的 URL 提取`() {
        assertEquals("BV1Nx411c7xx",
            BvExtractor.fromUrl("https://www.bilibili.com/video/BV1Nx411c7xx?p=2&t=120"))
    }

    @Test
    fun `从短链形式的 b23 tv 提取（无 BV 时返回 null）`() {
        // b23.tv 短链不含 BV 号，必须返回 null 而非猜一个
        assertEquals(null, BvExtractor.fromUrl("https://b23.tv/abcdEF"))
    }

    @Test
    fun `URL 无 BV 号返回 null`() {
        assertEquals(null, BvExtractor.fromUrl("https://www.bilibili.com/"))
    }
}
