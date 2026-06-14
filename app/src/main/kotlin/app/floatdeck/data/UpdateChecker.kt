package app.floatdeck.data

import android.content.Context
import app.floatdeck.BuildConfig
import io.github.z4kn4fein.semver.toVersionOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed class UpdateResult {
    data class Available(val info: ReleaseInfo) : UpdateResult()
    data object UpToDate : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val htmlUrl: String,
    val body: String,
)

/** 检查 GitHub Releases 是否有新版本。 */
object UpdateChecker {

    private const val REPO_API = "https://api.github.com/repos/kxxoling/FloatDeck/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Gets the installer package name; null means sideloaded. */
    fun getInstallerPackageName(context: Context): String? {
        return try {
            context.packageManager.getInstallerPackageName(context.packageName)
        } catch (_: Exception) {
            null
        }
    }

    /** Returns true only for sideloaded users (not from app stores). */
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

    /** Checks the latest GitHub release. */
    suspend fun checkForUpdate(): UpdateResult =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(REPO_API).openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "FloatDeck-Android/${BuildConfig.VERSION_NAME}")

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    return@withContext UpdateResult.Error("HTTP $responseCode: $errorBody")
                }

                val json = connection.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(json)
                val remoteTag = obj.optString("tag_name", "")
                if (remoteTag.isEmpty()) {
                    return@withContext UpdateResult.Error("Empty release tag")
                }

                val currentVersion = BuildConfig.VERSION_NAME

                if (isNewer(remoteTag, currentVersion)) {
                    UpdateResult.Available(
                        ReleaseInfo(
                            tagName = remoteTag,
                            name = obj.optString("name", remoteTag),
                            htmlUrl = obj.optString("html_url", ""),
                            body = obj.optString("body", ""),
                        ),
                    )
                } else {
                    UpdateResult.UpToDate
                }
            } catch (e: java.net.SocketTimeoutException) {
                UpdateResult.Error("Request timeout")
            } catch (e: java.net.UnknownHostException) {
                UpdateResult.Error("No network connection")
            } catch (e: Exception) {
                UpdateResult.Error("Check failed: ${e.message}")
            }
        }

    /** Compares versions using kotlin-semver (SemVer 2.0).
     *  Strips 'v' prefix, parses both sides, returns true if remote > current.
     *  Falls back to false if either side fails to parse.
     */
    internal fun isNewer(remote: String, current: String): Boolean {
        val rClean = remote.removePrefix("v")
        val cClean = current.removePrefix("v")

        val rVer = rClean.toVersionOrNull(strict = false)
        val cVer = cClean.toVersionOrNull(strict = false)

        if (rVer == null || cVer == null) return false

        return rVer > cVer
    }
}
