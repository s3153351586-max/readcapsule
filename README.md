# 速读胶囊 (ReadCapsule)

基于 Android 无障碍服务的二合一速读助手。同一个悬浮球，按前台应用自动切换形态：

| 前台应用 | 形态 | 触发 | 产出 |
|---|---|---|---|
| 微信公众号 / 知乎 / 今日头条 / QQ浏览器 | 📄 长文 | 点击悬浮球 | 核心结论 + 3~5 条要点 + 关键数据 |
| B站（`tv.danmaku.bili` / 国际版） | 🎬 视频 | 点击悬浮球 | 核心结论 + 要点 + **带时间戳的视频大纲** |

**设计定位**：拦截「本来要花 10 分钟读完、但其实只需 30 秒知道结论」的信息消费。

---

## 一、必须澄清的技术前提

### 1.1 B站没有公开的字幕接口

这是本方案与「无障碍底座原封不动搬过来」之间最大的认知差。事实：

- `x/player/v2` 在**未携带有效 `SESSDATA`** 时，`subtitle.subtitles` 恒为空数组
- 2023 年起该接口要求 **wbi 签名**，缺失返回 `code=-403`
- **AI 自动字幕**（用户量最大的字幕类型）仅在登录态下可见

结论：**B站字幕是账号态资源，必须由用户提供自己的 `SESSDATA`。** 这不是可选优化，是物理约束。本应用不内置任何凭据、不提供共享账号、不爬取第三方镜像。

### 1.2 无障碍读不到剪贴板，但能读到 BV 号

无法通过无障碍服务访问剪贴板或解析分享链接。可行路径是读取节点的 `text` / `contentDescription`——B站播放器区域会把 `BV1xx411c7xx` 写进 `contentDescription`。

风险：节点树里同时存在**推荐流的其它视频** BV 号。若简单取首个匹配，会造成「总结 A 视频却返回 B 视频摘要」这类静默错误。**对策是频次加权 + 歧义显式报错**：出现次数最多的候选胜出；最高频次并列时弹出候选列表由用户选择，**绝不猜测**。

### 1.3 为何放弃「内录音频 + Whisper」

| 维度（40 分钟视频） | MediaProjection + Whisper | **字幕轨提取（本方案）** |
|---|---|---|
| 单次耗时 | 40 min 录音 + 约 10 min 转写 | **< 2 s 拉字幕 + 5 s 总结** |
| 授权摩擦 | 每次弹系统录屏警告窗 | **0 次** |
| 发热 / 耗电 | 高（持续编码 + 推理） | **极低** |
| 依赖 | ffmpeg + 约 150 MB 模型 | **0**（平台 API） |
| 无 CC 字幕时 | 仍可用 | **显式报错降级** |

---

## 二、与 PriceCapsule 的架构关系

**继承的骨架（未改动）**：`TYPE_ACCESSIBILITY_OVERLAY` 悬浮窗（随服务生命周期自动销毁）、单线程 IO 执行器（`MIN_PRIORITY`，不抢前台 CPU）、幂等闸门 + 事件去抖、管线级 `catch (Throwable)` 兜底、自绘 `View` 与真实 `View` 树混用的分层策略、`SQLiteOpenHelper` 而非 Room。

**契约断裂（必须显式声明）**：

| | PriceCapsule | ReadCapsule |
|---|---|---|
| 网络权限 | **无**（系统强制零网络） | **有** `INTERNET` |
| 凭据 | 无 | B站 `SESSDATA` + LLM API Key |
| 触发模型 | 事件驱动、自动响应 | **用户驱动、按需触发** |

隐私契约由「绝对零网络」下调为「**网络能力单一且可审计**」：仅声明 `INTERNET`、全部出站请求收敛在 `Http.kt` 单一出口、凭据仅存私有库且 `allowBackup=false`。CI 断言守护该边界。

**触发模型为何必须改**：LLM 调用有 token 成本与数秒延迟。若沿用自动触发，用户每翻一页就产生一次计费调用——产品级事故。因此服务**只做感知与渲染，绝不自动发起网络请求**；仅在悬浮球被点击（= 用户明确表达"我要读"）后才触发。

---

## 三、复杂度预算

```
COMPONENTS=12   (Config, MainActivity, ReaderA11yService, CapsuleOverlay, ArticleParser,
                 BvExtractor, BiliClient, LlmClient, Http, Json, Store, Text)
                PriceCapsule=6，净增 6
RUNTIME_DEPS=0  进入 APK 的第三方依赖数为 0
TEST_DEPS=4     仅存在于单元测试 classpath，不进 APK
MANUAL_STEPS=5  装 APK → 开无障碍 → 填 SESSDATA → 填 LLM Key → 完成
                PriceCapsule=2，净增 3
PERMISSIONS=1   仅 INTERNET
```

### 依赖契约：二维，不是一维

上一版宣称「零 dependencies 块」。加入单元测试后**该说法不再成立**，此处修正而非维持一个好看的数字：

| 维度 | 值 | 含义 |
|---|---|---|
| `RUNTIME_DEPS` | **0** | APK 内不含任何第三方运行时依赖 |
| `TEST_DEPS` | **4** | JUnit + org.json，仅测试期，**不进 APK** |

`junit:junit:4.13.2` 与 `org.json:json:20240303` 是**为了能真实验证算法正确性**而付出的代价。`org.json` 专用于交叉校验自实现解析器（见 §5.2）。该边界由 Gradle 任务 `assertNoRuntimeDeps` 强制：`assembleRelease`/`assembleDebug` 依赖它，测试依赖一旦泄漏进运行期 classpath，构建立即失败。

自实现部分（仍为零依赖）：HTTP 用平台 `HttpURLConnection`、JSON 用 180 行递归下降解析器、存储用 `SQLiteOpenHelper`、并发用 `java.util.concurrent`、密码学用 `javax.crypto` + `MessageDigest`。

### 净增 6 个组件的 ROI 论证

| 组件 | 不可合并的理由 |
|---|---|
| `Json` | LLM 返回值不可信，需宽容解析；引 Gson 会增加 ~300KB 运行期体积 |
| `Wbi` | 全工程唯一「算法正确性即生死线」的模块，必须能脱离 Android 独立单测 |
| `BvExtractor` | 歧义判定独立于节点遍历，需覆盖 20+ 边界用例 |
| `BiliClient` | 三步流水线 + 4 类错误语义（需登录/无字幕/签名失效/接口变更） |
| `LlmClient` | 必须支持换供应商（改两个字符串）；写进编排层则焊死单一厂商 |
| `Store` | 凭据是敏感数据，独立文件便于审计「凭据只在此读写」 |

**明确拒绝的复杂度**：Room/KSP（3 张表、每表 ≤2 条查询，注解处理器是纯编译期失败面）、协程（单线程串行 IO 已足够）、OkHttp（低频串行请求，连接池无收益）、Markwon（只为渲染 4 种 Markdown 语法）、Robolectric（纯逻辑测试无需 Android 框架，省 200MB+ 的 `android-all`）。

---

## 四、目录结构

```
ReadCapsule/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── verify.sh                              # 8 阶段静态契约套件
├── README.md
├── .github/workflows/build.yml            # 双 job：test → assemble
└── app/
    ├── build.gradle.kts                   # RUNTIME_DEPS=0 / TEST_DEPS=4 + 污染守卫
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml        # 仅 INTERNET 一个权限
        │   ├── res/{values,layout,xml}/
        │   └── java/com/readcapsule/
        │       ├── Config.kt          # 常量 + 包名→形态映射（唯一真相源）
        │       ├── MainActivity.kt    # 配置 / 凭据 / 离线自检
        │       ├── ReaderA11yService.kt  # 编排层
        │       ├── CapsuleOverlay.kt  # 悬浮窗（自绘球 + View 树卡片）
        │       ├── ArticleParser.kt   # 长文抓取（节点评分 + 子树屏蔽）
        │       ├── BvExtractor.kt     # BV 提取 + wbi 签名 + SelfTest
        │       ├── BiliClient.kt      # B站字幕三步流水线
        │       ├── LlmClient.kt       # OpenAI 兼容总结客户端
        │       ├── Http.kt            # 网络唯一出口
        │       ├── Json.kt            # 零依赖 JSON 解析器
        │       ├── Store.kt           # 凭据 + 双缓存
        │       └── Text.kt            # 指纹 / 归一化 / 脱敏
        └── test/java/com/readcapsule/     # 单元测试（不进 APK）
            ├── WbiTest.kt                # 16 例：签名性质断言
            ├── BvExtractorTest.kt        # 24 例：提取与歧义
            ├── JsonTest.kt               # 30 例：解析器行为
            ├── TextTest.kt               # 26 例：指纹与凭据形态
            └── JsonCrossValidationTest.kt # 18 例：与 org.json 对拍
```

---

## 五、验证体系

分三层，各自覆盖不同风险，**不可互相替代**：

| 层 | 位置 | 覆盖 | 能在哪跑 |
|---|---|---|---|
| 静态契约 | `verify.sh` Stage 1-6 | 权限边界、白名单一致性、降级路径存在性、凭据卫生、测试闭合性 | 任意机器，零依赖 |
| **单元测试** | `./gradlew test` | **算法行为**——签名性质、BV 歧义、JSON 边界、指纹碰撞 | 需 JDK + 网络（CI） |
| 集成冒烟 | `verify.sh --net` | 线上接口结构是否仍匹配 wbi 签名实现 | 需真实凭据 |

### 5.1 静态契约（`bash verify.sh`）

66 条断言，8 个阶段。最有价值的几条：

- **Stage 3 白名单一致性**：`Config.PACKAGE_MODE` 与 `accessibility_service_config.xml` 的包名集合必须逐字相同——防「改了一处忘了另一处」的经典漂移，这类 bug 表现为「某个 App 完全没反应」，极难排查
- **Stage 5 凭据卫生**：`Log.*(sessdata|apiKey)` 正则扫描——防凭据经 logcat 泄漏
- **Stage 5 网络出口唯一**：全项目只有一个文件调用 `openConnection()`——保证出站流量可一次性审计
- **Stage 6b 测试闭合性**：断言关键测试用例确实存在（如「签名对 key 敏感」），防测试被误删后绿灯依旧

无 `kotlinc` 时 Stage 7 自动 SKIP，不影响 CI。

### 5.2 单元测试（`./gradlew test`，CI 上执行）

**这是算法正确性的唯一可靠验证**，因为静态审查抓不到这类 bug。92 个用例：

**`WbiTest`（签名，16 例）**
核心断言是**性质断言**而非对拍断言：
- `签名对 key 敏感` —— 若 `mixinKey` 计算了却没参与签名（最隐蔽的假实现），此断言失败。**读代码看不出来**
- `签名与参数插入顺序无关` —— `HashMap` 迭代顺序随机，若实现未排序，同一组参数会产出不同签名，服务端随机拒绝
- `非法字符被过滤` —— B站剔除 `!'()*` 后校验，本地未过滤必然 `-403`
- `mixinKey 长度恒为 32` / `只由重排表选取有效字符` —— 抓重排表越界

> **诚实边界**（已写入测试文件头）：性质断言**抓不住**「`MIXIN_KEY_ENC_TAB` 重排表本身写错」。那个只能靠真实请求验证——见 `verify.sh --net`。绿灯不等于签名 100% 正确，只等于「除重排表外的所有性质都满足」。

**`BvExtractorTest`（24 例）**
最关键是「歧义必须显式失败」——这里的失败模式是**静默产出错误摘要**，用户无法从摘要内容察觉。覆盖 9 位/11 位/含 `0`/含 `O`/小写 `bv`/缺前缀/紧邻长串 等所有边界。

**`JsonTest`（30 例）**
- `尾随内容必须拒绝` —— 截断的 HTTP 响应常表现为「合法 JSON + 残余字节」；若容忍尾随，会把残响应当完整数据用
- `JSON null 与 ParseFail 可区分` —— 前者是合法值，后者是错误，二者语义完全不同
- `长数字不丢精度` —— `1700000000000` 若用 `float` 会变成 `1699999999999`

**`TextTest`（26 例）**
- `分隔符防止拼接碰撞` —— `fingerprint("a","b") != fingerprint("ab","")`。若实现是简单拼接，两个不同视频会共享缓存键
- `summaryKey 对模型敏感` —— 换模型必须换键，否则复用旧模型的摘要是错的

**`JsonCrossValidationTest`（18 例）**
用 `org.json` 作参考实现做**对拍**。自家测试若只用手写期望值，通过只证明「实现符合我的预期」，不证明「对规范的理解和别人一致」。对拍把风险从「我理解错了」降为「两边都错」。

### 5.3 集成冒烟（可选）

```bash
READCAPSULE_SESSDATA=你的值 bash verify.sh --net
```

断言 `nav` 接口仍返回 `isLogin:true` 且含 `wbi_img`——这是检测「B站接口结构变更导致 wbi 签名失效」的早期预警。

### 5.4 构建

```bash
./gradlew test            # 单元测试
./gradlew assembleDebug   # 构建 APK（会先跑依赖污染守卫）
```

### 5.5 CI

`.github/workflows/build.yml` 双 job：

1. **`test`** —— 跑单元测试，并断言「用例数 ≥ 60」且「失败/错误 = 0」。**显式校验测试真的跑了**：0 个测试被误判为通过是 CI 最常见的假绿灯。
2. **`assemble`** —— `needs: test`。跑静态契约 → 构建 APK → 断言产物存在 → 断言权限恰好 1 个（仅 `INTERNET`）→ 断言凭据未进日志。

---

## 六、首次安装（5 步）

1. 安装 APK，打开「速读胶囊」
2. 点击「开启无障碍服务」→ 系统列表启用「速读胶囊 · 长文/视频总结」
3. **填写 B站 `SESSDATA`**
   浏览器登录 bilibili.com → F12 → Application → Cookies → 复制 `SESSDATA` 的**值**
   （不含 `SESSDATA=` 前缀；粘错格式时应用会给明确纠正提示）
4. **填写 LLM 配置**——点预设一键填充（DeepSeek / Moonshot / 智谱 / 本地 Ollama）
5. 点「运行离线自检」确认全绿 → 完成

---

## 七、使用与成本

**长文**：打开公众号文章 → 右侧出现 📄 小球 → 点击 → 卡片弹出「核心结论 / 关键要点 / 关键数据 / 适用建议」。

**视频**：打开 B站视频播放页 → 出现 🎬 小球 → 点击 → 卡片弹出「核心结论 / 关键要点 / 时间线（带 `mm:ss`）/ 适用建议」。

**缓存行为**（成本控制的核心）：

- 字幕按 BV 号缓存 **7 天**——重复点击同一视频 **0 次网络请求**
- 摘要按「`域 + 内容指纹 + 模型名`」缓存——重复点击 **0 token 消耗**
- 换模型 → 缓存键变化 → 自动重算（不同模型的摘要质量不同，复用是错的）

**预算提醒**：一次 40 分钟视频总结约 2 万输入 token + 500 输出 token，按 DeepSeek 定价约 ¥0.02/次。缓存命中时为 0。

---

## 八、降级路径（显式声明，绝不静默）

| 场景 | 行为 |
|---|---|
| 未配置 `SESSDATA` | 卡片显示「未配置 B站凭据」，附获取步骤 |
| `SESSDATA` 失效（`code=-101`） | 卡片显示「B站凭据失效，请重新填写」 |
| 视频无字幕轨 | 卡片显示「该视频无可用字幕（UP 未上传且 AI 字幕未生成）」 |
| wbi 签名被拒（`-403`） | 卡片显示「签名被拒，客户端可能需升级」 |
| 检测到多个 BV 候选 | **弹出候选列表由用户选择**，附文案「不猜——选错视频会产出完全无关的摘要」 |
| 输入超 `MAX_PAYLOAD_CHARS` | 摘要底部追加 `⚠️ 输入超出上限已抽样：输入 N 字（超上限，已抽样压缩）` |
| 页面过短（< 400 字） | 视为非文章页，静默隐藏（**唯一允许静默的路径**——无内容是事实，非失败） |
| 落库 / 挂载浮窗失败 | `Log.w` 明确标记 + 隐藏胶囊，**不显示假成功** |
| 加载中 | 小球显示灰色 `…`，**绝不显示为已完成色** |

---

## 九、已知限制

**验证边界（重要）**：

- 单元测试**不能**证明 `MIXIN_KEY_ENC_TAB` 重排表正确——只能由 `--net` 联网冒烟确认
- 沙箱内无 Android SDK，`assembleDebug` 与真机行为**未经执行验证**，需在你的机器或 CI 上确认
- 无障碍节点解析（`ArticleParser`）依赖真实 App 的 UI 结构，无法离线单测，只有静态断言保护

**功能限制**：

- **多分P视频只总结第一个分P**（接口传 `cid=0`）。完整支持需先取 `pagelist`。
- **长文抓取混入评论区残留**。已通过 `comment|reply|recommend` 等容器特征整棵子树屏蔽，但平台 DOM 差异会导致少量渗漏。
- **公众号文章 `WebView` 内文本**在部分 ROM 上无障碍不可达。已开 `flagIncludeNotImportantViews` 缓解，仍有机型差异。
- **BV 号识别依赖播放器 `contentDescription`**。B站改版可能导致识别率下降。
- **`SESSDATA` 有效期由 B站决定**（通常数月），失效后需重新填写。
- **LLM 输入上限** 24,000 字符，超出时按「45% 头 / 10% 中 / 45% 尾」在段落边界抽样——刻意不像简单截尾那样丢掉结论段。
- 本应用**不保证**任何第三方平台的接口稳定性。接口变更时表现为显式报错，而非静默产出错误摘要。
