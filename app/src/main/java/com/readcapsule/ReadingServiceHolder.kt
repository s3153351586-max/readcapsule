package com.readcapsule

/**
 * 无障碍服务实例的进程内引用。
 *
 * 为什么需要它：
 * 诊断信息（事件是否到达、正文抓到多少字、因何放弃）只存在于服务的实例字段里。
 * 要让主界面读到，最省事的做法有两种，都不合适：
 *   - 静态字段挂在 Service 上：Service 会被系统反复创建销毁，静态引用容易悬垂，
 *     且这个引用本身就会阻止 Service 实例被回收。
 *   - bindService：需要额外暴露 Binder、处理连接生命周期，为读几行文本引入
 *     一整套 IPC，不划算。
 *
 * 这里用一个显式的、单点的持有者：Service 在 onServiceConnected 时注册，
 * onUnbind/onDestroy 时注销。主界面读不到就是 null —— 而「null」本身
 * 恰好就是最有价值的那条诊断信息：服务根本没在跑。
 *
 * 主界面与服务同进程（同一个 APK，无 android:process），所以直接读对象是安全的。
 */
object ReadingServiceHolder {

    @Volatile
    private var instance: ReaderA11yService? = null

    fun attach(s: ReaderA11yService) {
        instance = s
    }

    fun detach(s: ReaderA11yService) {
        // 只有当前登记的就是这个实例时才清空，避免旧实例销毁时
        // 把新实例的登记一并抹掉（系统快速重启服务时会发生）
        if (instance === s) instance = null
    }

    fun get(): ReaderA11yService? = instance
}
