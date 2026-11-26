package com.pt.fastrime.manager.rime

import android.content.Context
import android.util.Log

/**
 * librime 的 JNI 封装。
 *
 * 说明：
 * - 本应用默认不带 librime.so，所以 [isLoaded] 在绝大多数设备上为 false，
 *   此时所有能力都返回 false，上层自动降级为「只写配置文件 + 重启输入法」。
 * - 如果你把输入法同款的 librime.so 放进 jniLibs 并启用 NDK 构建，
 *   这里会加载成功，从而可以直接在应用内完成部署与方案切换。
 */
object RimeNative {

    private const val TAG = "RimeNative"
    private const val LIB_NAME = "pt_rime_jni"

    /** 加载失败（未编译进 APK 或缺少 librime.so）时为 false。 */
    @Volatile
    var isLoaded: Boolean = false
        private set

    private var loadAttempted = false

    private external fun nativeLoad(candidatePaths: Array<String>): Boolean
    private external fun nativeVersion(): String
    private external fun nativeInitialize(sharedDir: String, userDir: String): Boolean
    private external fun nativeDeploy(): Boolean
    private external fun nativeSelectSchema(schemaId: String): Boolean
    private external fun nativeFinalize()

    /**
     * 尝试加载桥接库与 librime。
     *
     * @param context 任意 Context，用于拿本应用与输入法的 nativeLibraryDir
     * @return 是否成功拿到 librime 的函数表
     */
    @Synchronized
    fun load(context: Context): Boolean {
        if (loadAttempted) return isLoaded
        loadAttempted = true
        try {
            System.loadLibrary(LIB_NAME)
        } catch (t: Throwable) {
            Log.i(TAG, "pt_rime_jni 未打包进 APK，跳过 librime 直连：${t.message}")
            return false
        }
        return try {
            isLoaded = nativeLoad(candidateLibraryPaths(context))
            Log.i(TAG, "librime 加载结果：$isLoaded")
            isLoaded
        } catch (t: Throwable) {
            Log.w(TAG, "librime 加载异常", t)
            false
        }
    }

    /** 依次尝试：本应用 nativeLibraryDir → 小企鹅 nativeLibraryDir → 系统库名。 */
    private fun candidateLibraryPaths(context: Context): Array<String> {
        val paths = mutableListOf<String>()
        runCatching {
            paths += "${context.applicationInfo.nativeLibraryDir}/librime.so"
        }
        runCatching {
            val fcitx = context.packageManager.getApplicationInfo("org.fcitx.fcitx5.android", 0)
            paths += "${fcitx.nativeLibraryDir}/librime.so"
        }
        // 纯库名，交给 dlopen 走系统搜索路径（一般找不到，作为最后兜底）
        paths += "librime.so"
        return paths.toTypedArray()
    }

    fun version(): String = if (isLoaded) runCatching { nativeVersion() }.getOrDefault("") else ""

    /** 初始化 Rime 实例。sharedDir 为配置目录，userDir 为用户数据目录。 */
    fun initialize(sharedDir: String, userDir: String): Boolean {
        if (!isLoaded) return false
        return runCatching { nativeInitialize(sharedDir, userDir) }.getOrDefault(false)
    }

    /** 触发一次完整部署（生成 build/ 下的编译产物）。 */
    fun deploy(): Boolean {
        if (!isLoaded) return false
        return runCatching { nativeDeploy() }.getOrDefault(false)
    }

    /** 切换当前会话的输入方案。 */
    fun selectSchema(schemaId: String): Boolean {
        if (!isLoaded) return false
        return runCatching { nativeSelectSchema(schemaId) }.getOrDefault(false)
    }

    fun release() {
        if (!isLoaded) return
        runCatching { nativeFinalize() }
        isLoaded = false
    }
}
