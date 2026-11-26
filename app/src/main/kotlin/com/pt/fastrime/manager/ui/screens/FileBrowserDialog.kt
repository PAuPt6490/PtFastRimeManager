package com.pt.fastrime.manager.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pt.fastrime.manager.privilege.DirEntry

/**
 * 高权限文件浏览器。
 *
 * 为什么不用 SAF：小企鹅的配置目录在 /storage/emulated/0/Android/data/... 下，
 * Android 11+ 的 SAF 无法授权访问该路径，而且需求明确排除 SAF。
 * 因此这里用 Root/Shizuku 的 shell 列目录，再把用户选中的文件读回来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserDialog(
    initialPath: String,
    listDir: suspend (String) -> List<DirEntry>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var currentPath by remember { mutableStateOf(initialPath) }
    var entries by remember { mutableStateOf<List<DirEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(currentPath) {
        loading = true
        error = null
        val result = runCatching { listDir(currentPath) }
        entries = result.getOrDefault(emptyList())
        if (result.isFailure) error = "无法读取目录：${result.exceptionOrNull()?.message}"
        if (entries.isEmpty() && result.isSuccess) error = "目录为空或没有访问权限"
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("选择方案文件", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    currentPath,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭")
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = { onConfirm(selected.filterValues { it }.keys.toList()) },
                                enabled = selected.any { it.value },
                            ) { Text("导入(${selected.count { it.value }})") }
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            val parent = currentPath.substringBeforeLast('/', missingDelimiterValue = "/")
                            if (parent.isNotEmpty() && parent != currentPath) currentPath = parent
                        }) { Text("上一级") }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "支持 .yaml / .dict.yaml / .zip",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()

                    if (loading) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(entries, key = { it.name }) { entry ->
                                val fullPath = if (currentPath.endsWith("/")) currentPath + entry.name
                                else "$currentPath/${entry.name}"
                                val isYaml = entry.name.endsWith(".yaml") || entry.name.endsWith(".yml") || entry.name.endsWith(".zip", ignoreCase = true)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (entry.isDirectory) {
                                                currentPath = fullPath
                                            } else if (isYaml) {
                                                selected[fullPath] = !(selected[fullPath] ?: false)
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = if (entry.isDirectory) Icons.Filled.Folder
                                        else Icons.Filled.InsertDriveFile,
                                        contentDescription = null,
                                        tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        entry.name,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!entry.isDirectory && isYaml) {
                                        Checkbox(
                                            checked = selected[fullPath] == true,
                                            onCheckedChange = { selected[fullPath] = it },
                                        )
                                    }
                                }
                                HorizontalDivider(thickness = 0.5.dp)
                            }
                        }
                    }

                    error?.let {
                        Text(
                            it,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
