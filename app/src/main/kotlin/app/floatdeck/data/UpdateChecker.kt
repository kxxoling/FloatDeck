package app.floatdeck.data

import android.content.Context
import app.floatdeck.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val htmlUrl: String,
    val body: String,
)

/** 检查 GitHub Releases 是否有新版本。 */
object UpdateChecker {

    private const val REPO_API = "https://api.github.com/repos/kxxoling/FloatDeck/releases/latest"

    /** 获取安装来源包名，null 表示侧载。 */
    fun getInstallerPackageName(context: Context): String? {
        return try {
            context.packageManager.getInstallerPackageName(context.packageName)
        } catch (_: Exception) {
            null
        }
    }

    /** 是否应该检查更新（仅侧载用户）。 */
    fun shouldCheckForUpdate(context: Context): Boolean {
        val installer = getInstallerPackageName(context) ?: return true
        return installer !in setOf(
            "org.fdroid.fdroid",
            "com.android.vending",
            "com.amazon.venezia",
            "com.huawei.appmarket",
            "com.xiaomi.market",
            "com.samsung.android.onestore",
        )
    }

    /** 检查 GitHub 最新 Release，返回 null 表示当前已是最新。 */
    suspend fun checkForUpdate(): ReleaseInfo? =
        withContext(Dispatchers.IO) {
            try {
                val json = URL(REPO_API).readText()
                val obj = JSONObject(json)
                val remoteTag = obj.optString("tag_name", "") ?: return@withContext null
                val currentVersion = BuildConfig.VERSION_NAME

                if (isNewer(remoteTag, currentVersion)) {
                    ReleaseInfo(
                        tagName = remoteTag,
                        name = obj.optString("name", remoteTag),
                        htmlUrl = obj.optString("html_url", ""),
                        body = obj.optString("body", ""),
                    )
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

    /** 简单版本比较：去除 'v' 前缀后比较语义化版本。 */
    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val c = current.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(r.size, c.size)
        for (i in 0 until maxLen) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }
}
