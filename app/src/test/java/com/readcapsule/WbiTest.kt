package com.readcapsule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Wbi 签名单元测试。
 *
 * 这是本工程唯一「算错了就整个视频功能失效」的模块，因此断言最密。
 *
 * 测试边界说明（必须诚实）：
 *  以下断言全部是**性质断言**（determinism / sensitivity / order-independence），
 *  不是**对拍断言**——沙箱与 CI 均无法访问 B站线上环境获取"标准答案"。
 *
 *  性质断言能抓住的 bug：
 *    · mixinKey 计算了但未参与签名（最隐蔽的假实现）
 *    · 参数未排序导致同一请求产生两个签名（服务端必然拒绝其一）
 *    · 非法字符未过滤（B站会以 -403 拒绝，但本地看不出来）
 *    · wts 未注入
 *
 *  性质断言**抓不住**的 bug：
 *    · MIXIN_KEY_ENC_TAB 重排表本身写错 —— 这个只能靠真实请求验证
 *      （见 verify.sh Stage 8 的 --net 网络冒烟）
 *
 *  这个边界必须写在测试里，否则绿灯会给出虚假信心。
 */
class WbiTest {

    /** 构造一对合法的 wbi 素材 URL（各含 32 位 hex 文件名）。 */
    private fun keyOf(a: String, b: String): String = Wbi.mixinKey(
        "https://i0.hdslb.com/bfs/wbi/$a.png",
        "https://i0.hdslb.com/bfs/wbi/$b.png"
    )

    private val A32 = "a".repeat(32)
    private val B32 = "b".repeat(32)

    private val key: String get() = keyOf(A32, B32)

    // ---------- mixinKey ----------

    @Test
    fun `mixinKey 长度恒为 32`() {
        assertEquals(32, key.length)
        assertEquals(32, keyOf("0".repeat(32), "f".repeat(32)).length)
    }

    @Test
    fun `mixinKey 确定性`() {
        assertEquals(key, key)
        assertEquals(keyOf(A32, B32), keyOf(A32, B32))
    }

    @Test
    fun `mixinKey 只由重排表选取有效字符`() {
        // 素材全为 'a' 与 'b'，故输出必然只含这两个字符。
        // 若重排表被写错成越界索引，会抛异常或产出其它字符，此断言即失败。
        assertTrue(key.all { it == 'a' || it == 'b' })
    }

    @Test
    fun `mixinKey 对素材敏感`() {
        assertNotEquals(keyOf(A32, B32), keyOf("c".repeat(32), B32))
        assertNotEquals(keyOf(A32, B32), keyOf(A32, "c".repeat(32)))
        // 交换顺序必须产生不同结果，否则说明 img/sub 位置被弄反而无人察觉
        assertNotEquals(keyOf(A32, B32), keyOf(B32, A32))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `素材过短时显式抛错而非静默产出短 key`() {
        Wbi.mixinKey("https://x/y.png", "https://x/z.png")
    }

    @Test
    fun `mixinKey 忽略 URL 查询串与扩展名`() {
        val clean = keyOf(A32, B32)
        val noisy = Wbi.mixinKey(
            "https://i0.hdslb.com/bfs/wbi/$A32.png?deadbeef",
            "https://i0.hdslb.com/bfs/wbi/$B32.png?wts=999"
        )
        assertEquals(clean, noisy)
    }

    // ---------- sign ----------

    @Test
    fun `签名确定性`() {
        val a = Wbi.sign(mapOf("bvid" to "BV1Nx411c7xx"), key, 1700000000L)
        val b = Wbi.sign(mapOf("bvid" to "BV1Nx411c7xx"), key, 1700000000L)
        assertEquals(a, b)
    }

    @Test
    fun `签名注入 wts 且输出 w_rid`() {
        val s = Wbi.sign(emptyMap(), key, 1700000000L)
        assertTrue("wts 未注入: $s", s.contains("wts=1700000000"))
        assertTrue("w_rid 缺失: $s", s.contains("w_rid="))
    }

    @Test
    fun `签名对时间戳敏感`() {
        val a = Wbi.sign(mapOf("a" to "1"), key, 1L)
        val b = Wbi.sign(mapOf("a" to "1"), key, 2L)
        assertNotEquals(a, b)
    }

    @Test
    fun `签名对 key 敏感`() {
        // 关键断言：mixinKey 必须真的参与运算。
        // 若实现里漏拼接 mixinKey，此断言失败 —— 这是静态审查抓不到的假实现。
        val k2 = keyOf("c".repeat(32), "d".repeat(32))
        assertNotEquals(
            Wbi.sign(mapOf("a" to "1"), key, 1L),
            Wbi.sign(mapOf("a" to "1"), k2, 1L)
        )
    }

    @Test
    fun `签名对业务参数敏感`() {
        assertNotEquals(
            Wbi.sign(mapOf("bvid" to "BV1Nx411c7xx"), key, 1L),
            Wbi.sign(mapOf("bvid" to "BV1Nx411c7yy"), key, 1L)
        )
    }

    @Test
    fun `签名与参数插入顺序无关`() {
        // TreeMap 排序的语义保证：HashMap 迭代顺序随机，
        // 若实现未排序，同一组参数会产出不同签名，服务端随机拒绝。
        val m1 = linkedMapOf("a" to "1", "b" to "2", "c" to "3")
        val m2 = linkedMapOf("c" to "3", "a" to "1", "b" to "2")
        val m3 = linkedMapOf("b" to "2", "c" to "3", "a" to "1")
        assertEquals(Wbi.sign(m1, key, 7L), Wbi.sign(m2, key, 7L))
        assertEquals(Wbi.sign(m1, key, 7L), Wbi.sign(m3, key, 7L))
    }

    @Test
    fun `参数按字典序排列`() {
        val s = Wbi.sign(mapOf("zzz" to "1", "aaa" to "2"), key, 5L)
        assertTrue("参数未排序: $s", s.indexOf("aaa=2") < s.indexOf("zzz=1"))
    }

    @Test
    fun `非法字符被过滤`() {
        // B站会剔除 '! ( ) * 后校验签名；若本地未过滤，服务端必然 -403。
        val dirty = Wbi.sign(mapOf("x" to "a!b'c(d)e*f"), key, 1L)
        val clean = Wbi.sign(mapOf("x" to "abcdef"), key, 1L)
        assertEquals(clean, dirty)
        assertFalse("非法字符未被剔除: $dirty", dirty.contains("!") || dirty.contains("'"))
    }

    @Test
    fun `空参数集不崩溃`() {
        assertTrue(Wbi.sign(emptyMap(), key, 1L).contains("w_rid="))
    }

    @Test
    fun `w_rid 为 32 位小写 hex`() {
        val s = Wbi.sign(mapOf("a" to "1"), key, 1L)
        val rid = s.substringAfter("w_rid=")
        assertEquals(32, rid.length)
        assertTrue("非 hex: $rid", rid.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `输出可直接拼入 URL`() {
        // 冒号、斜杠、空格必须已编码，否则拼进 URL 会破坏查询串
        val s = Wbi.sign(mapOf("t" to "a b", "u" to "http://x/y"), key, 1L)
        assertFalse("含裸空格: $s", s.contains(' '))
        assertTrue("空格未编码为 %20: $s", s.contains("a%20b"))
    }
}
