package com.pt.fastrime.manager.privilege

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 当前权限状态。启动时只可能是 Checking → (Root | Shizuku | Unavailable)。
 * UI 根据它决定：显示绿色横幅 / 黄色提示 / 直接阻断核心功能。
 */
sealed interface PrivilegeState {
    data object Checking : PrivilegeState
    data class Root(val executor: PrivilegedExecutor) : PrivilegeState
    data class Shizuku(val executor: PrivilegedExecutor) : PrivilegeState

    /**
     * 两种高权限都不可用。
     * @param shizukuInstalled 设备上是否安装了 Shizuku（用来区分“没装”和“装了没授权”）
     */
    data class Unavailable(
        val reason: String,
        val shizukuInstalled: Boolean,
    ) : PrivilegeState
}

/**
 * 权限探测与持有者。
 *
 * 严格遵循需求：构造 / 启动阶段不做任何弹窗、不触发 Shizuku 授权请求，
 * 只是静默尝试 `su -c id` 和 Shizuku 的 binder 检查。授权动作只发生在
 * 用户点击主界面提示条时（见 MainViewModel.requestShizukuPermission）。
 */
class PrivilegeManager(private val context: Context) {

    private val rootExecutor = RootExecutor(context.cacheDir)
    private val shizukuExecutor = ShizukuExecutor()

    private val _state = MutableStateFlow<PrivilegeState>(PrivilegeState.Checking)
    val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    val currentExecutor: PrivilegedExecutor?
        get() = when (val s = _state.value) {
            is PrivilegeState.Root -> s.executor
            is PrivilegeState.Shizuku -> s.executor
            else -> null
        }

    suspend fun detect(): PrivilegeState = withContext(Dispatchers.IO) {
        // 1) 先试 Root：Magisk 环境下 su 通常几百毫秒内返回；未授权时 isAvailable 为 false，
        //    不会弹窗（Magisk 只会发一条通知，这是系统行为，应用无法也不应拦截）。
        if (rootExecutor.isAvailable()) {
            PrivilegeState.Root(rootExecutor).also { _state.value = it }
        } else if (shizukuExecutor.isAvailable()) {
            PrivilegeState.Shizuku(shizukuExecutor).also { _state.value = it }
        } else {
            val installed = isShizukuInstalled()
            val reason = when {
                installed -> "未检测到 Root 权限；已安装 Shizuku，但尚未授权给本应用"
                else -> "未检测到 Root（su）权限，也未检测到可用的 Shizuku"
            }
            PrivilegeState.Unavailable(reason, installed).also { _state.value = it }
        }
    }

    /** Shizuku 是否安装（用于提示文案与“去授权”按钮是否展示）。 */
    fun isShizukuInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Shizuku 是否已授权。只读检查，不请求。 */
    fun shizukuPermissionGranted(): Boolean = runCatching {
        rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_REQUEST_CODE = 1001
    }
}
