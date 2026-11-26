package com.pt.fastrime.manager.privilege

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 `su -c` 的 Root 实现。
 *
 * 不依赖 LibSu：当前构建环境无法联网拉取第三方库，而 Root 执行本身只是
 * “启动 su 子进程 + 读取输出”，自研一个执行器即可满足需求，也更容易加超时与失败兜底。
 */
class RootExecutor(private val tempDir: java.io.File) : PrivilegedExecutor {

    override val mode: PrivilegeMode = PrivilegeMode.ROOT

    /** 检测用短超时：没授权时 su 可能一直挂着，不能把界面卡在“检测中”。 */
    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        val r = shell("id", timeoutSeconds = 6)
        r.ok && Regex("uid=0\\b").containsMatchIn(r.output)
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        shell(command, timeoutSeconds = 20)
    }

    private fun shell(command: String, timeoutSeconds: Long): ShellResult =
        ShellRunner.run("su", tempDir, timeoutSeconds) { out ->
            // 命令套在子 shell 里执行，末尾打一行哨兵（退出码）：
            // 一来能拿到真实退出码，二来 ShellRunner 可以等哨兵出现再读文件，
            // 避免 su 先返回、子 shell 还在写的时候读到半截输出。
            val script = "( $command ) 2>&1; printf '\\n%s%s\\n' '${ShellRunner.MARKER}' \"\$?\""
            ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .redirectOutput(out)
                .start()
        }

    override suspend fun copyFile(source: String, target: String): ShellResult {
        // 先复制，再把属主/权限对齐到目标目录：Rime 以 fcitx5 的 uid 运行，
        // 文件属于 root 时它会读不到，部署会静默失败，所以这一步不能省。
        val dir = target.substringBeforeLast('/', missingDelimiterValue = "")
        val fix = if (dir.isNotEmpty()) {
            "cp -f ${shellQuote(source)} ${shellQuote(target)} && " +
                "chown \$(stat -c '%u:%g' ${shellQuote(dir)}) ${shellQuote(target)} && " +
                "chmod 660 ${shellQuote(target)}"
        } else {
            "cp -f ${shellQuote(source)} ${shellQuote(target)}"
        }
        return exec(fix)
    }
}
