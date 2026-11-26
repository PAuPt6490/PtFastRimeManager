package com.pt.fastrime.manager.rime

import android.content.Context
import com.pt.fastrime.manager.data.AppDatabase
import com.pt.fastrime.manager.data.SchemaEntity
import com.pt.fastrime.manager.data.SettingsStore
import com.pt.fastrime.manager.privilege.PrivilegedExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/** 导入结果：一句话给 UI 直接展示。 */
data class ImportResult(val success: Boolean, val message: String)

/**
 * 方案仓库：把“读取设备文件 → 存私有副本 → 安装进 Rime → 更新数据库与激活项”串成一条链路。
 *
 * 支持两种导入形态：
 * - 单个 .schema.yaml / .dict.yaml（可多选）；
 * - .zip 方案包（打包好的方案，解压后把所有 .yaml 按原目录结构安装）。
 * 无论哪种，文件都先落到应用私有目录，再由高权限复制到目标输入法的 Rime 数据目录。
 */
class SchemaRepository(
    private val context: Context,
    private val settings: SettingsStore,
) {

    private val dao = AppDatabase.get(context).schemaDao()
    private val deployer = RimeDeployer(context)

    companion object {
        /** 「设备上已有方案」的虚拟包名：这些记录没有私有副本，只是把目录里的方案收编进清单。 */
        const val DEVICE_GROUP = "@device"
    }

    /** 一个待安装的方案：本地根目录 + 相对文件列表。 */
    private class ImportItem(
        val localDir: File,
        val fileNames: List<String>,
        val displayName: String,
        /** 来自 .zip 时记录包名，单文件导入为空串 */
        val packageName: String = "",
    )

    /** 只观察当前目标输入法名下的方案，切输入法时由上层重新订阅。 */
    fun observeSchemas(imePackage: String): Flow<List<SchemaEntity>> = dao.observeForIme(imePackage)

    suspend fun loadSchemas(imePackage: String): List<SchemaEntity> = dao.getAllForIme(imePackage)

    suspend fun activeSchemaId(): String? = settings.activeSchemaId.firstOrNull()

    /**
     * 逐个探测已安装输入法的 Rime 目录，返回「包名 → 目录」。
     * 只返回探测到目录的项，没有目录的输入法保持缺席，界面据此标注。
     */
    suspend fun scanImeDirs(
        executor: PrivilegedExecutor,
        targets: List<ImeTarget>,
        probes: Map<String, RimeProbe>? = null,
    ): Map<String, String> {
        val all = probes ?: RimePaths.scanAll(executor, targets.map { it.packageName })
        // 用「有 Rime 配置」的那个目录来标注，只有个空目录的不算
        return targets.mapNotNull { target ->
            all[target.packageName]?.configuredDir?.let { target.packageName to it }
        }.toMap()
    }

    /** 一次性探完所有输入法的目录，结果给界面与后续判断复用。 */
    suspend fun scanImeProbes(
        executor: PrivilegedExecutor,
        targets: List<ImeTarget>,
    ): Map<String, RimeProbe> = RimePaths.scanAll(executor, targets.map { it.packageName })

    /** 设备上已安装的输入法，供界面里的「目标输入法」下拉框使用。 */
    fun installedImeTargets(): List<ImeTarget> = ImeTargets.installed(context)

    /**
     * 输入法清单：优先用 `ime list -a` 的权威结果（能列出装了但没启用的输入法），
     * 名称尽量取 PackageManager 里的应用名；查不到的退回包名。
     */
    suspend fun installedImeTargets(executor: PrivilegedExecutor?): List<ImeTarget> {
        val fromPm = ImeTargets.installed(context)
        if (executor == null) return fromPm
        val ids = runCatching { deployer.listImeIds(executor) }.getOrDefault(emptyList())
        if (ids.isEmpty()) return fromPm
        return ids.map { id ->
            val pkg = id.substringBefore('/')
            val known = fromPm.firstOrNull { it.packageName == pkg }
            known ?: ImeTarget(pkg, id.substringAfter('/'), pkg)
        }.distinctBy { it.packageName }
    }

    /**
     * 当前目标输入法的 Rime 环境。
     *
     * 用户选过就直接用选的那个；没选过时**只在只有一个输入法能探到 Rime 目录时**才自动认定，
     * 多个输入法都像 Rime 前端时一律不猜——猜错会把配置写到人家目录里，把别人的方案列表改掉。
     * 这种情况返回 null，界面提示用户自己选。
     */
    suspend fun currentEnvironment(
        executor: PrivilegedExecutor,
        targets: List<ImeTarget>? = null,
        probes: Map<String, RimeProbe>? = null,
    ): RimeEnvironment? {
        val list = targets ?: installedImeTargets(executor)
        val all = probes ?: RimePaths.scanAll(executor, list.map { it.packageName })

        val explicit = settings.targetImePackageNow()
        if (explicit != null) {
            val probe = all[explicit] ?: RimePaths.scanAll(executor, listOf(explicit))[explicit]
            val dir = probe?.bestDir ?: return null
            val env = RimeEnvironment(explicit, dir, configured = probe.configuredDir != null)
            adoptLegacyRecords(executor, env)
            return env
        }

        val configured = list.mapNotNull { target ->
            val probe = all[target.packageName] ?: return@mapNotNull null
            if (probe.configuredDir != null) {
                RimeEnvironment(target.packageName, probe.bestDir!!, configured = true)
            } else {
                null
            }
        }
        if (configured.size == 1) {
            val env = configured.first()
            settings.setTargetImePackage(env.packageName)
            adoptLegacyRecords(executor, env)
            return env
        }
        return null
    }

    /**
     * 老版本的记录没有「归属输入法」字段（imePackage 为空）。
     * 这里按「它的 schema 文件是否就在当前目录里」认领，认领成功才写入归属，
     * 避免把别的输入法的方案错记到当前目标名下。
     */
    private suspend fun adoptLegacyRecords(executor: PrivilegedExecutor, env: RimeEnvironment) {
        val legacy = runCatching { dao.getAll().filter { it.imePackage.isEmpty() } }.getOrDefault(emptyList())
        for (entity in legacy) {
            if (!deployer.schemeFilesPresent(executor, env.rimeDir, entity.schemaId)) continue
            dao.update(entity.copy(imePackage = env.packageName))
        }
    }

    /** 导入若干设备上的文件（高权限路径，供内置文件浏览器使用）。 */
    suspend fun importFiles(
        executor: PrivilegedExecutor,
        paths: List<String>,
    ): ImportResult = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext ImportResult(false, "没有选择任何文件")
        val named = mutableListOf<Pair<String, ByteArray>>()
        for (path in paths) {
            val name = path.substringAfterLast('/')
            val bytes = deployer.readRemoteBytes(executor, path)
                ?: return@withContext ImportResult(false, "读取失败（文件为空、过大或没有权限）：$name")
            named += name to bytes
        }
        importBytes(executor, named)
    }

    /** 导入系统文件选择器返回的本地副本（已复制到应用缓存）。 */
    suspend fun importLocalFiles(
        executor: PrivilegedExecutor,
        files: List<File>,
    ): ImportResult = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext ImportResult(false, "没有选择任何文件")
        val named = files.map { it.name to it.readBytes() }
        importBytes(executor, named)
    }

    private suspend fun importBytes(
        executor: PrivilegedExecutor,
        named: List<Pair<String, ByteArray>>,
    ): ImportResult = withContext(Dispatchers.IO) {
        val env = currentEnvironment(executor)
            ?: return@withContext ImportResult(false, "未找到目标输入法的 Rime 数据目录，请确认该输入法已安装并启动过一次")

        val grouped = linkedMapOf<String, ImportItem>()

        for ((name, bytes) in named) {
            if (name.endsWith(".zip", ignoreCase = true)) {
                val pkgName = name.removeSuffix(".zip").removeSuffix(".ZIP").ifBlank { "package" }
                val target = File(context.filesDir, "schemas/$pkgName")
                target.deleteRecursively()
                target.mkdirs()
                val extracted = unzip(bytes, target)
                if (extracted.isEmpty()) {
                    return@withContext ImportResult(false, "压缩包内没有 .yaml 文件：$name")
                }
                // 一个 zip 里可能打包多个方案（每个方案一个 *.schema.yaml），逐个登记，
                // 否则包内其它方案永远不会出现在 Rime 的方案选单里。
                val schemaFiles = extracted.filter { it.endsWith(".schema.yaml") }
                if (schemaFiles.isEmpty()) {
                    return@withContext ImportResult(false, "压缩包内没有 .schema.yaml，无法识别方案：$name")
                }
                var recognized = 0
                for (schemaFile in schemaFiles) {
                    val text = File(target, schemaFile).readText()
                    val schemaId = parseSchemaId(text)
                        ?: parseSchemaIdFromFileName(schemaFile.substringAfterLast('/'))
                        ?: continue
                    val displayName = parseDisplayName(text) ?: schemaId
                    // 包内所有 yaml 都装进 Rime 目录：方案之间共享词库，按方案拆开会导致缺文件
                    grouped[schemaId] = ImportItem(target, extracted, displayName, pkgName)
                    recognized++
                }
                if (recognized == 0) {
                    return@withContext ImportResult(false, "压缩包里没有可识别的方案：$name")
                }
                continue
            }

            if (!name.endsWith(".yaml") && !name.endsWith(".yml")) {
                return@withContext ImportResult(false, "只支持 .yaml / .yml / .zip 文件：$name")
            }
            val text = String(bytes, Charsets.UTF_8)
            val schemaId = parseSchemaId(text)
                ?: parseSchemaIdFromFileName(name)
                ?: return@withContext ImportResult(false, "无法从 $name 解析出 schema id，请确认它是 Rime 方案文件")
            val displayName = parseDisplayName(text) ?: schemaId

            val dir = File(context.filesDir, "schemas/$schemaId").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)

            val merged = grouped[schemaId]
            if (merged == null) {
                grouped[schemaId] = ImportItem(dir, listOf(name), displayName)
            } else {
                grouped[schemaId] = ImportItem(
                    merged.localDir,
                    (merged.fileNames + name).distinct(),
                    merged.displayName,
                )
            }
        }

        var installed = 0
        val failures = mutableListOf<String>()

        for ((schemaId, item) in grouped) {
            val existing = dao.findBySchemaIdForIme(schemaId, env.packageName)
            // 同一方案再次导入时，先把旧文件搬进新目录，保证历史文件不丢
            if (existing != null) {
                val oldDir = File(existing.localDir)
                if (oldDir.absolutePath != item.localDir.absolutePath && oldDir.isDirectory) {
                    oldDir.walkTopDown().filter { it.isFile }.forEach { old ->
                        val rel = old.relativeTo(oldDir).path
                        val dst = File(item.localDir, rel)
                        dst.parentFile?.mkdirs()
                        if (!dst.exists()) runCatching { old.copyTo(dst) }
                    }
                }
            }
            val names = (existing?.fileNames?.split('|')?.filter { it.isNotBlank() } ?: emptyList())
                .toMutableSet()
            names.addAll(item.fileNames)

            val entity = SchemaEntity(
                id = existing?.id ?: 0,
                schemaId = schemaId,
                displayName = existing?.displayName ?: item.displayName,
                fileNames = names.joinToString("|"),
                localDir = item.localDir.absolutePath,
                importedAt = System.currentTimeMillis(),
                enabled = true,
                packageName = item.packageName,
                imePackage = env.packageName,
            )
            dao.insert(entity)

            val failed = deployer.installFiles(executor, env, item.localDir, names.toList())
            if (failed.isEmpty()) installed++ else failures += failed
        }

        val active = activeSchemaId()
        if (active == null) {
            settings.setActiveSchemaId(grouped.keys.first())
        }
        deployer.deploy(executor, env, dao.getAllForIme(env.packageName), activeSchemaId())
        val nativeDeployed = tryNativeDeploy(env, activeSchemaId())

        val msg = buildString {
            append("已导入 ${grouped.size} 个方案")
            if (installed > 0) append("，成功安装 $installed 个")
            if (failures.isNotEmpty()) append("；以下文件写入失败：${failures.joinToString()}")
            append(
                if (nativeDeployed) "。已在应用内完成 Rime 部署。"
                else "。请重启目标输入法完成部署。"
            )
        }
        ImportResult(failures.isEmpty(), msg)
    }

    /**
     * 解压 zip：按原样完整解出（保留子目录，例如 lua/、cn_dicts/、opencc/），返回相对路径列表。
     * 只排除与 Rime 无关的说明文件（README.md 等）和系统垃圾文件，
     * 其它一律保留——方案里的 lua 脚本、词库、opencc 词表缺一不可。
     */
    private fun unzip(bytes: ByteArray, target: File): List<String> {
        val result = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || name.contains("..")) continue
                val baseName = name.substringAfterLast('/')
                if (baseName.startsWith(".") || baseName.equals("README.md", true) ||
                    baseName.equals("readme.txt", true)
                ) continue
                val out = File(target, name)
                out.parentFile?.mkdirs()
                out.outputStream().use { output -> zip.copyTo(output) }
                result += name
            }
        }
        return result
    }

    suspend fun setEnabled(
        executor: PrivilegedExecutor,
        schemaId: String,
        enabled: Boolean,
    ): ImportResult = withContext(Dispatchers.IO) {
        val env = currentEnvironment(executor) ?: return@withContext ImportResult(false, "未找到 Rime 数据目录")
        val entity = dao.findBySchemaIdForIme(schemaId, env.packageName)
            ?: return@withContext ImportResult(false, "方案不存在")

        // zip 包里的方案共享同一批文件：整包一起启用/停用，避免把兄弟方案的词库删掉
        val targets = entity.packageName.takeIf { it.isNotBlank() }
            ?.let { dao.findByPackageForIme(it, env.packageName) }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(entity)

        val names = targets
            .flatMap { it.fileNames.split('|') }
            .filter { it.isNotBlank() }
            .distinct()

        targets.forEach { dao.update(it.copy(enabled = enabled)) }

        if (enabled) {
            // 本地副本统一在同一目录，随便取一个即可。
            // 「设备上已有方案」没有私有副本（localDir 为空），文件本来就在位，跳过安装。
            val localDir = targets.first().localDir
            if (localDir.isNotBlank()) {
                val failed = deployer.installFiles(executor, env, File(localDir), names)
                if (failed.isNotEmpty()) return@withContext ImportResult(false, "部分文件写入失败：${failed.joinToString()}")
            }
        } else if (targets.any { it.localDir.isNotBlank() }) {
            // 只删「本应用装进去的那份」。设备上已有的方案（localDir 为空）文件属于输入法自己，
            // 停用只是把它从 schema_list 里摘掉，绝不能删源文件——删了就再也装不回来。
            deployer.removeFiles(executor, env, names)
        }
        deployer.deploy(executor, env, dao.getAllForIme(env.packageName), activeSchemaId())
        // 停用时同样要清掉编译产物里的条目，否则选单里还会留着已停用的方案
        if (!enabled) deployer.invalidateCompiled(executor, env, targets.map { it.schemaId })

        val what = if (targets.size > 1) "方案包 ${entity.packageName}" else entity.displayName
        ImportResult(true, if (enabled) "已启用 $what" else "已停用 $what")
    }

    suspend fun activate(
        executor: PrivilegedExecutor,
        schemaId: String,
    ): ImportResult = withContext(Dispatchers.IO) {
        val env = currentEnvironment(executor) ?: return@withContext ImportResult(false, "未找到 Rime 数据目录")
        settings.setActiveSchemaId(schemaId)
        deployer.deploy(executor, env, dao.getAllForIme(env.packageName), schemaId)
        val nativeDeployed = tryNativeDeploy(env, schemaId)
        ImportResult(
            true,
            if (nativeDeployed) "已切换激活方案为 $schemaId，并完成部署"
            else "已切换激活方案为 $schemaId，请重启目标输入法完成部署",
        )
    }

    /**
     * 如果 APK 内带了 librime JNI（可选），直接在应用内完成部署，省去手动重启输入法。
     * 绝大多数情况下这里返回 false，属于正常降级路径。
     */
    private fun tryNativeDeploy(env: RimeEnvironment, activeSchemaId: String?): Boolean {
        if (!RimeNative.load(context)) return false
        if (!RimeNative.initialize(env.rimeDir, env.rimeDir)) return false
        val deployed = RimeNative.deploy()
        if (deployed && !activeSchemaId.isNullOrBlank()) {
            RimeNative.selectSchema(activeSchemaId)
        }
        return deployed
    }

    suspend fun delete(
        executor: PrivilegedExecutor,
        schemaId: String,
    ): ImportResult = withContext(Dispatchers.IO) {
        val env = currentEnvironment(executor)
        val entity = dao.findBySchemaIdForIme(schemaId, env?.packageName ?: "")
            ?: return@withContext ImportResult(false, "方案不存在")

        // zip 导入的方案共享同一批词库文件：只删一个方案会把其它方案的词库一起删掉，
        // 所以这里按包整体删除（单文件导入时 packageName 为空，只会影响自身）。
        val targets = entity.packageName.takeIf { it.isNotBlank() }
            ?.let { dao.findByPackageForIme(it, env?.packageName ?: "") }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(entity)

        val names = targets
            .flatMap { it.fileNames.split('|') }
            .filter { it.isNotBlank() }
            .distinct()

        if (env != null) deployer.removeFiles(executor, env, names)
        // localDir 为空的是「设备上已有方案」，没有私有副本可删，别把空路径当目录递归删掉
        targets.forEach { if (it.localDir.isNotBlank()) File(it.localDir).deleteRecursively() }
        targets.forEach { dao.deleteBySchemaId(it.schemaId) }

        val removedIds = targets.map { it.schemaId }
        val imePkg = env?.packageName ?: entity.imePackage
        if (activeSchemaId() in removedIds) {
            settings.setActiveSchemaId(dao.getAllForIme(imePkg).firstOrNull()?.schemaId)
        }
        if (env != null) {
            deployer.deploy(executor, env, dao.getAllForIme(imePkg), activeSchemaId())
            // 只改 default.custom.yaml 时 Rime 未必立刻重新编译，选单里就会残留幽灵条目；
            // 这里顺手把这些方案的编译产物和编译后的列表一起丢掉，下次打开键盘即刷新。
            deployer.invalidateCompiled(executor, env, removedIds)
        }

        val message = if (targets.size > 1) {
            "已删除方案包 ${entity.packageName}（含 ${targets.size} 个方案：${targets.joinToString { it.displayName }}）"
        } else {
            "已删除方案 ${entity.displayName}"
        }
        ImportResult(true, message)
    }

    suspend fun restoreDefaultList(executor: PrivilegedExecutor): ImportResult = withContext(Dispatchers.IO) {
        val env = currentEnvironment(executor) ?: return@withContext ImportResult(false, "未找到 Rime 数据目录")
        deployer.restoreDefaultCustom(executor, env)
        ImportResult(true, "已删除应用生成的 default.custom.yaml，重启目标输入法后恢复原始方案列表")
    }

    /**
     * 重启输入法并恢复默认输入法：
     * 先记下「重启前系统的默认输入法」，重启目标进程后再把它设回来——
     * 被杀掉的正是用户正在用的那个输入法，重启后系统可能回退到别的输入法，
     * 这里恢复成原来的那个，不写死任何具体包名。
     */
    suspend fun restartIme(executor: PrivilegedExecutor): ImportResult = withContext(Dispatchers.IO) {
        val env = currentEnvironment(executor) ?: return@withContext ImportResult(false, "未找到 Rime 数据目录")
        // 必须在 force-stop 之前读取，重启后系统状态可能已被改写
        val previousIme = deployer.currentDefaultIme(executor)
        if (previousIme != null) settings.setLastImeId(previousIme)
        val ok = deployer.restartIme(executor, env.packageName)

        // 优先恢复重启前的默认输入法；读不到时退回目标包对应的输入法
        val target = previousIme ?: deployer.resolveImeId(executor, env.packageName)
        val imeRestored = target != null && deployer.setIme(executor, target)

        val imeNote = when {
            !imeRestored -> "；未能自动恢复默认输入法，请在系统设置里手动选择"
            previousIme != null -> "；默认输入法已恢复为 $previousIme"
            else -> "；默认输入法已设为 $target"
        }
        ImportResult(
            ok,
            if (ok) "已重启目标输入法：旧进程已结束并重新拉起$imeNote"
            else "重启失败，请在最近任务里手动划掉目标输入法后重新打开$imeNote",
        )
    }

    /**
     * 重新设定默认输入法（不重启任何进程）：
     * 依次尝试「上次重启前记录的那个输入法」→「当前系统默认输入法」→「目标包对应的输入法」，
     * 全程不写死具体包名，谁被换掉就把谁设回来。
     */
    suspend fun restoreIme(executor: PrivilegedExecutor): ImportResult = withContext(Dispatchers.IO) {
        val env = currentEnvironment(executor)
        val remembered = settings.lastImeIdNow()
        val current = deployer.currentDefaultIme(executor)
        val fallback = env?.let { deployer.resolveImeId(executor, it.packageName) }
        val target = remembered ?: current ?: fallback
            ?: return@withContext ImportResult(false, "没有可恢复的输入法记录，请先在系统设置里选择一次输入法")

        val ok = deployer.setIme(executor, target)
        ImportResult(
            ok,
            if (ok) "已把默认输入法设为 $target"
            else "设置失败：系统拒绝切换到 $target，请检查该输入法是否仍已安装并启用",
        )
    }

    /**
     * 清理「文件已经被删掉、但还留在列表里」的方案。
     *
     * 场景：用户手工删掉 Rime 目录里的方案 yaml，应用侧数据库仍记着它，
     * 于是每次部署都会把它写进 schema_list，输入法选单里就出现删不掉的幽灵条目。
     * 这里以「Rime 目录里是否还有 <schemaId>.schema.yaml」为准做一次对齐。
     */
    suspend fun pruneMissing(executor: PrivilegedExecutor): ImportResult = withContext(Dispatchers.IO) {
        val env = currentEnvironment(executor) ?: return@withContext ImportResult(false, "未找到 Rime 数据目录")
        val removed = pruneInternal(executor, env)
        if (removed.isEmpty()) return@withContext ImportResult(true, "没有发现失效方案")
        ImportResult(
            true,
            "已清理失效方案：${removed.joinToString()}；已清空编译缓存，重启输入法后生效",
        )
    }

    /** 剔除「清单里有、但 Rime 目录里已经没有」的记录；不负责部署。返回被剔除的 schemaId。 */
    private suspend fun pruneInternal(executor: PrivilegedExecutor, env: RimeEnvironment): List<String> {
        val removed = mutableListOf<String>()
        for (entity in dao.getAllForIme(env.packageName)) {
            val present = deployer.schemeFilesPresent(executor, env.rimeDir, entity.schemaId)
            if (!present) {
                if (entity.localDir.isNotBlank()) File(entity.localDir).deleteRecursively()
                dao.deleteBySchemaId(entity.schemaId)
                removed += entity.schemaId
            }
        }
        if (removed.isEmpty()) return removed
        if (activeSchemaId() in removed) {
            settings.setActiveSchemaId(dao.getAllForIme(env.packageName).firstOrNull()?.schemaId)
        }
        return removed
    }

    /**
     * 收录「设备上已有、但应用没有记录」的方案。
     *
     * 典型场景：老版本升级数据库时丢过清单，文件还躺在 Rime 目录里，
     * 应用里看不到也就删不掉，键盘选单里却一直在（用户眼里的「幽灵方案」）。
     * 这里扫一遍目录，把没登记过的 *.schema.yaml 收编成记录，归到「设备上已有的方案」组，
     * 之后就能正常启停/删除；删除时会连带清掉编译产物，条目立刻消失。
     *
     * 只记录 schema 文件本身，不猜它依赖哪些词库（可能和别的方案共享），
     * 避免一次删除把兄弟方案的词库也删了。
     */
    suspend fun scanInstalled(executor: PrivilegedExecutor): ImportResult = withContext(Dispatchers.IO) {
        val env = currentEnvironment(executor) ?: return@withContext ImportResult(false, "未找到 Rime 数据目录")
        val known = dao.getAllForIme(env.packageName).map { it.schemaId }.toSet()
        val all = executor.listDir(env.rimeDir)
        val ids = all
            .asSequence()
            .filter { !it.isDirectory }
            .map { it.name }
            .filter { it.endsWith(".schema.yaml", ignoreCase = true) }
            .map { it.dropLast(".schema.yaml".length) }
            .filter { it.isNotBlank() && it !in known }
            .toList()
        android.util.Log.d(
            "PtFastRime",
            "scanInstalled: entries=${all.size}, known=${known.size}, new=${ids.size}, first=${all.take(5).map { it.name + "/" + it.isDirectory }}",
        )

        if (ids.isEmpty()) return@withContext ImportResult(true, "没有发现未登记的方案")

        val adopted = mutableListOf<String>()
        for (id in ids) {
            val text = executor.readText("${env.rimeDir}/$id.schema.yaml")
            val name = text?.let { parseDisplayName(it) } ?: id
            dao.insert(
                SchemaEntity(
                    schemaId = id,
                    displayName = name,
                    fileNames = "$id.schema.yaml",
                    // 文件已经在 Rime 目录里，没有私有副本，localDir 留空
                    localDir = "",
                    importedAt = System.currentTimeMillis(),
                    enabled = true,
                    packageName = DEVICE_GROUP,
                    imePackage = env.packageName,
                ),
            )
            adopted += name
        }
        ImportResult(true, "已收录 ${adopted.size} 个设备上已有的方案：${adopted.joinToString()}")
    }

    /**
     * 启动时的一次静默对齐（不弹窗、不重启输入法）：
     * 收编目录里新出现的方案 → 剔除清单里已经消失的方案 → 按最终清单重写配置。
     * 如果确实剔除了记录，顺手丢掉编译产物，避免键盘选单里留下已经删掉的条目。
     */
    suspend fun syncOnLaunch(executor: PrivilegedExecutor): ImportResult = withContext(Dispatchers.IO) {
        val env = currentEnvironment(executor) ?: return@withContext ImportResult(false, "未找到 Rime 数据目录")
        val adopted = scanInstalled(executor)
        val removed = pruneInternal(executor, env)
        val all = dao.getAllForIme(env.packageName)
        val changed = deployer.deploy(executor, env, all, activeSchemaId())
        // 只要写出来的方案列表和之前不一样，就丢掉编译产物里的列表快照，
        // 让键盘下次启动按新配置重建，避免选单比配置慢一拍。
        if (changed || removed.isNotEmpty()) deployer.invalidateCompiled(executor, env, removed)
        ImportResult(
            true,
            buildString {
                if (adopted.message.startsWith("已收录")) append(adopted.message).append("；")
                if (removed.isNotEmpty()) append("已剔除失效方案：${removed.joinToString()}；")
                append("已按 ${all.size} 个方案重写配置")
            },
        )
    }

    /**
     * 强制重新部署：清空 build 缓存并重启输入法。
     *
     * 这是界面上「刷新」按钮的实现，也是「每次有变动都重写一遍」的兜底：
     * 先扫目录把新出现的方案收编、把已经消失的方案剔除，再按清单重写
     * default.custom.yaml / user.yaml，最后清掉编译缓存并重启输入法，
     * 保证键盘选单与配置严格一致。
     */
    suspend fun forceRedeploy(executor: PrivilegedExecutor): ImportResult = withContext(Dispatchers.IO) {
        val env = currentEnvironment(executor) ?: return@withContext ImportResult(false, "未找到 Rime 数据目录")
        val adopted = scanInstalled(executor)
        val removed = pruneInternal(executor, env)
        val all = dao.getAllForIme(env.packageName)
        deployer.deploy(executor, env, all, activeSchemaId())
        val cleared = deployer.forceRedeploy(executor, env)
        val previousIme = deployer.currentDefaultIme(executor)
        if (previousIme != null) settings.setLastImeId(previousIme)
        val ok = deployer.restartIme(executor, env.packageName)
        val target = previousIme ?: deployer.resolveImeId(executor, env.packageName)
        target?.let { deployer.setIme(executor, it) }
        ImportResult(
            ok,
            buildString {
                if (adopted.message.startsWith("已收录")) append(adopted.message).append("；")
                if (removed.isNotEmpty()) append("已剔除失效方案：${removed.joinToString()}；")
                append("共 ${all.size} 个方案，")
                append(if (cleared) "已清空编译缓存，" else "未能清空编译缓存，")
                append(if (ok) "并已重启输入法" else "请手动重启输入法")
                append("；下次打开键盘会重新编译，选单与配置文件一致")
            },
        )
    }

    /**
     * 解析方案 id，兼容 librime 的两种写法：
     * 1. 旧式顶层单行：`schema: rime_ice`；
     * 2. 1.16 起的嵌套写法：
     *    schema:
     *      schema_id: hokchew_roma
     *      name: 榕拼輸入法：教罗
     */
    private fun parseSchemaId(text: String): String? {
        Regex("(?m)^schema:\\s*([A-Za-z0-9_.\\-]+)\\s*$").find(text)
            ?.groupValues?.getOrNull(1)
            ?.let { return it }
        return Regex("(?m)^\\s*schema_id:\\s*([A-Za-z0-9_.\\-]+)\\s*$")
            .find(text)
            ?.groupValues?.getOrNull(1)
    }

    private fun parseSchemaIdFromFileName(name: String): String? = when {
        name.endsWith(".schema.yaml") -> name.removeSuffix(".schema.yaml")
        name.endsWith(".dict.yaml") -> name.removeSuffix(".dict.yaml")
        else -> null
    }

    /** 展示名：顶层 `name:` 或嵌套在 `schema:` 下的 `name:`，都允许行尾注释。 */
    private fun parseDisplayName(text: String): String? {
        val match = Regex("(?m)^\\s*name:\\s*(.+?)\\s*$").find(text) ?: return null
        return match.groupValues[1]
            .substringBefore(" #")
            .trim()
            .trim('"', '\'')
            .takeIf { it.isNotBlank() }
    }
}
