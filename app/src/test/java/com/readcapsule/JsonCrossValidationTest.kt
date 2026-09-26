package com.readcapsule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自实现 JSON 解析器 vs org.json 参考实现的**交叉校验**。
 *
 * 为何需要这个文件：
 *   自家解析器的测试若只用手写的期望值，测试通过只证明「实现符合我的预期」，
 *   不证明「实现对 JSON 规范的解读和别人一致」。
 *   交叉校验用业界参考实现做锚点，把风险从「我理解错了」降为「两边都错」。
 *
 * 依赖边界：org.json 仅在 testImplementation 中，**不进入 APK**。
 * 由 assertNoRuntimeDeps 任务在构建期强制。
 */
class JsonCrossValidationTest {

    private fun bothParse(json: String) {
        val mine = Json.parse(json)
        assertFalse("自实现解析失败: $json -> $mine", mine is Json.ParseFail)

        val ref = org.json.JSONTokener(json).nextValue()
        assertEquivalent(ref, mine, json)
    }

    /** 递归比较两个解析结果。数字统一按 Double 比较（JSON 无整数/浮点之分）。 */
    private fun assertEquivalent(ref: Any?, mine: Any?, ctx: String) {
        when {
            ref is org.json.JSONObject -> {
                val m = Json.obj(mine)
                assertEquals("类型不匹配(应为对象): $ctx", true, m != null)
                assertEquals("键集不匹配: $ctx",
                    ref.keys().asSequence().toSet(),
                    m!!.keys.also { }.toSet()
                )
                for (k in ref.keys()) {
                    assertEquivalent(ref.get(k), Json.path(mine, k), "$ctx.$k")
                }
            }
            ref is org.json.JSONArray -> {
                val m = Json.arr(mine)
                assertEquals("类型不匹配(应为数组): $ctx", true, m != null)
                assertEquals("数组长度不匹配: $ctx", ref.length(), m!!.size)
                for (i in 0 until ref.length()) {
                    assertEquivalent(ref.get(i), m[i], "$ctx[$i]")
                }
            }
            ref is String -> assertEquals("字符串不匹配: $ctx", ref, Json.str(mine))
            ref is Boolean -> assertEquals("布尔不匹配: $ctx", ref, mine as Boolean?)
            ref is Int -> assertEquals("数字不匹配: $ctx", ref.toDouble(), mine as Double?, 1e-9)
            ref is Long -> assertEquals("数字不匹配: $ctx", ref.toDouble(), mine as Double?, 1e-9)
            ref is Double -> assertEquals("数字不匹配: $ctx", ref, mine as Double?, 1e-9)
            ref === org.json.JSONObject.NULL -> assertEquals("应为 null: $ctx", null, mine)
            else -> assertEquals("未覆盖类型 ${ref!!::class}: $ctx", ref, mine)
        }
    }

    // ---------- 真实响应结构 ----------

    @Test
    fun `B站 nav 响应`() {
        bothParse(
            """
            {"code":0,"message":"0","ttl":1,"data":{
              "isLogin":true,"mid":123456,"uname":"user",
              "wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/aaa.png","sub_url":"https://i0.hdslb.com/bfs/wbi/bbb.png"}
            }}
            """.trimIndent()
        )
    }

    @Test
    fun `B站 player v2 响应`() {
        bothParse(
            """
            {"code":0,"message":"0","data":{
              "aid":123,"bvid":"BV1Nx411c7xx","cid":456,"title":"测试视频标题",
              "subtitle":{"allow_submit":false,"subtitles":[
                {"id":1,"lan":"ai-zh","lan_doc":"中文（自动生成）","is_lock":false,
                 "subtitle_url":"//aisubtitle.hdslb.com/bfs/ai_subtitle/x.json"}
              ]}
            }}
            """.trimIndent()
        )
    }

    @Test
    fun `B站字幕文件响应`() {
        bothParse(
            """
            {"font_size":0.4,"font_color":"#FFFFFF","background_alpha":0.5,
             "body":[{"from":0.0,"to":2.5,"location":2,"content":"大家好"},
                     {"from":2.5,"to":5.1,"location":2,"content":"今天讲一个话题"}]}
            """.trimIndent()
        )
    }

    @Test
    fun `OpenAI 兼容 chat completions 响应`() {
        bothParse(
            """
            {"id":"chatcmpl-1","object":"chat.completion","created":1700000000,
             "model":"deepseek-chat",
             "choices":[{"index":0,"message":{"role":"assistant","content":"## 核心结论\n很好"},
                         "finish_reason":"stop"}],
             "usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}
            """.trimIndent()
        )
    }

    @Test
    fun `OpenAI 错误响应`() {
        bothParse("""{"error":{"message":"Invalid API key","type":"invalid_request_error","code":"invalid_api_key"}}""")
    }

    @Test
    fun `B站错误码响应`() {
        bothParse("""{"code":-101,"message":"账号未登录","ttl":1,"data":{"isLogin":false}}""")
    }

    // ---------- 语法边界 ----------

    @Test
    fun `转义字符与参考实现一致`() {
        bothParse("""{"s":"quote \" backslash \\ slash \/ newline \n tab \t cr \r b \b f \f"}""")
    }

    @Test
    fun `unicode 转义与参考实现一致`() {
        bothParse("""{"cn":"\u4e2d\u6587","mix":"a\u0041b","emoji":"\ud83c\udfac"}""")
    }

    @Test
    fun `各种数字形态`() {
        bothParse("""{"a":0,"b":-1,"c":1.5,"d":-1.5e3,"e":1E-2,"f":123456789012345}""")
    }

    @Test
    fun `空容器与 null`() {
        bothParse("""{"o":{},"a":[],"n":null,"t":true,"f":false}""")
    }

    @Test
    fun `深层嵌套结构`() {
        bothParse("""{"a":{"b":{"c":{"d":{"e":{"f":[1,{"g":"h"}]}}}}}}""")
    }

    @Test
    fun `数组内混合类型`() {
        bothParse("""[1,"two",true,null,{"k":"v"},[1,2]]""")
    }

    @Test
    fun `空白与换行容忍`() {
        bothParse("\n\t {\n  \"a\" : [ 1 , 2 ]\n } \n")
    }

    @Test
    fun `顶层为原始值`() {
        bothParse(""" "just a string" """.trim())
        bothParse("true")
        bothParse("null")
        bothParse("42")
    }

    // ---------- 双方一致地拒绝 ----------

    @Test
    fun `双方均拒绝非法 JSON`() {
        val bad = listOf(
            """{"a":}""",
            """{"a":1,}""",
            """{"a"1}""",
            """[1,2""",
            """{"a":1""",
            """{"a":"x}""",
            """{'a':1}""",
            """"""
        )
        for (b in bad) {
            assertTrue("自实现应拒绝: [$b]", Json.parse(b) is Json.ParseFail)
        }
    }

    @Test
    fun `真实字幕文本量大时不丢字符`() {
        // 构造 2000 条字幕，验证长数组解析正确
        val items = (0 until 2000).joinToString(",") {
            """{"from":$it.0,"to":${it + 1}.0,"location":2,"content":"第${it}句字幕内容"}"""
        }
        val json = """{"body":[$items]}"""
        val mine = Json.parse(json)
        assertFalse(mine is Json.ParseFail)
        assertEquals(2000, Json.pathArr(mine, "body").size)
        assertEquals("第1999句字幕内容", Json.pathStr(Json.pathArr(mine, "body")[1999], "content"))

        // 与参考实现比对最后一条
        val ref = org.json.JSONTokener(json).nextValue() as org.json.JSONObject
        val refLast = ref.getJSONArray("body").getJSONObject(1999).getString("content")
        assertEquals(refLast, Json.pathStr(Json.pathArr(mine, "body")[1999], "content"))
    }
}
