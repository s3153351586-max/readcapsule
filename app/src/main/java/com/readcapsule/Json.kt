package com.readcapsule

/**
 * 零依赖 JSON 解析器。
 *
 * 为何不引 Gson/Moshi/kotlinx.serialization：
 *  - 工程只解析 3 个固定结构的响应（nav / player / LLM）
 *  - 任一库都会打破「零运行时依赖」契约，引入 ~300KB 体积与混淆规则
 *  - LLM 返回值本身不可信，需要宽容解析 + 显式降级，强类型映射反而是负担
 *
 * 这是一个约 180 行的递归下降解析器，覆盖 JSON 全语法。
 * 兼容性策略：解析失败返回 null（显式），由调用方转成用户可见错误，不静默。
 */
object Json {

    /**
     * 解析 JSON 文本。
     * @return 根值（Map/List/String/Double/Boolean/null）；语法错误返回 [ParseFail]
     */
    fun parse(text: String): Any? {
        val p = P(text)
        return try {
            p.skipWs()
            val v = p.value()
            p.skipWs()
            if (p.i < p.s.length) return ParseFail("trailing content at ${p.i}")
            v
        } catch (t: Throwable) {
            ParseFail(t.message ?: "parse error")
        }
    }

    /** 解析失败标记。与合法的 JSON null 区分开 —— 二者语义完全不同。 */
    data class ParseFail(val reason: String)

    // ---- 类型安全的取值助手：全部返回可空，缺失即 null，调用方显式处理 ----

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    fun arr(v: Any?): List<Any?>? = v as? List<Any?>

    fun str(v: Any?): String? = v as? String

    fun long(v: Any?): Long? = when (v) {
        is Double -> v.toLong()
        is Long -> v
        is String -> v.toLongOrNull()
        else -> null
    }

    /**
     * 按路径取值：path 为 key 序列，逐层下钻，任一层缺失返回 null。
     *
     * 路径段为纯数字且当前层是数组时，按数组下标解释（覆盖 `choices[0].message`
     * 这类 JSONPath 风格访问）；否则按对象键解释。如此 `{"0":"x"}` 这种以数字
     * 作键的对象仍可正常下钻，两种语义不冲突。
     *
     * 注：LlmClient 取 LLM 响应的 `choices[0].message.content` 依赖本行为；
     * 在支持数字下标之前，该调用恒返回 null，导致摘要功能整条链路失效。
     */
    fun path(root: Any?, vararg path: String): Any? {
        var cur: Any? = root
        for (k in path) {
            val idx = k.toIntOrNull()
            cur = if (idx != null && cur is List<*>) {
                cur.getOrNull(idx) ?: return null
            } else {
                val m = obj(cur) ?: return null
                m[k] ?: return null
            }
        }
        return cur
    }

    /** 便利：按路径取字符串。 */
    fun pathStr(root: Any?, vararg path: String): String? = str(path(root, *path))

    /** 便利：按路径取对象列表。 */
    @Suppress("UNCHECKED_CAST")
    fun pathArr(root: Any?, vararg path: String): List<Any?> {
        return arr(path(root, *path)) ?: emptyList<Any?>()
    }

    private class P(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalStateException("unexpected end")
            return when (s[i]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> numberValue()
            }
        }

        fun objectValue(): Map<String, Any?> {
            expect('{')
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return m }
            while (true) {
                skipWs()
                val k = stringValue()
                skipWs()
                expect(':')
                m[k] = value()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw IllegalStateException("expected , or } at $i")
                }
            }
        }

        fun arrayValue(): List<Any?> {
            expect('[')
            val l = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return l }
            while (true) {
                l.add(value())
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw IllegalStateException("expected , or ] at $i")
                }
            }
        }

        fun stringValue(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalStateException("unterminated string")
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (i >= s.length) throw IllegalStateException("bad escape")
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i + 1, i + 5)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        fun numberValue(): Double {
            val start = i
            if (peek() == '-' || peek() == '+') i++
            // JSON 规范要求整数部分至少一位数字，`.5` / `-.5` 属非法。
            // 此前实现会放过 `.5`（toDoubleOrNull 接受），与"语法错误显式失败"契约冲突。
            if (i >= s.length || !s[i].isDigit()) {
                throw IllegalStateException("bad number: integer part required at $i")
            }
            while (i < s.length && (s[i].isDigit() || s[i] == '.' ||
                        s[i] == 'e' || s[i] == 'E' || s[i] == '-' || s[i] == '+')
            ) i++
            val raw = s.substring(start, i)
            return raw.toDoubleOrNull() ?: throw IllegalStateException("bad number: $raw")
        }

        fun literal(lit: String, v: Any?): Any? {
            if (!s.startsWith(lit, i)) throw IllegalStateException("expected $lit at $i")
            i += lit.length
            return v
        }

        fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        fun expect(c: Char) {
            if (i >= s.length || s[i] != c) {
                throw IllegalStateException("expected $c at $i, got ${peek()}")
            }
            i++
        }
    }

    /** 生成字符串字面量（含转义）。供 LLM 请求体构造使用。 */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
