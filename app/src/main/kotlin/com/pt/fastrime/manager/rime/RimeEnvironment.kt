package com.pt.fastrime.manager.rime

import com.pt.fastrime.manager.privilege.PrivilegedExecutor
import com.pt.fastrime.manager.privilege.shellQuote

/**
 * 目标输入法的 Rime 数据目录。
 *
 * @param packageName 目标输入法包名，例如 org.fcitx.fcitx5.android
 * @param rimeDir     Rime 数据目录（部署目录），绝对路径
 */
data class RimeEnvironment(
    val packageName: String,
    val rimeDir: String,
    /** 目录里是否已经有 Rime 的配置文件（用来区分“真正的数据目录”和“只是个存在的目录”） */
    val configured: Boolean = false,
)

/**
 * 单个输入法的探测结果。
 *
 * @param configuredDir 目录里已经有 yaml/txt 的那个位置（真正的 Rime 数据目录）
 * @param existingDir   只是存在的目录，没有配置文件（可能是还没初始化，或者是别的东西）
 */
data class RimeProbe(
    val configuredDir: String?,
    val existingDir: String?,
) {
    val bestDir: String? get() = configuredDir ?: existingDir
}

/**
 * 按包名探测 Rime 数据目录。
 *
 * 不同 Rime 前端放数据的习惯并不一样（小企鹅是 `files/data/rime`，同文/Trime 是 `files/rime`，
 * 还有的放私有目录 `/data/data/<pkg>/files/…`），所以这里不写死任何一家：
 * 按「外部私有目录 → 应用私有目录」×「常见子路径」一次性探测，
 * 目录里已经有 yaml/txt 的优先认作 Rime 数据目录。
 */
object RimePaths {

    private val subDirs = listOf(
        "files/data/rime",
        "files/rime",
        "files/data",
        "files",
        "rime",
        "app_rime",
    )

    private const val EXTERNAL_ROOT = "/storage/emulated/0/Android/data"
    private const val INTERNAL_ROOT = "/data/data"

    /**
     * 应用私有目录的第二种访问方式：走 `/proc/1/root`（init 的根，看到的是完整的 /data/data）。
     *
     * 为什么需要它：Android 11+ 给每个应用一套 mount namespace，`/data/data` 里只挂着
     * 自己（和少数可见包），所以从本进程 fork 出来的 `su` 只能看到 `com.pt.fastrime.manager`
     * 与 gms，别的应用目录直接 “No such file or directory”。`/proc/1/root/data/data/…`
     * 绕开这层 namespace：拷文件、chown、rm 都照常生效，因为指向的是同一个 inode。
     */
    private const val PROC_ROOT = "/proc/1/root"

    fun candidateDirs(packageName: String): List<String> =
        subDirs.map { "$EXTERNAL_ROOT/$packageName/$it" } +
            subDirs.map { "$INTERNAL_ROOT/$packageName/$it" } +
            subDirs.map { "$PROC_ROOT$INTERNAL_ROOT/$packageName/$it" }

    /** 目录还没创建时用于展示/兜底的默认位置（大多数 Rime 前端都用这一套）。 */
    fun defaultDir(packageName: String): String = "$EXTERNAL_ROOT/$packageName/files/data/rime"

    /**
     * 一次高权限调用，把所有输入法的候选目录都探完。
     *
     * 分成两轮遍历：第一轮只走公有目录（`/storage/emulated/0/Android/data/…`），
     * 第二轮只走私有目录（`/data/data/…` 与 `/proc/1/root/data/data/…`）。
     * 两轮都在这同一个 shell 里跑完，所以无论有多少个输入法，一次启动只花这一条命令。
     *
     * 输出形如 `OK|<包名>|<目录>`（目录里有 scheme）与 `DIR|<包名>|<目录>`（只是目录存在）。
     */
    suspend fun scanAll(
        executor: PrivilegedExecutor,
        packageNames: List<String>,
    ): Map<String, RimeProbe> {
        if (packageNames.isEmpty()) return emptyMap()

        val script = buildString {
            // 第一轮：所有输入法的公有目录
            packageNames.forEach { pkg ->
                appendLoop(pkg, subDirs.map { "$EXTERNAL_ROOT/$pkg/$it" })
            }
            // 第二轮：所有输入法的私有目录（/data/data 与 /proc/1/root 入口）
            packageNames.forEach { pkg ->
                appendLoop(
                    pkg,
                    subDirs.map { "$INTERNAL_ROOT/$pkg/$it" } +
                        subDirs.map { "$PROC_ROOT$INTERNAL_ROOT/$pkg/$it" },
                )
            }
        }
        return parseScan(executor.exec(script).output)
    }

    /**
     * 生成一个包名的遍历循环。
     *
     * 包名直接写死在 echo 里，不从变量里拆——Android 的 mksh 会把 `${e%%|*}` 里的 `|`
     * 当成管道，`p=${e%%|*}` 直接展开成空串，整个探测就静默失效了（踩过一次）。
     */
    private fun StringBuilder.appendLoop(pkg: String, dirs: List<String>) {
        append("for d in")
        dirs.forEach { append(' ').append(shellQuote(it)) }
        append("; do [ -d \"\$d\" ] || continue; ")
        // 判定「这真的是 Rime 数据目录」的依据：
        // 有 *.schema.yaml（自己带方案）或有 installation.yaml（Rime 初始化过，方案可能在 APK 内置）
        append("if ls -1 \"\$d\" 2>/dev/null | grep -qm1 -E '^(installation\\.yaml|.*\\.schema\\.yaml)\$'; ")
        append("then echo \"OK|$pkg|\$d\"; else echo \"DIR|$pkg|\$d\"; fi; done; ")
    }

    private fun parseScan(output: String): Map<String, RimeProbe> {
        val configured = LinkedHashMap<String, String>()
        val existing = LinkedHashMap<String, String>()
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            val parts = line.split('|')
            if (parts.size != 3) return@forEach
            val kind = parts[0]
            val pkg = parts[1]
            val dir = parts[2]
            if (pkg.isEmpty() || dir.isEmpty()) return@forEach
            if (existing[pkg] == null) existing[pkg] = dir
            if (kind == "OK" && configured[pkg] == null) configured[pkg] = dir
        }
        return (configured.keys + existing.keys).associateWith { pkg ->
            RimeProbe(configured[pkg], existing[pkg])
        }
    }

    /** 单个输入法的探测，等价于 `scanAll` 只传一个包名。 */
    suspend fun scan(executor: PrivilegedExecutor, packageName: String): RimeProbe =
        scanAll(executor, listOf(packageName))[packageName] ?: RimeProbe(null, null)

    suspend fun detect(executor: PrivilegedExecutor, packageName: String): RimeEnvironment? {
        val probe = scan(executor, packageName)
        val dir = probe.bestDir ?: return null
        return RimeEnvironment(packageName, dir, configured = probe.configuredDir != null)
    }
}
