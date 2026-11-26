package com.pt.fastrime.manager.privilege

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * 基于 Shizuku 的实现：把命令交给 Shizuku 的服务进程执行，效果等价于 `adb shell`。
 *
 * 说明：Shizuku 13.x 的 `Shizuku.newProcess` 已被标记为私有，官方推荐通过
 * `Shizuku.getBinder()` 取到 IShizukuService 后再调 `newProcess`（同样受 Shizuku 授权保护）。
 * 这里只做“已授权检测”，绝不在启动时调用 requestPermission——
 * 需求要求启动静默检测，授权入口只出现在主界面的权限提示条上（由用户主动点击）。
 */
class ShizukuExecutor : PrivilegedExecutor {

    override val mode: PrivilegeMode = PrivilegeMode.SHIZUKU

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        val output = StringBuilder()
        try {
            val binder = Shizuku.getBinder() ?: return@withContext ShellResult(-1, "Shizuku 服务未连接")
            val service = IShizukuService.Stub.asInterface(binder)
                ?: return@withContext ShellResult(-1, "无法获取 Shizuku 服务接口")

            // 合并 stderr 到 stdout，并在末尾打印退出码。
            // 不依赖 IRemoteProcess.waitForTimeout/exitValue（不同 Shizuku 版本行为不一致，
            // 实测会出现“输出读到了但退出码拿不到”的情况），改用哨兵行解析，稳定可靠。
            //
            // 注意：命令必须放在子 shell 里执行。不少调用方（如 listDir）会在命令末尾写
            // `exit 0` / `exit 3` 来控制状态码，如果直接拼在同一个 shell 里，那个 exit 会
            // 提前结束整个脚本，哨兵行永远打不出来，于是输出被当成失败丢掉。
            val marker = "__PT_EXIT__"
            val script = "( $command ) 2>&1; printf '\\n%s%s\\n' '$marker' \"\$?\""
            val remote = service.newProcess(arrayOf("sh", "-c", script), null, null)
                ?: return@withContext ShellResult(-1, "Shizuku 拒绝创建进程")

            ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.append(String(buffer, 0, read))
                }
            }

            val text = output.toString()
            val markerIndex = text.lastIndexOf(marker)
            if (markerIndex >= 0) {
                val code = text.substring(markerIndex + marker.length).trim().toIntOrNull() ?: 0
                runCatching { remote.destroy() }
                return@withContext ShellResult(code, text.substring(0, markerIndex).trimEnd('\n'))
            }
            runCatching { remote.destroy() }
            ShellResult(-1, text)
        } catch (e: RemoteException) {
            ShellResult(-1, "Shizuku 远程调用失败：${e.message}\n$output")
        } catch (t: Throwable) {
            ShellResult(-1, "${t.javaClass.simpleName}: ${t.message}\n$output")
        }
    }

    override suspend fun copyFile(source: String, target: String): ShellResult {
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
