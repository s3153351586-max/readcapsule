package com.readcapsule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本指纹与凭据形态测试。
 *
 * 指纹是摘要缓存的键，其正确性直接决定**计费**：
 *  - 碰撞 -> 读到别的视频的摘要（静默错误）
 *  - 过度敏感 -> 缓存永不命中（每次点击都花 token）
 * 因此两个方向都要断言。
 */
class TextTest {

    // ---------- fingerprint ----------

    @Test
    fun `指纹确定性`() {
        assertEquals(Text.fingerprint("a", "b"), Text.fingerprint("a", "b"))
    }

    @Test
    fun `指纹为 32 位小写 hex`() {
        val f = Text.fingerprint("x")
        assertEquals(32, f.length)
        assertTrue("非 hex: $f", f.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `分隔符防止拼接碰撞`() {
        // 关键：若实现是简单字符串拼接，"a"+"b" 与 "ab"+"" 会碰撞。
        // 用 0x1F 作分隔符可消除该歧义。
        assertNotEquals(Text.fingerprint("a", "b"), Text.fingerprint("ab", ""))
        assertNotEquals(Text.fingerprint("a", "b"), Text.fingerprint("", "ab"))
        assertNotEquals(Text.fingerprint("ab", "c"), Text.fingerprint("a", "bc"))
    }

    @Test
    fun `指纹对内容敏感`() {
        assertNotEquals(Text.fingerprint("a"), Text.fingerprint("b"))
    }

    @Test
    fun `空指纹不崩溃`() {
        assertEquals(32, Text.fingerprint().length)
        assertNotEquals(Text.fingerprint(), Text.fingerprint(""))
    }

    // ---------- summaryKey ----------

    @Test
    fun `summaryKey 确定性`() {
        assertEquals(
            Text.summaryKey("video:BV1", "content", "deepseek-chat"),
            Text.summaryKey("video:BV1", "content", "deepseek-chat")
        )
    }

    @Test
    fun `summaryKey 对模型敏感`() {
        // 换模型必须换键：不同模型的摘要质量不同，复用旧摘要是错的
        assertNotEquals(
            Text.summaryKey("video:BV1", "c", "deepseek-chat"),
            Text.summaryKey("video:BV1", "c", "glm-4-flash")
        )
    }

    @Test
    fun `summaryKey 对内容敏感`() {
        assertNotEquals(
            Text.summaryKey("video:BV1", "content-1", "m"),
            Text.summaryKey("video:BV1", "content-2", "m")
        )
    }

    @Test
    fun `summaryKey 对域敏感（同一文本的长文与视频摘要不串）`() {
        assertNotEquals(
            Text.summaryKey("article", "same text", "m"),
            Text.summaryKey("video:BV1", "same text", "m")
        )
    }

    @Test
    fun `summaryKey 超长内容走采样分支且稳定`() {
        // 超过 16000 字符时切换为「首8K+长度+尾8K」采样
        val long1 = "x".repeat(30_000)
        val long2 = "x".repeat(30_000)
        assertEquals(Text.summaryKey("v", long1, "m"), Text.summaryKey("v", long2, "m"))

        // 长度不同 -> 键必不同（长度已进采样）
        assertNotEquals(
            Text.summaryKey("v", "x".repeat(30_000), "m"),
            Text.summaryKey("v", "x".repeat(30_001), "m")
        )
    }

    @Test
    fun `summaryKey 采样对尾部变化敏感`() {
        // 尾部含有视频结论，必须影响键
        val a = "h".repeat(10_000) + "MID" + "tail-AAA"
        val b = "h".repeat(10_000) + "MID" + "tail-BBB"
        assertNotEquals(Text.summaryKey("v", a, "m"), Text.summaryKey("v", b, "m"))
    }

    @Test
    fun `summaryKey 采样对首部变化敏感`() {
        val a = "head-AAA" + "MID" + "t".repeat(10_000)
        val b = "head-BBB" + "MID" + "t".repeat(10_000)
        assertNotEquals(Text.summaryKey("v", a, "m"), Text.summaryKey("v", b, "m"))
    }

    // ---------- cleanBody ----------

    @Test
    fun `折叠连续空格与制表符`() {
        assertEquals("a b c", Text.cleanBody("a   b\t\tc"))
    }

    @Test
    fun `折叠三个以上空行`() {
        assertEquals("a\n\nb", Text.cleanBody("a\n\n\n\n\nb"))
    }

    @Test
    fun `保留单个空行（段落分隔）`() {
        assertEquals("a\n\nb", Text.cleanBody("a\n\nb"))
    }

    @Test
    fun `不破坏中文标点与数字`() {
        val s = "价格 12,345.67 元，涨幅 +3.5%；参考 A 股。"
        assertEquals(s, Text.cleanBody(s))
    }

    @Test
    fun `去除首尾空白`() {
        assertEquals("x", Text.cleanBody("   \n  x  \n  "))
    }

    @Test
    fun `不折叠不间断空格以外的特殊字符`() {
        // U+00A0 属于空白，应折叠
        assertEquals("a b", Text.cleanBody("a\u00A0b"))
    }

    // ---------- SESSDATA 形态 ----------

    @Test
    fun `正常 SESSDATA 通过`() {
        assertTrue(Text.looksLikeSessdata("abcd1234efgh5678ijkl"))
        assertTrue(Text.looksLikeSessdata("%2Cabcdef1234567890ab"))
    }

    @Test
    fun `含等号的整条 Cookie 被拒`() {
        // 最常见的误操作：把 "SESSDATA=xxx" 整条粘进来
        assertFalse(Text.looksLikeSessdata("SESSDATA=abcd1234efgh5678"))
    }

    @Test
    fun `含分号的多字段 Cookie 被拒`() {
        assertFalse(Text.looksLikeSessdata("SESSDATA=abc; bili_jct=def; DedeUserID=1"))
    }

    @Test
    fun `过短输入被拒`() {
        assertFalse(Text.looksLikeSessdata("abc"))
        assertFalse(Text.looksLikeSessdata(""))
    }

    @Test
    fun `含空格或换行被拒`() {
        assertFalse(Text.looksLikeSessdata("abcd 1234 efgh 5678"))
        assertFalse(Text.looksLikeSessdata("abcd1234efgh5678\nijkl"))
    }

    // ---------- mask ----------

    @Test
    fun `掩码不泄露完整凭据`() {
        val secret = "abcd1234efgh5678ijkl"
        val m = Text.mask(secret)
        assertNotEquals(secret, m)
        assertFalse("掩码泄露了中段: $m", m.contains("1234efgh"))
        assertTrue(m.contains("…"))
    }

    @Test
    fun `短凭据全部打码`() {
        assertEquals("****", Text.mask("abcd"))
        assertEquals("", Text.mask(""))
    }

    @Test
    fun `掩码保留首尾各 4 位以便用户辨认`() {
        val m = Text.mask("abcdXXXXefgh")
        assertTrue(m.startsWith("abcd"))
        assertTrue(m.endsWith("efgh"))
    }

    // ---------- humanBytes ----------

    @Test
    fun `字节数可读化`() {
        assertEquals("512 B", Text.humanBytes(512))
        assertEquals("1 KB", Text.humanBytes(1024))
        assertEquals("2 MB", Text.humanBytes(2L * 1024 * 1024))
    }
}
