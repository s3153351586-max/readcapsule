# 无障碍服务由系统按类名反射实例化，混淆会导致服务无法启动
-keep class com.readcapsule.ReaderA11yService { *; }
-keep class com.readcapsule.MainActivity { *; }

# AccessibilityNodeInfo 相关 API 为平台类，仅需保留枚举/常量引用
-keepclassmembers class * extends android.accessibilityservice.AccessibilityService {
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent);
    public void onInterrupt();
    protected void onServiceConnected();
}

# Json / Wbi / BvExtractor 为纯逻辑 object，通过 object 单例字段访问。
# R8 可能内联其方法；此处保留以保证反射式单测（如未来接入 Robolectric）可用。
-keep class com.readcapsule.Json { *; }
-keep class com.readcapsule.Wbi { *; }
-keep class com.readcapsule.BvExtractor { *; }
-keep class com.readcapsule.SelfTest { *; }

# HttpURLConnection 的 TLS 实现依赖反射访问部分内部类，禁止优化
-keep class com.android.okhttp.** { *; }
-dontwarn com.android.okhttp.**

# 凭据类字段（Store 中的 KEY_* 常量）被字符串引用，不随混淆改名，
# 否则已发布的版本升级后会读不到旧凭据。常量已编译为字面量，无需 keep。
