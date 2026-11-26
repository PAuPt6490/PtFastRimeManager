package com.pt.fastrime.manager.privilege

/**
 * 权限模式。应用启动时静默探测，UI 只读取结果，不主动申请任何权限。
 */
enum class PrivilegeMode {
    ROOT,
    SHIZUKU,
    NONE,
}

/**
 * 一次 shell 执行的结果。stdout / stderr 已合并读取，exitCode 来自进程退出码。
 */
data class ShellResult(
    val exitCode: Int,
    val output: String,
) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * 目录项：name 为文件名，isDirectory 表示是否是目录。
 */
data class DirEntry(val name: String, val isDirectory: Boolean)

/**
 * 统一的高权限执行抽象层。
 *
 * 上层（仓库、Rime 部署、文件浏览）只依赖本接口，完全不感知底层是 Root 还是 Shizuku。
 * 之所以要这样抽象：Root 与 Shizuku 的可用性、启动方式、失败表现都不同，
 * 一旦把 `su` 写死在业务里，两种模式的降级逻辑会散落各处、很难维护。
 */
interface PrivilegedExecutor {

    val mode: PrivilegeMode

    /** 是否仍然可用（例如 Root 被临时撤销、Shizuku 服务被停）。 */
    suspend fun isAvailable(): Boolean

    /** 执行一条 shell 命令；command 会交给 sh 解释，请注意自行转义参数。 */
    suspend fun exec(command: String): ShellResult

    /** 创建目录（等价 mkdir -p）。 */
    suspend fun mkdirs(path: String): ShellResult = exec("mkdir -p ${shellQuote(path)}")

    /** 文件是否存在。 */
    suspend fun exists(path: String): Boolean =
        exec("[ -e ${shellQuote(path)} ] && echo 1 || echo 0").output.trim() == "1"

    /** 读取文本文件；失败返回 null。 */
    suspend fun readText(path: String): String? {
        val r = exec("cat ${shellQuote(path)}")
        return if (r.ok) r.output else null
    }

    /** 把 root 可读的文件复制到目标路径，并尽量保持目标目录的属主/权限。 */
    suspend fun copyFile(source: String, target: String): ShellResult

    /** 删除文件或目录。 */
    suspend fun delete(path: String): ShellResult = exec("rm -rf ${shellQuote(path)}")

    /** 列出目录内容；path 不存在时返回空列表。 */
    suspend fun listDir(path: String): List<DirEntry> {
        // 用 sh 的 for 循环输出 "D:目录名" / "F:文件名"，避免依赖 ls 的格式化参数（不同 ROM 的 toybox 差异很大）
        val cmd = "cd ${shellQuote(path)} 2>/dev/null || exit 3; " +
            "for f in * .[!.]*; do [ -e \"\$f\" ] || continue; " +
            "if [ -d \"\$f\" ]; then echo \"D:\$f\"; else echo \"F:\$f\"; fi; done; exit 0"
        val r = exec(cmd)
        if (r.exitCode != 0) return emptyList()
        return r.output.lineSequence()
            .filter { it.length > 2 && (it.startsWith("D:") || it.startsWith("F:")) }
            .mapNotNull { line ->
                val isDir = line[0] == 'D'
                val name = line.substring(2)
                if (name == "." || name == "..") null else DirEntry(name, isDir)
            }
            .sortedWith(compareByDescending<DirEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()
    }
}

/**
 * 单引号转义，避免路径里的空格 / 引号 / $ 被 shell 解释。
 */
fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"
