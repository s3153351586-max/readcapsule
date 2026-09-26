package com.readcapsule

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地存储：凭据 + 字幕/摘要缓存。
 *
 * 沿用 PriceCapsule 的 SQLiteOpenHelper 结论：单库 3 表、
 * 每表查询不超过 2 条，Room 的 KSP 生成开销与 schema 导出目录
 * 在编译期引入的失败面远大于它省下的行数 —— ROI 为负。
 *
 * 与 PriceCapsule 的关键差异：此库存储**凭据**，属于敏感数据。
 * 对策（不引依赖的前提下能做到的上限）：
 *  - `allowBackup=false`（Manifest 已设）：凭据不进入 adb backup / 云备份
 *  - 库文件位于应用私有目录（Context.MODE_PRIVATE 语义），其他应用无读权限
 *  - 全库不参与日志输出：任何 Log 调用都不得携带 cookie/key 明文
 */
class Store(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE cred (
                k TEXT PRIMARY KEY,
                v TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE sub_cache (
                bvid       TEXT PRIMARY KEY,
                title      TEXT NOT NULL,
                lang       TEXT NOT NULL,
                body       TEXT NOT NULL,
                bytes      INTEGER NOT NULL,
                fetched_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE sum_cache (
                key        TEXT PRIMARY KEY,
                bvid       TEXT NOT NULL,
                title      TEXT NOT NULL,
                body       TEXT NOT NULL,
                source_chars INTEGER NOT NULL,
                truncated  INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // 摘要按 bvid 清理时使用；无索引则退化为全表扫描
        db.execSQL("CREATE INDEX idx_sum_bvid ON sum_cache(bvid)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1：无迁移。后续版本在此显式 ALTER —— 不接受 drop-and-recreate，
        // 那会丢弃用户已付费产生的摘要缓存（含 LLM token 成本）。
    }

    // ---------------- 凭据 ----------------

    @Synchronized
    fun putCred(key: String, value: String) {
        val db = writableDatabase
        val cv = ContentValues(2).apply {
            put("k", key)
            put("v", value)
        }
        // CONFLICT_REPLACE：凭据更新即覆盖，历史值无保留价值
        db.insertWithOnConflict("cred", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun getCred(key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM cred WHERE k = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }

    @Synchronized
    fun clearCred(key: String) {
        writableDatabase.delete("cred", "k = ?", arrayOf(key))
    }

    @Synchronized
    fun hasCred(key: String): Boolean = getCred(key) != null

    // ---------------- 字幕缓存 ----------------

    /** 命中且未过期才返回；过期视为未命中，由调用方重新拉取。 */
    @Synchronized
    fun getSubtitles(bvid: String, now: Long): SubtitleDoc? {
        readableDatabase.rawQuery(
            "SELECT title, lang, body, fetched_at FROM sub_cache WHERE bvid = ?",
            arrayOf(bvid)
        ).use { c ->
            if (!c.moveToFirst()) return null
            val fetchedAt = c.getLong(3)
            if (now - fetchedAt > Config.CACHE_TTL_MS) return null
            return SubtitleDoc(bvid, c.getString(0), c.getString(1), c.getString(2), fetchedAt)
        }
    }

    @Synchronized
    fun putSubtitles(d: SubtitleDoc) {
        val cv = ContentValues(6).apply {
            put("bvid", d.bvid)
            put("title", d.title)
            put("lang", d.lang)
            put("body", d.body)
            put("bytes", d.body.toByteArray(Charsets.UTF_8).size)
            put("fetched_at", d.fetchedAt)
        }
        writableDatabase.insertWithOnConflict("sub_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------------- 摘要缓存 ----------------

    @Synchronized
    fun getSummary(key: String): SummaryDoc? {
        readableDatabase.rawQuery(
            "SELECT bvid, title, body, source_chars, truncated, created_at FROM sum_cache WHERE key = ?",
            arrayOf(key)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return SummaryDoc(
                key = key,
                bvid = c.getString(0),
                title = c.getString(1),
                body = c.getString(2),
                sourceChars = c.getInt(3),
                truncated = c.getInt(4) == 1,
                createdAt = c.getLong(5)
            )
        }
    }

    @Synchronized
    fun putSummary(d: SummaryDoc) {
        val cv = ContentValues(7).apply {
            put("key", d.key)
            put("bvid", d.bvid)
            put("title", d.title)
            put("body", d.body)
            put("source_chars", d.sourceChars)
            put("truncated", if (d.truncated) 1 else 0)
            put("created_at", d.createdAt)
        }
        writableDatabase.insertWithOnConflict("sum_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 统计信息，供 MainActivity 展示与自检。 */
    @Synchronized
    fun stats(): Triple<Int, Int, Long> {
        val subs = readableDatabase.rawQuery("SELECT COUNT(*) FROM sub_cache", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        val sums = readableDatabase.rawQuery("SELECT COUNT(*) FROM sum_cache", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        val bytes = readableDatabase.rawQuery(
            "SELECT IFNULL(SUM(bytes), 0) FROM sub_cache", null
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        return Triple(subs, sums, bytes)
    }

    @Synchronized
    fun purgeAll() {
        val db = writableDatabase
        db.delete("sub_cache", null, null)
        db.delete("sum_cache", null, null)
    }

    fun closeQuietly() {
        try {
            close()
        } catch (_: Throwable) {
            // 进程退出路径，关闭失败无需上抛
        }
    }

    companion object {
        private const val DB_NAME = "readcapsule.db"
        private const val DB_VERSION = 1

        const val KEY_SESSDATA = "sessdata"
        const val KEY_API_BASE = "llm_base"
        const val KEY_API_KEY = "llm_key"
        const val KEY_API_MODEL = "llm_model"
    }
}

/** 字幕文档。body 为已按时间轴规整的纯文本（含 `[mm:ss]` 行首标记）。 */
data class SubtitleDoc(
    val bvid: String,
    val title: String,
    val lang: String,
    val body: String,
    val fetchedAt: Long
)

/** 摘要文档。key 由 bvid + 模型 + 内容指纹派生，换模型会自然失效重算。 */
data class SummaryDoc(
    val key: String,
    val bvid: String,
    val title: String,
    val body: String,
    val sourceChars: Int,
    val truncated: Boolean,
    val createdAt: Long
)
