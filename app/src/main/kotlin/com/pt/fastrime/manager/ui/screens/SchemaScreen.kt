package com.pt.fastrime.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pt.fastrime.manager.data.SchemaEntity
import com.pt.fastrime.manager.privilege.PrivilegeState
import com.pt.fastrime.manager.rime.ImeTarget
import com.pt.fastrime.manager.rime.RimeEnvironment
import com.pt.fastrime.manager.rime.SchemaRepository
import com.pt.fastrime.manager.ui.UiState

/**
 * 本地方案页：唯一负责“导入 / 启用 / 激活 / 删除”的界面。
 * 权限不可用时这里只展示阻断说明，不渲染任何可执行按钮。
 *
 * 方案按「方案包」分组展示：一个 zip 里打包的多个方案折叠在同一张卡片里，
 * 单个 yaml 导入的方案归到「单独导入的方案」组，避免十几个方案把列表撑成一大片。
 */
@Composable
fun SchemaScreen(
    state: UiState,
    onImport: () -> Unit,
    onActivate: (String) -> Unit,
    onToggle: (SchemaEntity, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onRestart: () -> Unit,
    onRestoreIme: () -> Unit,
    onRestoreDefault: () -> Unit,
    onSelectIme: (String) -> Unit,
) {
    val privilege = state.privilege
    val blocked = privilege !is PrivilegeState.Root && privilege !is PrivilegeState.Shizuku

    // 分组：包名非空的是方案包，包名为空的是单独导入的散装方案（排在最后）
    val groups = remember(state.schemas) { buildGroups(state.schemas) }
    // 展开状态：默认展开第一个包（通常是刚导入的那个），其余收起。
    // 放在 LazyColumn 外面是因为 remember 只能在 composable 上下文里调用。
    val expanded = remember(groups.map { it.name }) {
        mutableStateMapOf<String, Boolean>().apply {
            groups.firstOrNull()?.let { put(it.name, true) }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (blocked) {
            item { BlockedCard() }
            return@LazyColumn
        }

        item { EnvironmentCard(state.rimeEnv, state.imeTargets, state.imeRimeDirs, state.selectedIme, onSelectIme) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导入本地方案")
                }
                OutlinedButton(onClick = onRestart) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                OutlinedButton(onClick = onRestoreIme) {
                    // 键盘图标：把默认输入法设回上一次使用（被重启）的那个
                    Icon(Icons.Filled.Keyboard, contentDescription = "重新设定输入法", modifier = Modifier.size(18.dp))
                }
            }
        }

        if (state.schemas.isEmpty()) {
            item { EmptyState(onImport) }
        } else {
            val packageCount = groups.count { it.isPackage }
            item {
                Text(
                    text = buildString {
                        if (packageCount > 0) append("已导入 $packageCount 个方案包、")
                        append("${state.schemas.size} 个方案（激活方案排在首位）")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 展开状态在 LazyColumn 外维护（见上方 expanded）
            groups.forEach { group ->
                item(key = "group:${group.name}") {
                    SchemaGroupCard(
                        group = group,
                        expanded = expanded[group.name] ?: false,
                        activeSchemaId = state.activeSchemaId,
                        onToggleExpand = { expanded[group.name] = !(expanded[group.name] ?: false) },
                        onActivate = onActivate,
                        onToggle = onToggle,
                        onDelete = onDelete,
                    )
                }
            }
            item {
                TextButton(onClick = onRestoreDefault) {
                    Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("删除本应用生成的方案列表，恢复原有配置")
                }
            }
        }
    }
}

/** 一个方案包（或散装方案的集合）。 */
private data class SchemaGroup(
    val name: String,
    val displayName: String,
    val isPackage: Boolean,
    val schemas: List<SchemaEntity>,
)

private fun buildGroups(schemas: List<SchemaEntity>): List<SchemaGroup> {
    val named = schemas
        .filter { it.packageName.isNotBlank() }
        .groupBy { it.packageName }
        .toSortedMap()
        .map { (pkg, list) ->
            // 「设备上已有方案」是个虚拟包名，展示成中文标题更好懂
            val title = if (pkg == SchemaRepository.DEVICE_GROUP) "Rime 自带的散装方案" else pkg
            SchemaGroup(pkg, title, true, list.sortedBy { it.displayName })
        }
    val singles = schemas.filter { it.packageName.isBlank() }
    val singlesGroup = if (singles.isEmpty()) {
        emptyList()
    } else {
        listOf(SchemaGroup("", "单独导入的方案", false, singles.sortedBy { it.displayName }))
    }
    return named + singlesGroup
}

@Composable
private fun SchemaGroupCard(
    group: SchemaGroup,
    expanded: Boolean,
    activeSchemaId: String?,
    onToggleExpand: () -> Unit,
    onActivate: (String) -> Unit,
    onToggle: (SchemaEntity, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val activeHere = group.schemas.firstOrNull { it.schemaId == activeSchemaId }
    val enabledCount = group.schemas.count { it.enabled }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column {
            // —— 组头：点整行展开/收起；包名、方案数、当前激活方案 ——
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (group.isPackage) Icons.Filled.Inventory2 else Icons.Filled.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append("${group.schemas.size} 个方案")
                            if (enabledCount != group.schemas.size) append("，已启用 $enabledCount 个")
                            activeHere?.let { append("，当前：${it.displayName}") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (group.name == SchemaRepository.DEVICE_GROUP) {
                        Text(
                            "散装方案（未打包），直接来自 Rime 数据目录，可单独启停或删除",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
                if (group.isPackage && group.name != SchemaRepository.DEVICE_GROUP) {
                    // 整包删除：包内方案共享词库，拆开删会连累其它方案。
                    // 「散装方案」例外——它们互相独立，逐条删除（见下面的 SchemaRow）。
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "删除方案包")
                    }
                }
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                    )
                }
            }

            if (expanded) {
                HorizontalDivider()
                group.schemas.forEachIndexed { index, schema ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SchemaRow(
                        schema = schema,
                        active = schema.schemaId == activeSchemaId,
                        showPackageTag = false,
                        onActivate = { onActivate(schema.schemaId) },
                        onToggle = { onToggle(schema, it) },
                        // 方案包内共用词库，删除统一在组头；散装方案彼此独立，允许逐条删
                        onDelete = if (group.isPackage && group.name != SchemaRepository.DEVICE_GROUP) {
                            null
                        } else {
                            ({ onDelete(schema.schemaId) })
                        },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除方案包 ${group.displayName}？") },
            text = {
                Text(
                    "包内含 ${group.schemas.size} 个方案：${group.schemas.joinToString { it.displayName }}。" +
                        "包内方案共享词库文件，会整体删除；应用私有副本和已装到目标输入法目录的文件一起清理，操作不可撤销。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    group.schemas.firstOrNull()?.let { onDelete(it.schemaId) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SchemaRow(
    schema: SchemaEntity,
    active: Boolean,
    showPackageTag: Boolean,
    onActivate: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = active, onClick = onActivate)
            Column(Modifier.weight(1f)) {
                Text(
                    schema.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text("schema: ${schema.schemaId}", style = MaterialTheme.typography.labelSmall)
                Text(
                    // 文件多时逐条罗列会把卡片撑得很难看，这里只给数量
                    "共 ${schema.fileNames.split('|').count { it.isNotBlank() }} 个文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showPackageTag && schema.packageName.isNotBlank()) {
                    Text(
                        "方案包：${schema.packageName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = schema.enabled, onCheckedChange = onToggle)
            if (onDelete != null) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "删除方案")
                }
            }
        }
        if (active) {
            AssistChip(onClick = onActivate, label = { Text("当前激活") })
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${schema.displayName}？") },
            text = { Text("会同时删除应用私有副本和已安装到目标输入法目录中的文件，操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun BlockedCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("核心配置已锁定", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "目标输入法的配置目录受系统保护，需要借用 Root 或 Shizuku 才能读写。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("请在 Root 管理器中授予本应用 Root，或激活 Shizuku 后回到本页重试。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnvironmentCard(
    env: RimeEnvironment?,
    imeTargets: List<ImeTarget>,
    imeRimeDirs: Map<String, String>,
    selectedIme: String?,
    onSelectIme: (String) -> Unit,
) {
    val exists = env != null
    var dropdownOpen by remember { mutableStateOf(false) }
    val current = selectedIme ?: env?.packageName
    val currentLabel = imeTargets.firstOrNull { it.packageName == current }?.label
    // 只列「探到 Rime 数据目录」的输入法：搜狗、Gboard、KDE 这类没有 Rime 的没必要占位。
    // 例外是当前已选中的那个——即使探不到也留着，否则下拉框会显示成空白。
    val options = imeTargets.filter {
        imeRimeDirs.containsKey(it.packageName) || it.packageName == current
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (exists) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("目标输入法", style = MaterialTheme.typography.labelMedium)

            if (options.isEmpty()) {
                Text(
                    text = current ?: "未检测到已安装的输入法",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                // 设备上可能装了好几个输入法，由用户自己选哪个才是「目标输入法」
                ExposedDropdownMenuBox(expanded = dropdownOpen, onExpandedChange = { dropdownOpen = it }) {
                    OutlinedTextField(
                        value = currentLabel ?: current ?: "请选择输入法",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownOpen) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = dropdownOpen, onDismissRequest = { dropdownOpen = false }) {
                        options.forEach { target ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(target.label)
                                        val dir = imeRimeDirs[target.packageName]
                                        Text(
                                            if (dir != null) target.packageName
                                            else "${target.packageName}（未找到 Rime 目录）",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (dir != null) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    dropdownOpen = false
                                    onSelectIme(target.packageName)
                                },
                            )
                        }
                    }
                }
            }

            if (env != null) {
                // /proc/1/root 只是我们访问私有目录的入口，给用户看的时候还原成真实路径
                Text(
                    "Rime 目录：${env.rimeDir.removePrefix("/proc/1/root")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "没有在目标输入法下找到 Rime 数据目录。请先启动一次该输入法，或在上方换成别的输入法。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onImport: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("还没有导入任何方案", style = MaterialTheme.typography.titleMedium)
            Text(
                "请把 .schema.yaml / .dict.yaml 或打包好的 .zip 方案放到手机存储中，点下面的按钮通过文件选择器导入。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onImport) { Text("选择本地文件") }
        }
    }
}
