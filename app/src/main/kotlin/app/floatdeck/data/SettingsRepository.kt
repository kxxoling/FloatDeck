package app.floatdeck.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 应用级 DataStore 单例。 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

/**
 * 用户设置持久化仓库。
 *
 * 使用 Jetpack DataStore（Preferences）存储模板 ID、壁纸 URI 和立绘特效。
 */
class SettingsRepository(
    private val context: Context,
) {
    companion object {
        private val KEY_TEMPLATE = stringPreferencesKey("template_id")
        private val KEY_WALLPAPER_URI = stringPreferencesKey("wallpaper_uri")
        private val KEY_PORTRAIT_EFFECT = stringPreferencesKey("portrait_effect")
        private val KEY_DRAG_ENABLED = booleanPreferencesKey("drag_enabled")
    }

    /** 当前选中的模板 ID。 */
    val templateId: Flow<String> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_TEMPLATE] ?: ""
        }

    /** 用户自定义壁纸 URI（为 null 时使用模板内置壁纸）。 */
    val wallpaperUri: Flow<String?> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_WALLPAPER_URI]
        }

    /** 立绘特效选择，默认无。 */
    val portraitEffect: Flow<PortraitEffect> =
        context.dataStore.data.map { prefs ->
            PortraitEffect.fromKey(prefs[KEY_PORTRAIT_EFFECT] ?: "none")
        }

    /** 保存模板 ID。 */
    suspend fun setTemplate(id: String) {
        context.dataStore.edit { it[KEY_TEMPLATE] = id }
        // 同步写入 SharedPreferences，供壁纸服务同步读取
        context
            .getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("template_id", id)
            .apply()
    }

    /** 保存立绘特效。 */
    suspend fun setPortraitEffect(effect: PortraitEffect) {
        context.dataStore.edit { it[KEY_PORTRAIT_EFFECT] = effect.key }
        context
            .getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("portrait_effect", effect.key)
            .apply()
    }

    /** Whether portrait drag is enabled, default true. */
    val dragEnabled: Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_DRAG_ENABLED] ?: true
        }

    /** Save portrait drag enabled state. */
    suspend fun setDragEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DRAG_ENABLED] = enabled }
        context
            .getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("drag_enabled", enabled)
            .apply()
    }

    /** Save wallpaper URI (null removes it, reverting to the built-in wallpaper). */
    suspend fun setWallpaperUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri != null) {
                prefs[KEY_WALLPAPER_URI] = uri
            } else {
                prefs.remove(KEY_WALLPAPER_URI)
            }
        }
    }
}
