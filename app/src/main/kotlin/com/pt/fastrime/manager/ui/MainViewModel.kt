package com.pt.fastrime.manager.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pt.fastrime.manager.PtFastRimeApp
import com.pt.fastrime.manager.data.SchemaEntity
import com.pt.fastrime.manager.privilege.DirEntry
import com.pt.fastrime.manager.privilege.PrivilegeState
import com.pt.fastrime.manager.rime.ImeTarget
import com.pt.fastrime.manager.rime.RimeEnvironment
import com.pt.fastrime.manager.rime.ImportResult
import com.pt.fastrime.manager.rime.RimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AppTab { SCHEMAS, MARKET }

data class UiState(
    val privilege: PrivilegeState = PrivilegeState.Checking,
    val schemas: List<SchemaEntity> = emptyList(),
    val activeSchemaId: String? = null,
    val rimeEnv: RimeEnvironment? = null,
    /** 设备上已安装的输入法（目标输入法下拉框的候选项） */
    val imeTargets: List<ImeTarget> = emptyList(),
    /** 用户选定的目标输入法包名 */
    val selectedIme: String? = null,
    /** 各输入法探测到的 Rime 目录：包名 → 目录（没有目录的输入法不在里面） */
    val imeRimeDirs: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
    val message: String? = null,
    val tab: AppTab = AppTab.SCHEMAS,
    /** 文件浏览器是否打开（打开时盖在界面上方） */
    val browserOpen: Boolean = false,
    /** 文件浏览器起始目录（记住上次浏览位置） */
    val lastBrowseDir: String = com.pt.fastrime.manager.data.SettingsStore.DEFAULT_BROWSE_DIR,
)

/**
 * 主界面状态机。所有高权限操作都经过 PrivilegeManager 当前生效的 Executor，
 * 一旦权限失效（比如用户中途撤销了 Root），会重新探测并把界面切回阻断态。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PtFastRimeApp
    private val manager = app.privilegeManager
    private val repository = app.schemaRepository

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            manager.state.collect { state ->
                _ui.update { it.copy(privilege = state) }
                if (state is PrivilegeState.Root || state is PrivilegeState.Shizuku) {
                    detectEnvironment()
                }
            }
        }
        viewModelScope.launch {
            // 方案清单跟着「目标输入法」走：切到别的输入法就换成它名下的方案
            app.settingsStore.targetImePackage
                .flatMapLatest { pkg -> repository.observeSchemas(pkg.orEmpty()) }
                .collect { list ->
                    _ui.update { it.copy(schemas = list) }
                }
        }
        viewModelScope.launch {
            app.settingsStore.activeSchemaId.collect { id ->
                _ui.update { it.copy(activeSchemaId = id) }
            }
        }
        viewModelScope.launch {
            app.settingsStore.lastBrowseDir.collect { dir ->
                _ui.update { it.copy(lastBrowseDir = dir) }
            }
        }
        // 启动静默检测：不弹授权窗，也不弹提示条；状态只体现在顶部横幅上
        viewModelScope.launch { manager.detect() }
        viewModelScope.launch {
            // 设备上所有输入法都列出来，用户自己选目标，不写死任何一家
            val targets = repository.installedImeTargets(manager.currentExecutor)
            val selected = app.settingsStore.targetImePackageNow()
            _ui.update { it.copy(imeTargets = targets, selectedIme = selected) }
            refreshImeDirs()
        }
    }

    /**
     * 跑一次检测；没检测到高权限就提示用户，不静默吞掉。
     *
     * 检测内部每步都有超时（Root 6 秒、Shizuku 立即返回），所以这里一定会返回，
     * 不会把界面卡在“正在静默检测权限…”。连续失败到第三次就明确叫用户先去授权。
     */
    private suspend fun detectAndNotify(): PrivilegeState {
        val state = try {
            manager.detect()
        } catch (t: Throwable) {
            PrivilegeState.Unavailable(
                reason = "检测异常：${t.message ?: t.javaClass.simpleName}",
                shizukuInstalled = manager.isShizukuInstalled(),
            )
        }
        if (state is PrivilegeState.Unavailable) {
            failedDetects += 1
            val text = if (failedDetects >= 3) {
                "已连续 $failedDetects 次未检测到高权限，请先在 Root 管理器 / Shizuku 中授权，再回来重试"
            } else {
                "未检测到高权限：${state.reason}"
            }
            _ui.update { it.copy(message = text) }
        } else {
            failedDetects = 0
        }
        return state
    }

    private var failedDetects = 0

    private suspend fun detectEnvironment() {
        val executor = manager.currentExecutor
        android.util.Log.d("PtFastRime", "detectEnvironment: executor=${executor?.mode}")
        if (executor == null) return
        withContext(Dispatchers.IO) {
            // 第一步：列出输入法。有了高权限就用系统权威清单（`ime list -a`），
            // 比 PackageManager 那份干净：不会有自动填充、语音输入之类混进来
            val targets = repository.installedImeTargets(executor)
            if (targets.isNotEmpty()) _ui.update { it.copy(imeTargets = targets) }

            // 第二步：探测 Rime 目录。公有路径遍历一轮、私有路径遍历一轮，
            // 两轮都在同一条高权限命令里跑完，输入法再多也只花这一次
            val probes = repository.scanImeProbes(executor, targets)
            val dirs = repository.scanImeDirs(executor, targets, probes)
            _ui.update { it.copy(imeRimeDirs = dirs) }

            // 第三步：定下目标输入法的环境（用户选过就用选的，没选过不瞎猜）
            val env = repository.currentEnvironment(executor, targets, probes)
            android.util.Log.d("PtFastRime", "detectEnvironment: env=$env")
            _ui.update { it.copy(rimeEnv = env, selectedIme = env?.packageName ?: it.selectedIme) }
            // 顺手把设备上已有、但清单里没有的方案收编进来，
            // 且每次都按最终清单重写一遍配置，保证键盘选单与应用里看到的一致。
            if (env != null) {
                val r = try {
                    repository.syncOnLaunch(executor)
                } catch (e: Exception) {
                    ImportResult(false, "扫描失败：${e.message}")
                }
                android.util.Log.d("PtFastRime", "syncOnLaunch: ${r.success} ${r.message}")
            }
        }
    }

    /** 逐个探测已安装输入法的 Rime 目录，供下拉框标注「有没有数据目录」。 */
    private suspend fun refreshImeDirs() {
        val executor = manager.currentExecutor ?: return
        withContext(Dispatchers.IO) {
            val targets = _ui.value.imeTargets.ifEmpty { repository.installedImeTargets(executor) }
            if (targets.isEmpty()) return@withContext
            val dirs = repository.scanImeDirs(executor, targets)

            _ui.update { it.copy(imeRimeDirs = dirs) }
        }
    }

    /** 切换目标输入法：记住选择，然后按新包重新探测 Rime 目录并同步方案。 */
    fun selectIme(packageName: String) {
        val executor = manager.currentExecutor
        if (executor == null) {
            showMessage("当前没有可用的 Root / Shizuku 权限，无法切换目标输入法")
            return
        }
        viewModelScope.launch {
            app.settingsStore.setTargetImePackage(packageName)
            _ui.update { it.copy(selectedIme = packageName, rimeEnv = null, busy = true) }
            detectEnvironment()
            _ui.update { it.copy(busy = false) }
            val env = ui.value.rimeEnv
            val label = ui.value.imeTargets.firstOrNull { it.packageName == packageName }?.label ?: packageName
            val shownDir = env?.rimeDir?.removePrefix("/proc/1/root")
            showMessage(
                if (env != null) "已切换到 $label，Rime 目录：$shownDir"
                else "已切换到 $label，但没有找到它的 Rime 数据目录，请先启动一次该输入法",
            )
        }
    }

    fun setTab(tab: AppTab) = _ui.update { it.copy(tab = tab) }

    fun openBrowser() = _ui.update { it.copy(browserOpen = true) }

    fun closeBrowser() = _ui.update { it.copy(browserOpen = false) }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    fun showMessage(text: String) = _ui.update { it.copy(message = text) }

    suspend fun listDir(path: String): List<DirEntry> {
        val executor = manager.currentExecutor ?: return emptyList()
        return withContext(Dispatchers.IO) { executor.listDir(path) }
    }

    fun importFiles(paths: List<String>) {
        val executor = manager.currentExecutor
        if (executor == null) {
            showMessage("当前没有可用的 Root / Shizuku 权限，无法导入")
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, browserOpen = false) }
            paths.firstOrNull()?.let { app.settingsStore.setLastBrowseDir(it.substringBeforeLast('/')) }
            val result = repository.importFiles(executor, paths)
            _ui.update { it.copy(busy = false, message = result.message) }
        }
    }

    /** 系统文件选择器回调：先把内容 URI 复制到应用缓存，再走同一套导入流程。 */
    fun importUris(uris: List<Uri>) {
        val executor = manager.currentExecutor
        if (executor == null) {
            showMessage("当前没有可用的 Root / Shizuku 权限，无法导入")
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val resolver = getApplication<Application>().contentResolver
            val dir = File(app.cacheDir, "picked").apply { deleteRecursively(); mkdirs() }
            val files = mutableListOf<File>()
            uris.forEachIndexed { index, uri ->
                val name = queryDisplayName(resolver, uri) ?: "picked_$index"
                val target = File(dir, name)
                runCatching {
                    resolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (target.isFile && target.length() > 0) files += target
            }
            val result = if (files.isEmpty()) {
                ImportResult(false, "无法读取所选文件")
            } else {
                repository.importLocalFiles(executor, files)
            }
            _ui.update { it.copy(busy = false, message = result.message) }
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    }.getOrNull()

    fun activate(schemaId: String) {
        val executor = manager.currentExecutor ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val result = repository.activate(executor, schemaId)
            _ui.update { it.copy(busy = false, message = result.message) }
        }
    }

    fun setEnabled(schemaId: String, enabled: Boolean) {
        val executor = manager.currentExecutor ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val result = repository.setEnabled(executor, schemaId, enabled)
            _ui.update { it.copy(busy = false, message = result.message) }
        }
    }

    fun delete(schemaId: String) {
        val executor = manager.currentExecutor ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val result = repository.delete(executor, schemaId)
            _ui.update { it.copy(busy = false, message = result.message) }
        }
    }

    fun restartIme() {
        val executor = manager.currentExecutor ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            // 「刷新」= 完整对齐一遍：收编新方案、剔除失效方案、重写配置、清编译缓存、重启输入法
            val result = repository.forceRedeploy(executor)
            _ui.update { it.copy(busy = false, message = result.message) }
        }
    }

    /** 仅重新设定默认输入法，不重启进程。 */
    fun restoreIme() {
        val executor = manager.currentExecutor ?: return
        viewModelScope.launch {
            val result = repository.restoreIme(executor)
            _ui.update { it.copy(message = result.message) }
        }
    }

    fun restoreDefaultList() {
        val executor = manager.currentExecutor ?: return
        viewModelScope.launch {
            val result = repository.restoreDefaultList(executor)
            _ui.update { it.copy(message = result.message) }
        }
    }

    fun retryDetect() {
        viewModelScope.launch {
            _ui.update { it.copy(privilege = PrivilegeState.Checking) }
            val state = detectAndNotify()
            // StateFlow 对相同的值不会重复发射：重试结果与上次一样时 collect 收不到事件，
            // 界面就会永远停在“正在静默检测权限…”。所以这里把结果显式写回 UI。
            _ui.update { it.copy(privilege = state) }
            if (state is PrivilegeState.Root || state is PrivilegeState.Shizuku) {
                detectEnvironment()
            }
        }
    }
}
