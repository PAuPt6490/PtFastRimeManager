package com.pt.fastrime.manager.rime

import android.content.Context
import android.util.Base64
import com.pt.fastrime.manager.data.SchemaEntity
import com.pt.fastrime.manager.privilege.PrivilegedExecutor
import com.pt.fastrime.manager.privilege.shellQuote
import java.io.File

/**
 * Rime 部署器：负责“把私有目录里的方案文件推送进目标输入法的 Rime 数据目录”，
 * 并生成 Rime 能识别的 user.yaml / default.custom.yaml。
 *
 * 关键约定：
 * - 所有写操作都走 PrivilegedExecutor，不使用 SAF、不申请存储权限；
 * - 覆盖前一定备份原文件（*.ptfastbak），用户原有配置可回滚；
 * - 文件属主统一对齐到 Rime 数据目录的属主（fcitx5 的 uid），否则输入法读不到。
 */
class RimeDeployer(private val context: Context) {

    companion object {
        const val HEADER = "# 由 PtFastRime 配置生成"
        private const val MAX_IMPORT_BYTES = 32L * 1024 * 1024
    }

    suspend fun readRemoteText(executor: PrivilegedExecutor, path: String): String? =
        executor.readText(path)

    /**
     * 通过 `base64` 把设备上的任意文件读回应用进程。
     * 用 base64 而不是 `cat`：词库是 UTF-8 文本，但直接 cat 再按行读取容易被
     * shell 的编码/换行处理破坏；base64 是二进制安全的最小方案。
     */
    suspend fun readRemoteBytes(executor: PrivilegedExecutor, path: String): ByteArray? {
        val sizeResult = executor.exec("stat -c %s ${shellQuote(path)}")
        val size = sizeResult.output.trim().toLongOrNull() ?: return null
        if (size <= 0L || size > MAX_IMPORT_BYTES) return null

        val result = executor.exec("base64 ${shellQuote(path)}")
        if (!result.ok) return null
        val cleaned = result.output.filterNot { it.isWhitespace() }
        return runCatching {
            Base64.decode(cleaned, Base64.DEFAULT)
        }.getOrNull()
    }

    /** 把私有目录里的文件安装到 Rime 目录；返回失败的文件名（附失败原因）。 */
    suspend fun installFiles(
        executor: PrivilegedExecutor,
        env: RimeEnvironment,
        localDir: File,
        fileNames: List<String>,
    ): List<String> {
        val failed = mutableListOf<String>()
        for (name in fileNames) {
            val src = File(localDir, name)
            if (!src.isFile) {
                failed += "$name（本地副本不存在）"
                continue
            }
            // 方案包里可能带子目录（如 cn_dicts/xxx.dict.yaml），先确保远端目录存在
            val parent = name.substringBeforeLast('/', missingDelimiterValue = "")
            if (parent.isNotEmpty()) {
                executor.mkdirs("${env.rimeDir}/$parent")
            }
            // 关键：Shizuku 以 shell(uid 2000) 身份执行，读不到本应用的私有目录，
            // 所以先把文件转到外部私有目录（shell 可读）再复制过去。
            val staged = stageForCopy(name, src)
            if (staged == null) {
                failed += "$name（无法创建中转副本）"
                continue
            }
            val result = executor.copyFile(staged.absolutePath, "${env.rimeDir}/$name")
            staged.delete()
            if (!result.ok) {
                failed += "$name（${result.output.trim().take(80)}）"
            }
        }
        return failed
    }

    /**
     * 把文件复制到外部私有目录（/sdcard/Android/data/<pkg>/files/stage），
     * 使 shell 身份的高权限进程也能读到它。返回中转文件。
     */
    private fun stageForCopy(name: String, src: File): File? = runCatching {
        val stageRoot = context.getExternalFilesDir("stage") ?: return@runCatching null
        val dst = File(stageRoot, name.replace('/', '_'))
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        dst
    }.getOrNull()

    suspend fun removeFiles(
        executor: PrivilegedExecutor,
        env: RimeEnvironment,
        fileNames: List<String>,
    ): Boolean {
        if (fileNames.isEmpty()) return true
        val cmd = fileNames.joinToString(" && ") {
            "rm -f ${shellQuote("${env.rimeDir}/$it")}"
        }
        return executor.exec(cmd).ok
    }

    /**
     * 生成 default.custom.yaml（受管方案列表）与 user.yaml（当前激活方案）。
     * 只在用户至少导入了一个方案时接管列表；否则保持原样，避免“一进来就把别人的方案全隐藏”。
     */
    suspend fun deploy(
        executor: PrivilegedExecutor,
        env: RimeEnvironment,
        schemas: List<SchemaEntity>,
        activeSchemaId: String?,
    ): Boolean {
        // 只保留「方案文件确实还在 Rime 目录里」的方案：
        // 用户可能手工删掉了某个方案的 yaml，此时如果仍写进 schema_list，
        // 输入法选单里就会留下一个点了没反应、也删不掉的幽灵条目。
        val enabled = schemas.filter { it.enabled && schemeFilesPresent(executor, env.rimeDir, it.schemaId) }

        val ordered = enabled.sortedWith(
            compareByDescending<SchemaEntity> { it.schemaId == activeSchemaId }
                .thenBy { it.displayName },
        )

        // 关键：不能只写“本应用导入的方案”，否则会把 rime_ice、朙月拼音等原有方案从选单里抹掉。
        // 做法：扫一遍 Rime 目录，把目录里所有 *.schema.yaml 都当成可用方案；
        // 本应用管理的方案（激活的排最前）在前，其余目录里扫到的方案按文件名顺序跟在后面。
        val discovered = scanSchemas(executor, env.rimeDir)
        // 目录里一个方案都没有时不动配置，避免生成一份空的 schema_list 把输入法选单清空。
        if (discovered.isEmpty()) return false
        val managed = ordered.map { it.schemaId }.toSet()
        val tail = discovered.filterNot { it in managed }

        val defaultCustom = buildString {
            appendLine(HEADER + "，请勿手工修改；删除本文件可恢复原始方案列表。")
            appendLine("patch:")
            appendLine("  schema_list:")
            ordered.forEach { appendLine("    - schema: ${it.schemaId}") }
            tail.forEach { appendLine("    - schema: $it") }
        }
        val target = "${env.rimeDir}/default.custom.yaml"
        // 先读旧内容，写完再判断是否真的变了：变了就要让编译缓存失效，否则选单还是旧列表。
        val previous = executor.readText(target)
        pushConfig(executor, target, defaultCustom)
        val listChanged = previous?.trimEnd() != defaultCustom.trimEnd()

        // 没有受管方案时（例如用户只是让应用重建列表），不写 user.yaml，避免动到用户的输入状态。
        val active = activeSchemaId ?: ordered.firstOrNull()?.schemaId ?: return listChanged
        val now = System.currentTimeMillis() / 1000
        val userYaml = buildString {
            appendLine(HEADER + "，记录上次激活方案。")
            appendLine("var:")
            appendLine("  last_build_time: 0")
            appendLine("  previously_selected_schema: $active")
            appendLine("  schema_access_time:")
            appendLine("    $active: $now")
        }

        pushConfig(executor, "${env.rimeDir}/user.yaml", userYaml)

        // 编译产物里的列表如果和刚写的一致，就不用动缓存；不一致说明键盘还读着旧列表，
        // 丢掉 build/default.yaml 强制下次启动按新配置重建（这就是幽灵条目的来源）。
        val expected = ordered.map { it.schemaId } + tail
        val compiled = compiledSchemaList(executor, env)
        if (compiled != null && compiled != expected) {
            invalidateCompiled(executor, env, emptyList())
        }
        return listChanged
    }

    /** 判断某个方案的 .schema.yaml 是否还在 Rime 目录里（外部被删时要据此清理幽灵条目）。 */
    suspend fun schemeFilesPresent(executor: PrivilegedExecutor, rimeDir: String, schemaId: String): Boolean =
        executor.exists("$rimeDir/$schemaId.schema.yaml")

    /**
     * 读取编译产物里的 schema_list（build/default.yaml），用于判断键盘选单和源配置是否一致。
     * 读不到文件时返回 null，表示「还没有编译过」。
     */
    suspend fun compiledSchemaList(executor: PrivilegedExecutor, env: RimeEnvironment): List<String>? {
        val text = executor.readText("${env.rimeDir}/build/default.yaml") ?: return null
        val lines = text.lineSequence().toList()
        val start = lines.indexOfFirst { it.trimEnd() == "schema_list:" }
        if (start < 0) return emptyList()
        val result = mutableListOf<String>()
        for (line in lines.drop(start + 1)) {
            // 顶格的下一段配置就是 schema_list 结束
            if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("-")) break
            Regex("^\\s*-\\s*schema:\\s*([A-Za-z0-9_.\\-]+)").find(line)?.let { result += it.groupValues[1] }
        }
        return result
    }

    /**
     * 强制重新部署：删掉 Rime 的 build 缓存目录。
     * 只改配置不删缓存时，输入法可能不重新编译，选单里就会出现「删了文件但条目还在」的情况。
     * build 目录是可再生的，删掉后输入法下次启动会重新编译全部方案。
     */
    suspend fun forceRedeploy(executor: PrivilegedExecutor, env: RimeEnvironment): Boolean {
        val result = executor.exec("rm -rf ${shellQuote("${env.rimeDir}/build")}")
        return result.ok
    }

    /**
     * 丢弃和这些方案相关的编译产物，解决「文件删了、选单里还留着幽灵条目」。
     *
     * - `build/default.yaml` 是编译后的配置快照，输入法选单读的是它里面的 schema_list；
     *   只改 default.custom.yaml 不会立刻生效（要等 Rime 自己判断需要重新编译），
     *   删掉它就能强制下次启动时按新的 default.custom.yaml 重建选单。
     * - `build/<id>.prism.bin` 等是方案自身的索引缓存，方案都删了留着只会误导。
     *
     * 这里只挑相关文件删，不像 forceRedeploy 那样整目录清空，
     * 避免用户下次打开键盘时要等一两分钟做全量重编译。
     */
    suspend fun invalidateCompiled(
        executor: PrivilegedExecutor,
        env: RimeEnvironment,
        schemaIds: List<String>,
    ) {
        val buildDir = "${env.rimeDir}/build"
        val targets = mutableListOf("$buildDir/default.yaml")
        schemaIds.forEach { id ->
            targets += "$buildDir/$id.schema.yaml"
            targets += "$buildDir/$id.prism.bin"
            targets += "$buildDir/$id.reverse.bin"
            targets += "$buildDir/$id.table.bin"
        }
        val cmd = targets.joinToString(" && ") { "rm -f ${shellQuote(it)}" }
        executor.exec(cmd)
    }

    /**
     * 扫描 Rime 目录，收集所有 `*.schema.yaml` 对应的方案 id。
     * Rime 以「文件名（去掉 .schema.yaml）」作为方案 id，因此这里直接按文件名取，
     * 既不用解析 yaml 内容，也能覆盖用户自己放进去的、以及输入法自带的方案。
     */
    private suspend fun scanSchemas(executor: PrivilegedExecutor, rimeDir: String): List<String> =
        executor.listDir(rimeDir)
            .asSequence()
            .filter { !it.isDirectory }
            .map { it.name }
            .filter { it.endsWith(".schema.yaml", ignoreCase = true) }
            .map { it.substring(0, it.length - ".schema.yaml".length) }
            .filter { it.isNotBlank() }
            .sorted()
            .toList()

    /** 删除本应用生成的 default.custom.yaml，恢复用户原有方案列表。 */
    suspend fun restoreDefaultCustom(executor: PrivilegedExecutor, env: RimeEnvironment) {
        val path = "${env.rimeDir}/default.custom.yaml"
        val current = executor.readText(path) ?: return
        if (current.contains(HEADER)) {
            executor.delete(path)
        }
    }

    /**
     * 写入一个配置文件：先写应用缓存，再由高权限复制并修正属主。
     * 覆盖前把原文件备份为 <name>.ptfastbak（只在首次备份，避免反复覆盖用户可回滚点）。
     */
    private suspend fun pushConfig(executor: PrivilegedExecutor, targetPath: String, content: String) {
        val name = targetPath.substringAfterLast('/')
        val backup = "$targetPath.ptfastbak"
        if (!executor.exists(backup) && executor.exists(targetPath)) {
            val original = executor.readText(targetPath)
            if (original != null && !original.contains(HEADER)) {
                val cache = File(context.cacheDir, "backup_$name")
                cache.writeText(original)
                stageForCopy(name, cache)?.let { executor.copyFile(it.absolutePath, backup) }
            }
        }
        val temp = File(context.cacheDir, "push_$name")
        temp.writeText(content)
        val staged = stageForCopy(name, temp)
        if (staged != null) executor.copyFile(staged.absolutePath, targetPath)
        temp.delete()
        staged?.delete()
    }

    /** 读取系统当前的默认输入法（形如 `包名/服务类`），读不到返回 null。 */
    suspend fun currentDefaultIme(executor: PrivilegedExecutor): String? {
        val raw = executor.exec("settings get secure default_input_method").output.trim()
        return raw.takeIf { it.contains('/') && !it.equals("null", true) }
    }

    /**
     * 系统里所有输入法的权威清单（`ime list -a`），返回 `包名/服务类`。
     *
     * 为什么还要走 shell：`PackageManager` 的结果受 Android 11+ 的包可见性限制，
     * 用户装了但没启用的输入法可能根本查不到；`ime list -a` 是系统自己的清单，最全。
     */
    suspend fun listImeIds(executor: PrivilegedExecutor): List<String> =
        executor.exec("ime list -a").output
            .lineSequence()
            .map { it.trim() }
            .filter { it.endsWith(":") && it.contains('/') }
            .map { it.removeSuffix(":").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    /** 在已安装的输入法里找某个包对应的输入法 id（兼容同一包多输入法的情况）。 */    suspend fun resolveImeId(executor: PrivilegedExecutor, packageName: String): String? =
        executor.exec("ime list -a").output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$packageName/") }

    /**
     * 把默认输入法设为指定 id：先确保它在已启用列表里，再 `ime set` 切换。
     * 只切默认输入法，不改动用户的输入法启用列表。
     */
    suspend fun setIme(executor: PrivilegedExecutor, imeId: String): Boolean {
        executor.exec("ime enable ${shellQuote(imeId)}")
        return executor.exec("ime set ${shellQuote(imeId)}").ok
    }

    /**
     * 重启目标输入法：先 force-stop 杀掉旧进程（Rime 引擎会随之卸载），再拉起它的主界面。
     * 只有杀掉旧进程才能保证下一次初始化读到我们刚写入的 yaml 与 build 配置。
     */
    suspend fun restartIme(executor: PrivilegedExecutor, packageName: String): Boolean {
        executor.exec("am force-stop ${shellQuote(packageName)}")
        // 不知道对方 Activity 名时先用 PackageManager 解析，失败再退回 monkey 启动
        val resolved = executor.exec("cmd package resolve-activity --brief ${shellQuote(packageName)}")
            .output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.contains('/') && !it.startsWith("priority") }
        if (resolved != null) {
            val started = executor.exec("am start -n ${shellQuote(resolved)}")
            if (started.ok) return true
        }
        return executor.exec(
            "monkey -p ${shellQuote(packageName)} -c android.intent.category.LAUNCHER 1",
        ).ok
    }
}
