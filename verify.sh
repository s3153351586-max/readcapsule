#!/usr/bin/env bash
# ReadCapsule 验证套件
#
# 用法：bash verify.sh          （在项目根目录执行）
#       bash verify.sh --net    （额外执行真实网络冒烟，需 SKIP 见下方）
#
# 设计原则：
#   Stage 1-6 为纯静态断言，零依赖，任何机器可跑（含 CI）
#   Stage 7 为 JVM 冒烟测试：把 Json/Wbi/BvExtractor/Text 从 Android 依赖中解耦，
#           用 JDK 直接编译真实源文件并跑断言 —— 证明算法层真的能跑，而非只看代码
set -u

PASS=0; FAIL=0
ok()  { echo "  PASS: $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }
chk() { if eval "$2" >/dev/null 2>&1; then ok "$1"; else bad "$1"; fi; }

SRC=app/src/main
PKG=$SRC/java/com/readcapsule
DO_NET=0
[ "${1:-}" = "--net" ] && DO_NET=1

echo "=============================================="
echo " ReadCapsule verification"
echo "=============================================="

echo
echo "== Stage 1: 闭合性 =="
chk "settings.gradle.kts"        "[ -f settings.gradle.kts ]"
chk "根 build.gradle.kts"        "[ -f build.gradle.kts ]"
chk "app/build.gradle.kts"       "[ -f app/build.gradle.kts ]"
chk "AndroidManifest.xml"        "[ -f $SRC/AndroidManifest.xml ]"
chk "CI 工作流"                  "[ -f .github/workflows/build.yml ]"
chk "README.md"                  "[ -f README.md ]"
for f in Config MainActivity ReaderA11yService CapsuleOverlay ArticleParser \
         BvExtractor BiliClient LlmClient Http Json Store Text; do
  chk "源码 $f.kt"               "[ -f $PKG/$f.kt ]"
done
chk "无障碍服务配置"             "[ -f $SRC/res/xml/accessibility_service_config.xml ]"

echo
echo "== Stage 2: 权限最小化契约 =="
chk "声明 INTERNET（功能前提）"  "grep -q '<uses-permission[^>]*INTERNET' $SRC/AndroidManifest.xml"
chk "无 SYSTEM_ALERT_WINDOW"     "! grep -q '<uses-permission[^>]*SYSTEM_ALERT_WINDOW' $SRC/AndroidManifest.xml"
chk "无存储/录音/剪贴板权限"     "! grep -qE '<uses-permission[^>]*(READ_EXTERNAL_STORAGE|RECORD_AUDIO|READ_CLIPBOARD)' $SRC/AndroidManifest.xml"
chk "allowBackup=false"          "grep -q 'allowBackup=\"false\"' $SRC/AndroidManifest.xml"
chk "usesCleartextTraffic=false" "grep -q 'usesCleartextTraffic=\"false\"' $SRC/AndroidManifest.xml"
chk "Manifest 声明 BIND_A11Y"    "grep -q 'BIND_ACCESSIBILITY_SERVICE' $SRC/AndroidManifest.xml"

echo
echo "== Stage 3: 目标包白名单一致性 =="
# Config.PACKAGE_MODE 与 accessibility_service_config.xml 必须完全一致，防止漂移
XML_PKGS=$(grep -o 'android:packageNames="[^"]*"' $SRC/res/xml/accessibility_service_config.xml \
  | sed 's/.*="//;s/"//' | tr ',' '\n' | sort | tr '\n' ' ' | sed 's/ $//')
CFG_PKGS=$(grep -oE '"[a-z][a-z0-9_.]+" to Mode\.' $PKG/Config.kt \
  | sed 's/"//g;s/ to Mode.*//' | sort | tr '\n' ' ' | sed 's/ $//')
if [ "$XML_PKGS" = "$CFG_PKGS" ] && [ -n "$XML_PKGS" ]; then
  ok "两处白名单完全一致"
  echo "       [$XML_PKGS]"
else
  bad "白名单漂移"
  echo "       xml: [$XML_PKGS]"
  echo "       cfg: [$CFG_PKGS]"
fi
chk "服务内二次校验"             "grep -q 'PACKAGE_MODE' $PKG/ReaderA11yService.kt"
chk "B站包名在列"                "grep -q 'tv.danmaku.bili' $SRC/res/xml/accessibility_service_config.xml"

echo
echo "== Stage 4: 降级路径显式声明 =="
chk "使用 TYPE_ACCESSIBILITY_OVERLAY" "grep -q 'TYPE_ACCESSIBILITY_OVERLAY' $PKG/ReaderA11yService.kt"
chk "管线级 catch(Throwable)"    "grep -q 'catch (t: Throwable)' $PKG/ReaderA11yService.kt"
chk "解析层 catch(Throwable)"    "grep -q 'catch (t: Throwable)' $PKG/ArticleParser.kt"
chk "JSON 解析失败显式类型"      "grep -q 'ParseFail' $PKG/Json.kt"
chk "B站: 需登录错误类型"        "grep -q 'class NeedLogin' $PKG/BiliClient.kt"
chk "B站: 无字幕错误类型"        "grep -q 'class NoSubtitle' $PKG/BiliClient.kt"
chk "LLM: 无 Key 错误类型"       "grep -q 'class NoKey' $PKG/LlmClient.kt"
chk "LLM: 截断标记"              "grep -q 'truncated' $PKG/LlmClient.kt"
chk "截断在 UI 显式呈现"         "grep -q '输入超出上限已抽样' $PKG/ReaderA11yService.kt"
chk "BV 歧义显式声明"            "grep -q 'class Ambiguous' $PKG/BvExtractor.kt"
chk "BV 正则严格 10 位"          "grep -q 'BV\[1-9A-HJ-NP-Za-km-z\]{10}' $PKG/BvExtractor.kt"
chk "无障碍节点数上限"           "grep -q 'MAX_NODES' $PKG/Config.kt"
chk "无障碍递归深度上限"         "grep -q 'MAX_DEPTH' $PKG/Config.kt"
chk "HTTP 响应体上限"            "grep -q 'MAX_BODY_BYTES' $PKG/Http.kt"
chk "凭据脱敏显示"               "grep -q 'fun mask' $PKG/Text.kt"
chk "Loading 态不得伪装已完成"   "grep -q 'body is Body.Loading' $PKG/CapsuleOverlay.kt"

echo
echo "== Stage 5: 隐私与凭据卫生 =="
chk "凭据不写日志"               "! grep -nE 'Log\.[dwie]\(.*(sessdata|apiKey|API_KEY)' $PKG/*.kt"
chk "Cookie 注入点唯一"          "grep -c 'setRequestProperty(\"Cookie\"' $PKG/Http.kt | grep -q '^1$'"
chk "网络出口唯一（全项目）"     "[ \$(grep -l 'openConnection()' $PKG/*.kt | wc -l) -eq 1 ]"
chk "凭据键集中于 Store"         "grep -q 'KEY_SESSDATA' $PKG/Store.kt"

echo
echo "== Stage 6: 依赖契约（二维：运行期 / 测试期） =="
# 修正后的契约：RUNTIME_DEPS=0（进 APK），TEST_DEPS=4（不进 APK）。
# 二者不可混为一谈 —— 上一版把「零依赖」当成一维指标是错误的。
chk "运行期无实现依赖"           "! grep -qE '^[[:space:]]*implementation\(' app/build.gradle.kts"
chk "测试依赖已声明"             "grep -q 'testImplementation(\"junit:junit' app/build.gradle.kts"
chk "存在依赖污染守卫任务"       "grep -q 'assertNoRuntimeDeps' app/build.gradle.kts"
chk "assemble 依赖守卫任务"      "grep -q 'dependsOn(\"assertNoRuntimeDeps\")' app/build.gradle.kts"
# 只在「依赖声明的语法位置」上匹配，而不是文件里出现该单词即判失败。
# 否则 assertNoRuntimeDeps 的禁用清单、以及注释里的说明文字都会造成误报。
# 合法声明形如：implementation("okhttp...")  /  testImplementation("gson...")
chk "无 okhttp/retrofit/gson 依赖声明" \
  "! sed 's|//.*||' app/build.gradle.kts build.gradle.kts \
     | grep -qiE '(implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation)\([^)]*(okhttp|retrofit|gson|kotlinx-serialization|moshi)'"

echo
echo "== Stage 6b: 测试覆盖闭合性 =="
TESTDIR=app/src/test/java/com/readcapsule
chk "测试目录存在"               "[ -d $TESTDIR ]"
for t in WbiTest BvExtractorTest JsonTest TextTest JsonCrossValidationTest; do
  chk "测试类 $t.kt"             "[ -f $TESTDIR/$t.kt ]"
done
# 关键：每个纯逻辑模块都必须有对应测试，否则「测试通过」覆盖不到该模块
chk "wbi 参数顺序断言存在"       "grep -q '与参数插入顺序无关' $TESTDIR/WbiTest.kt"
chk "wbi 对 key 敏感断言存在"    "grep -q '签名对 key 敏感' $TESTDIR/WbiTest.kt"
chk "BV 歧义断言存在"            "grep -q '歧义必须显式失败' $TESTDIR/BvExtractorTest.kt || grep -q '判定为歧义' $TESTDIR/BvExtractorTest.kt"
chk "JSON 尾随内容断言存在"      "grep -q '尾随内容必须拒绝' $TESTDIR/JsonTest.kt"
chk "JSON 交叉校验存在"          "grep -q 'JSONTokener' $TESTDIR/JsonCrossValidationTest.kt"
chk "指纹碰撞防护断言存在"       "grep -q '分隔符防止拼接碰撞' $TESTDIR/TextTest.kt"
# 断言测试自带诚实边界说明，而非伪装成"完全验证"
chk "wbi 测试标注验证边界"       "grep -q '抓不住' $TESTDIR/WbiTest.kt"

echo
echo "== Stage 7: JVM 冒烟测试（真实编译 + 执行） =="
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/src"

# 仅复制无 Android 依赖的纯逻辑模块
for f in Config BvExtractor Json Text; do
  cp "$PKG/$f.kt" "$TMP/src/" 2>/dev/null || true
done

if ! command -v kotlinc >/dev/null 2>&1; then
  echo "  SKIP: kotlinc 未安装，跳过 JVM 冒烟测试"
  echo "        （安装：sdk install kotlin  /  brew install kotlin）"
else
  cat > "$TMP/src/Main.kt" <<'KOTLIN'
import com.readcapsule.*

var fail = 0
fun t(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("PASS: $name")
    else { println("FAIL: $name ${if (detail.isNotEmpty()) "-> $detail" else ""}"); fail++ }
}

fun main() {
    // --- BvExtractor ---
    val only = BvExtractor.extract(listOf("【4K】BV1Nx411c7xx 深度评测", "分享 BV1Nx411c7xx"))
    t("BV 唯一命中", only is BvExtractor.Result.Found && only.bv == "BV1Nx411c7xx", "$only")

    val amb = BvExtractor.extract(listOf("BV1Nx411c7xx", "BV1aaaaaaaaa"))
    t("BV 歧义显式声明", amb is BvExtractor.Result.Ambiguous, "$amb")

    val freq = BvExtractor.extract(listOf("BV1Nx411c7xx", "BV1aaaaaaaaa", "BV1Nx411c7xx"))
    t("BV 频次加权", freq is BvExtractor.Result.Found && freq.bv == "BV1Nx411c7xx", "$freq")

    t("BV 长度校验(9位拒)", BvExtractor.extract(listOf("BV1Nx411c7x")) is BvExtractor.Result.NotFound)
    t("BV 长度校验(11位拒)", BvExtractor.extract(listOf("BV1Nx411c7xxz")) is BvExtractor.Result.NotFound)
    t("BV 含非法字符0 拒", BvExtractor.extract(listOf("BV01Nx411c7x")) is BvExtractor.Result.NotFound)
    t("BV 空输入", BvExtractor.extract(emptyList()) is BvExtractor.Result.NotFound)
    t("BV 从URL提取", BvExtractor.fromUrl("https://www.bilibili.com/video/BV1Nx411c7xx?p=2") == "BV1Nx411c7xx")

    // --- Wbi ---
    val k = Wbi.mixinKey(
        "https://i0.hdslb.com/bfs/wbi/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",
        "https://i0.hdslb.com/bfs/wbi/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png"
    )
    t("wbi mixinKey 长度=32", k.length == 32, "${k.length}")
    t("wbi mixinKey 确定性", k == Wbi.mixinKey(
        "https://i0.hdslb.com/bfs/wbi/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",
        "https://i0.hdslb.com/bfs/wbi/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png"))

    val s1 = Wbi.sign(mapOf("bvid" to "BV1Nx411c7xx"), k, 1700000000L)
    val s2 = Wbi.sign(mapOf("bvid" to "BV1Nx411c7xx"), k, 1700000000L)
    t("wbi 签名确定性", s1 == s2 && s1.contains("w_rid="), s1)
    t("wbi wts 已注入", s1.contains("wts=1700000000"))
    t("wbi 非法字符过滤",
        Wbi.sign(mapOf("x" to "a!b'c"), k, 1L) == Wbi.sign(mapOf("x" to "abc"), k, 1L))
    t("wbi 参数顺序无关",
        Wbi.sign(mapOf("a" to "1", "b" to "2"), k, 5L) == Wbi.sign(mapOf("b" to "2", "a" to "1"), k, 5L))
    t("wbi 时间戳影响签名",
        Wbi.sign(mapOf("a" to "1"), k, 1L) != Wbi.sign(mapOf("a" to "1"), k, 2L))
    // 不同 key 必须产生不同签名（证明 mixinKey 真的参与运算，而非摆设）
    val k2 = Wbi.mixinKey(
        "https://i0.hdslb.com/bfs/wbi/zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz.png",
        "https://i0.hdslb.com/bfs/wbi/yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy.png"
    )
    t("wbi mixinKey 真的参与签名",
        Wbi.sign(mapOf("a" to "1"), k, 1L) != Wbi.sign(mapOf("a" to "1"), k2, 1L))

    // --- Json ---
    val j = Json.parse("""{"code":0,"data":{"subtitle":{"subtitles":[{"lan":"ai-zh","subtitle_url":"//x/y.json"}]}}}""")
    t("Json 嵌套路径下钻",
        !(j is Json.ParseFail) &&
        Json.pathStr(j, "data", "subtitle", "subtitles", "0", "lan") == "ai-zh" &&
        Json.pathStr(j, "data", "subtitle", "subtitles", "0", "subtitle_url") == "//x/y.json")
    t("Json 数组长度", Json.pathArr(j, "data", "subtitle", "subtitles").size == 1)
    t("Json 数字取值", Json.long(Json.path(j, "code")) == 0L)
    t("Json 缺失路径返回 null", Json.pathStr(j, "data", "nope", "x") == null)

    val esc = Json.parse("""{"s":"l1\nl2\t\"q\" \\ \u4e2d"}""")
    t("Json 转义解析", Json.pathStr(esc, "s") == "l1\nl2\t\"q\" \\ 中", "${Json.pathStr(esc, "s")}")

    t("Json 语法错误显式失败", Json.parse("""{"a":}""") is Json.ParseFail)
    t("Json 尾随内容拒绝", Json.parse("""{"a":1} junk""") is Json.ParseFail)
    t("Json 未闭合字符串拒绝", Json.parse("""{"a":"x}""") is Json.ParseFail)
    t("Json null 与 ParseFail 可区分", Json.parse("null") == null)
    t("Json 深层嵌套",
        Json.long(Json.path(Json.parse("""{"a":{"b":{"c":{"d":42}}}}"""), "a", "b", "c", "d")) == 42L)

    val q = Json.quote("a\"b\n中\\c\t")
    t("Json.quote 往返", Json.pathStr(Json.parse("""{"k":$q}"""), "k") == "a\"b\n中\\c\t")

    // --- Text ---
    t("指纹确定性", Text.fingerprint("a", "b") == Text.fingerprint("a", "b"))
    t("指纹分隔符防碰撞", Text.fingerprint("a", "b") != Text.fingerprint("ab", ""))
    t("指纹长度 32", Text.fingerprint("x").length == 32)
    t("summaryKey 模型敏感",
        Text.summaryKey("v", "content", "m1") != Text.summaryKey("v", "content", "m2"))
    t("summaryKey 内容敏感",
        Text.summaryKey("v", "c1", "m") != Text.summaryKey("v", "c2", "m"))
    t("SESSDATA 形态-正常", Text.looksLikeSessdata("abcd1234efgh5678ijkl"))
    t("SESSDATA 形态-整条Cookie拒", !Text.looksLikeSessdata("SESSDATA=abcd1234efgh5678"))
    t("SESSDATA 形态-过短拒", !Text.looksLikeSessdata("abc"))
    t("掩码不泄露全文", !Text.mask("abcdefghijklmnop").contains("cdefghijklm"))

    // --- 生产 SelfTest ---
    for (c in SelfTest.run()) t("[SelfTest] ${c.name}", c.pass, c.detail)

    println()
    if (fail == 0) println("SMOKE: ALL PASS") else println("SMOKE: $fail FAILED")
    if (fail != 0) kotlin.system.exitProcess(1)
}
KOTLIN

  if kotlinc -nowarn -d "$TMP/out" "$TMP/src" 2>"$TMP/compile.log"; then
    ok "Kotlin 纯逻辑模块编译通过"
    if kotlin -classpath "$TMP/out" MainKt 2>&1 | sed 's/^/  /'; then
      ok "JVM 冒烟测试全绿"
    else
      bad "JVM 冒烟测试有失败项（见上方输出）"
    fi
  else
    bad "Kotlin 编译失败"
    sed 's/^/       /' "$TMP/compile.log" | head -40
  fi
fi

echo
echo "== Stage 8: 可选网络冒烟（--net，需真实凭据） =="
if [ "$DO_NET" -eq 0 ]; then
  echo "  SKIP: 未指定 --net"
  echo "        用法: READCAPSULE_SESSDATA=xxx bash verify.sh --net"
else
  SESS="${READCAPSULE_SESSDATA:-}"
  if [ -z "$SESS" ]; then
    bad "缺少 READCAPSULE_SESSDATA"
  else
    echo "  请求 nav ..."
    NAV=$(curl -s -m 15 -A 'Mozilla/5.0' -H "Cookie: SESSDATA=$SESS" \
      https://api.bilibili.com/x/web-interface/nav)
    if echo "$NAV" | grep -q '"isLogin":true'; then
      ok "nav 登录态有效（SESSDATA 可用）"
      if echo "$NAV" | grep -q '"wbi_img"'; then
        ok "nav 返回 wbi_img（接口结构未变）"
      else
        bad "nav 缺少 wbi_img —— 接口结构已变更，wbi 签名需更新"
      fi
    else
      bad "nav 登录态无效（SESSDATA 过期或格式错误）"
    fi
  fi
fi

echo
echo "----------------------------------------------"
echo "PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "VERDICT: PASS"; exit 0; else echo "VERDICT: FAIL($FAIL)"; exit 1; fi
