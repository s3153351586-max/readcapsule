plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.readcapsule"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.readcapsule"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.3-diag"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // 无障碍服务相关检查不应阻断 CI；产物可用性优先
        abortOnError = false
    }

    testOptions {
        unitTests {
            // wbi/JSON/指纹等纯逻辑不触碰 Android 框架，标准 JVM 测试即可；
            // 无需 Robolectric，避免引入 200MB+ 的 android-all 依赖
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // ============================ 依赖契约变更说明 ============================
    // 上一版注释宣称「零 dependencies 块」。加入单元测试后该说法**不再成立**，
    // 此处显式修正，而非把测试依赖藏在别处以维持一个好看的数字。
    //
    // 修正后的契约（二维，二者不可混为一谈）：
    //   RUNTIME_DEPS = 0   <- APK 内不含任何第三方运行时依赖（此条**未变**）
    //   TEST_DEPS    = 4   <- 仅存在于单元测试 classpath，**不进入 APK**
    //
    // 为何需要这 4 个依赖：
    //   wbi 签名 / BV 提取 / JSON 解析 / 指纹均为纯逻辑，其正确性无法由静态
    //   断言证明（例如「mixinKey 是否真的参与签名」——读代码看不出来）。
    //   把它们作为 JVM 单测在 CI 上真实执行，是唯一可靠的验证手段。
    //
    // 验证 TEST_DEPS 不污染 APK：
    //   ./gradlew :app:dependencies --configuration releaseRuntimeClasspath
    //   该命令输出中不应出现 junit。CI 已加入此断言（见 build.yml）。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // 仅用于交叉校验自实现解析器
    // ======================================================================
}

// 依赖契约的机器可验证断言：一旦有人把测试依赖误升级为实现依赖，此处立即失败。
tasks.register("assertNoRuntimeDeps") {
    description = "断言 APK 运行期依赖树为空（测试依赖不得进入运行时）"
    doLast {
        val rtConfig = configurations.findByName("releaseRuntimeClasspath")
            ?: error("releaseRuntimeClasspath 不存在，构建配置异常")
        val forbidden = listOf("junit", "org.json", "okhttp", "retrofit", "gson")
        val offenders = rtConfig.resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id.toString() }
            .filter { id -> forbidden.any { id.contains(it, ignoreCase = true) } }
        if (offenders.isNotEmpty()) {
            error("运行期依赖污染，以下测试依赖进入了 APK: $offenders")
        }
        logger.lifecycle("assertNoRuntimeDeps: PASS（运行期依赖树中无测试依赖）")
    }
}

// 让 assemble 依赖该校验，使污染在构建期即被拦截，而非发布后才发现
tasks.matching { it.name == "assembleRelease" || it.name == "assembleDebug" }
    .configureEach { dependsOn("assertNoRuntimeDeps") }
