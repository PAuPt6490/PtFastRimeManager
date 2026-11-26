package com.pt.fastrime.manager.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

/** 应用级 DataStore（单例扩展属性，避免重复创建导致 “multiple DataStores active” 崩溃）。 */
private val Context.settingsDataStore by preferencesDataStore(name = "pt_fast_rime_settings")

/**
 * 轻量设置：当前激活方案、文件浏览器上次所在目录、上次使用的输入法。
 * 方案清单本身放 Room，这里只存单个标量，避免用 Room 存“键值对”这种反模式。
 */
class SettingsStore(private val context: Context) {

    val activeSchemaId: Flow<String?> = context.settingsDataStore.data
        .map { it[KEY_ACTIVE_SCHEMA] }

    val lastBrowseDir: Flow<String> = context.settingsDataStore.data
        .map { it[KEY_LAST_DIR] ?: DEFAULT_BROWSE_DIR }

    /** 记录最近一次被重启（以及其默认输入法）的输入法 id，形如 `包名/服务类`。 */
    val lastImeId: Flow<String?> = context.settingsDataStore.data
        .map { it[KEY_LAST_IME] }

    /** 用户选定的「目标输入法」包名；为空表示还没选过，交给探测逻辑自动挑一个。 */
    val targetImePackage: Flow<String?> = context.settingsDataStore.data
        .map { it[KEY_TARGET_IME] }

    suspend fun targetImePackageNow(): String? = targetImePackage.firstOrNull()

    suspend fun setTargetImePackage(packageName: String?) {
        context.settingsDataStore.edit { prefs ->
            if (packageName.isNullOrBlank()) prefs.remove(KEY_TARGET_IME)
            else prefs[KEY_TARGET_IME] = packageName
        }
    }

    suspend fun setActiveSchemaId(schemaId: String?) {
        context.settingsDataStore.edit { prefs ->
            if (schemaId == null) prefs.remove(KEY_ACTIVE_SCHEMA) else prefs[KEY_ACTIVE_SCHEMA] = schemaId
        }
    }

    suspend fun setLastBrowseDir(path: String) {
        context.settingsDataStore.edit { it[KEY_LAST_DIR] = path }
    }

    suspend fun setLastImeId(imeId: String?) {
        context.settingsDataStore.edit { prefs ->
            if (imeId.isNullOrBlank()) prefs.remove(KEY_LAST_IME) else prefs[KEY_LAST_IME] = imeId
        }
    }

    suspend fun lastImeIdNow(): String? = lastImeId.firstOrNull()

    companion object {
        const val DEFAULT_BROWSE_DIR = "/storage/emulated/0/Download"
        private val KEY_ACTIVE_SCHEMA = stringPreferencesKey("active_schema_id")
        private val KEY_LAST_DIR = stringPreferencesKey("last_browse_dir")
        private val KEY_LAST_IME = stringPreferencesKey("last_ime_id")
        private val KEY_TARGET_IME = stringPreferencesKey("target_ime_package")
    }
}
