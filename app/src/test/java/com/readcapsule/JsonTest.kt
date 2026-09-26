package com.readcapsule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 零依赖 JSON 解析器单元测试。
 *
 * 核心要求：**语法错误必须显式失败**，绝不能返回 null 冒充"解析成功但值为空"。
 * 这两者语义完全不同——前者应触发用户可见错误，后者会造成静默空摘要。
 */
class JsonTest {

    // ---------- 基础类型 ----------

    @Test
    fun `解析对象`() {
        val v = Json.obj(Json.parse("""{"a":1,"b":"x"}"""))
        assertEquals(1.0, v?.get("a"))
        assertEquals("x", v?.get("b"))
    }

    @Test
    fun `解析数组`() {
        val a = Json.arr(Json.parse("""[1,"two",true,null]"""))
        assertEquals(4, a?.size)
        assertEquals(1.0, a?.get(0))
        assertEquals("two", a?.get(1))
        assertEquals(true, a?.get(2))
        assertNull(a?.get(3))
    }

    @Test
    fun `解析空对象与空数组`() {
        assertEquals(0, Json.obj(Json.parse("{}"))?.size)
        assertEquals(0, Json.arr(Json.parse("[]"))?.size)
    }

    @Test
    fun `解析负数与小数`() {
        assertEquals(-3L, Json.long(Json.parse("-3")))
        assertEquals(0L, Json.long(Json.parse("0.9")))
        assertEquals(12L, Json.long(Json.parse("12.7")))
    }

    @Test
    fun `解析科学计数法`() {
        assertEquals(1500L, Json.long(Json.parse("1.5e3")))
    }

    @Test
    fun `长数字不丢精度（时间戳场景）`() {
        // 1700000000000 超出 float 精度，若实现用 float 会变成 1699999999999
        assertEquals(1700000000000L, Json.long(Json.parse("1700000000000")))
    }

    // ---------- 转义 ----------

    @Test
    fun `解析全部转义序列`() {
        // 用单引号原始串构造 JSON，避免与 Kotlin 自身的转义规则相互干扰
        val json = """{"k":"a\nb\tc\rd\"e\\f\/g"}"""
        val v = Json.parse(json)
        assertFalse("解析失败: $v", v is Json.ParseFail)
        assertEquals("a\nb\tc\rd\"e\\f/g", Json.pathStr(v, "k"))
    }

    @Test
    fun `解析 unicode 转义`() {
        assertEquals("中", Json.str(Json.parse(""""\u4e2d"""")))
        assertEquals("A", Json.str(Json.parse(""""\u0041"""")))
    }

    @Test
    fun `解析中文与 emoji 原样保留`() {
        assertEquals("核心结论：很好", Json.str(Json.parse(""""核心结论：很好"""")))
        assertEquals("🎬📄", Json.str(Json.parse(""""🎬📄"""")))
    }

    @Test
    fun `quote 转义后能被解析回原值`() {
        val inputs = listOf(
            "simple",
            "has \"quotes\"",
            "line1\nline2",
            "tab\there",
            "back\\slash",
            "中文🎬",
            "\u0000\u001f",
            "mixed \\\" \n 中 🎬"
        )
        for (s in inputs) {
            val json = """{"k":${Json.quote(s)}}"""
            val parsed = Json.parse(json)
            assertFalse("解析失败: $s", parsed is Json.ParseFail)
            assertEquals("往返不一致: [$s]", s, Json.pathStr(parsed, "k"))
        }
    }

    // ---------- 路径取值 ----------

    @Test
    fun `嵌套路径下钻`() {
        val v = Json.parse("""{"a":{"b":{"c":{"d":42}}}}""")
        assertEquals(42L, Json.long(Json.path(v, "a", "b", "c", "d")))
    }

    @Test
    fun `数组下标路径`() {
        val v = Json.parse("""{"list":[{"n":"first"},{"n":"second"}]}""")
        assertEquals("first", Json.pathStr(v, "list", "0", "n"))
        assertEquals("second", Json.pathStr(v, "list", "1", "n"))
    }

    @Test
    fun `B站字幕响应结构端到端`() {
        // 真实响应结构的最小复刻
        val raw = """
        {
          "code": 0,
          "message": "0",
          "data": {
            "isLogin": true,
            "wbi_img": {
              "img_url": "https://i0.hdslb.com/bfs/wbi/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",
              "sub_url": "https://i0.hdslb.com/bfs/wbi/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png"
            },
            "subtitle": {
              "subtitles": [
                {"lan":"ai-zh","lan_doc":"中文（自动生成）","subtitle_url":"//aisubtitle.hdslb.com/x.json"},
                {"lan":"en-US","lan_doc":"English","subtitle_url":"//aisubtitle.hdslb.com/y.json"}
              ]
            }
          }
        }
        """.trimIndent()

        val v = Json.parse(raw)
        assertFalse(v is Json.ParseFail)
        assertEquals(0L, Json.long(Json.path(v, "code")))
        assertEquals(true, Json.path(v, "data", "isLogin"))
        assertEquals("ai-zh", Json.pathStr(v, "data", "subtitle", "subtitles", "0", "lan"))
        assertEquals(2, Json.pathArr(v, "data", "subtitle", "subtitles").size)
        assertEquals(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            Json.pathStr(v, "data", "wbi_img", "sub_url")?.substringAfterLast('/')?.substringBeforeLast('.')
        )
    }

    @Test
    fun `字幕 body 数组结构`() {
        val v = Json.parse("""{"body":[{"from":0.5,"to":2.3,"content":"第一句"},{"from":2.3,"to":4.1,"content":"第二句"}]}""")
        val body = Json.pathArr(v, "body")
        assertEquals(2, body.size)
        assertEquals("第一句", Json.pathStr(body[0], "content"))
        assertEquals(0.5, Json.path(body[0], "from"))
    }

    @Test
    fun `缺失路径返回 null 而非崩溃`() {
        val v = Json.parse("""{"a":1}""")
        assertNull(Json.path(v, "nope"))
        assertNull(Json.path(v, "a", "b", "c"))
        assertNull(Json.pathStr(v, "a", "b"))
    }

    @Test
    fun `数组越界返回 null`() {
        val v = Json.parse("""[1,2]""")
        assertEquals(0, Json.pathArr(v, "5").size)
    }

    @Test
    fun `对空集合取路径返回空列表而非崩溃`() {
        assertEquals(0, Json.pathArr(Json.parse("{}"), "missing").size)
    }

    // ---------- 错误显式化 ----------

    @Test
    fun `语法错误返回 ParseFail 而非 null`() {
        val cases = listOf(
            """{"a":}""",
            """{"a":1,}""",
            """{"a"1}""",
            """{"a":1""",
            """[1,2""",
            """{"a":"unterminated}""",
            """not json at all""",
            """{"a":.5}""",
            """"""
        )
        for (c in cases) {
            assertTrue("应显式失败: [$c] -> ${Json.parse(c)}", Json.parse(c) is Json.ParseFail)
        }
    }

    @Test
    fun `尾随内容必须拒绝`() {
        // 关键：截断的 HTTP 响应常表现为"合法 JSON + 残余字节"。
        // 若容忍尾随，会把不完整响应当成完整结果，产出基于残缺数据的摘要。
        assertTrue(Json.parse("""{"a":1} garbage""") is Json.ParseFail)
        assertTrue(Json.parse("""{"a":1}{"b":2}""") is Json.ParseFail)
        assertTrue(Json.parse("""[1,2] extra""") is Json.ParseFail)
    }

    @Test
    fun `JSON null 与 ParseFail 可区分`() {
        val n = Json.parse("null")
        assertNull(n)
        assertFalse("null 被误判为解析失败", n is Json.ParseFail)
    }

    @Test
    fun `ParseFail 携带可诊断原因`() {
        val f = Json.parse("""{"a":}""")
        assertTrue(f is Json.ParseFail)
        assertTrue("原因为空", (f as Json.ParseFail).reason.isNotBlank())
    }

    @Test
    fun `合法 JSON 不会被误判为失败`() {
        for (s in listOf("{}", "[]", "null", "true", "false", "0", "-1.5", "\"x\"", """{"a":[{},[]]}""")) {
            assertFalse("被误判: $s", Json.parse(s) is Json.ParseFail)
        }
    }

    // ---------- 顶层原始值 ----------

    @Test
    fun `顶层布尔与 null`() {
        assertEquals(true, Json.parse("true"))
        assertEquals(false, Json.parse("false"))
        assertNull(Json.parse("null"))
    }

    @Test
    fun `顶层字符串`() {
        assertEquals("hello", Json.str(Json.parse(""""hello"""")))
    }

    // ---------- 深度 ----------

    @Test
    fun `深层嵌套不栈溢出`() {
        val depth = 200
        val open = "{".repeat(depth).let { it.chunked(1).joinToString("") { _ -> "" } } +
            (0 until depth).joinToString("") { """{"k":""" } + "1" + "}".repeat(depth)
        val v = Json.parse(open)
        assertFalse("深度 $depth 解析失败: ${(v as? Json.ParseFail)?.reason}", v is Json.ParseFail)
    }
}
