package com.pt.fastrime.manager.privilege

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 封装“启动子进程 → 读取输出 → 超时保护”的重复逻辑，供 Root / Shizuku 两个实现共用。
 */
internal object ShellRunner {

    /** 命令结束时由 shell 打印的哨兵行前缀，用来判断“输出真的写完了”。 */
    const val MARKER = "__PT_EXIT__"

    /**
     * 把子进程的 stdout/stderr 重定向到临时文件，退出后再整块读回来。
     *
     * 不能走管道，两种情况都会踩坑：
     * 1) 子进程卡住不退出（例如 su 在等 Root 管理器授权）时，读管道会永久阻塞，
     *    界面就一直停在“正在静默检测权限…”；
     * 2) Android 的 `Process.waitFor(timeout, unit)` 返回时会把管道关掉，
     *    另起线程异步读只能拿到空串，表现为“命令成功但没有输出”（Root 检测失败）。
     *
     * 重定向到文件两头都稳：超时直接 destroy，已经写进去的内容仍然读得到。
     *
     * 但文件还有第三个坑：某些 su 实现会先返回、子 shell 仍在往后写，
     * 这时 `waitFor` 一到就读文件只能读到前半截（表现为“只输出了一行”“输出为空”）。
     * 所以这里再等哨兵行出现，确认输出确实写完才返回。
     */
    fun run(
        executorName: String,
        tempDir: File,
        timeoutSeconds: Long = 20,
        start: (File) -> Process,
    ): ShellResult {
        val outFile = File(tempDir, "shell_${System.nanoTime()}.out")
        return try {
            val process = start(outFile)
            val finished = runCatching {
                process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            }.getOrDefault(false)

            if (finished) {
                val text = readUntilMarker(outFile, timeoutSeconds = 10)
                ShellResult(codeOf(text), stripMarker(text))
            } else {
                runCatching { process.destroy() }
                runCatching { process.waitFor(2, TimeUnit.SECONDS) }
                ShellResult(
                    -1,
                    stripMarker(readText(outFile)) + "\n[超时] $executorName 命令 $timeoutSeconds 秒未返回",
                )
            }
        } catch (t: Throwable) {
            ShellResult(-1, "$executorName 启动失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { outFile.delete() }
        }
    }

    /**
     * 等哨兵行写进文件（最多 [timeoutSeconds] 秒）。
     * 哨兵没出现时（比如命令把 shell 自己 exit 掉了）退化为“内容稳定就返回”。
     */
    private fun readUntilMarker(file: File, timeoutSeconds: Long): String {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        var last = ""
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val now = readText(file)
            if (now.contains(MARKER)) return now
            if (now != last) {
                last = now
                stableSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - stableSince > 600) {
                // 内容 600ms 没变化：认为输出已经写完（没有哨兵行的情况）
                return now
            }
            runCatching { Thread.sleep(60) }
        }
        return readText(file)
    }

    /** 从哨兵行里取出退出码（`__PT_EXIT__0`）。 */
    private fun codeOf(text: String): Int {
        val index = text.lastIndexOf(MARKER)
        if (index < 0) return 0
        return text.substring(index + MARKER.length).trim().toIntOrNull() ?: 0
    }

    /** 去掉哨兵行，只留命令本身的输出。 */
    private fun stripMarker(text: String): String {
        val index = text.lastIndexOf(MARKER)
        return if (index >= 0) text.substring(0, index).trimEnd('\n') else text
    }

    private fun readText(file: File): String =
        runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
}
