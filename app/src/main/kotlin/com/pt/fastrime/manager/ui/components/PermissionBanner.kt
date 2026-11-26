package com.pt.fastrime.manager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pt.fastrime.manager.privilege.PrivilegeState

/**
 * 权限横幅：主界面顶部的唯一权限状态入口。
 *
 * 需求要求“启动不弹窗、进入主界面后再告知当前模式”，这个组件就是告知与补救的出口：
 * - Root / Shizuku 可用：绿色/蓝色信息条，说明当前执行通道；
 * - 不可用：红色阻断条，提供“重试检测”和（装了 Shizuku 时）“去授权”两个动作。
 */
@Composable
fun PermissionBanner(
    state: PrivilegeState,
    onRetry: () -> Unit,
    onRequestShizuku: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        PrivilegeState.Checking -> CheckingBanner(modifier)

        is PrivilegeState.Root -> InfoBanner(
            modifier = modifier,
            background = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            title = "Root 模式已就绪",
            detail = "所有配置操作直接以 root 身份执行，无需额外授权",
        )

        is PrivilegeState.Shizuku -> InfoBanner(
            modifier = modifier,
            background = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            title = "Shizuku 模式已就绪",
            detail = "通过 Shizuku 服务执行高权限操作，效果等同 adb shell",
        )

        is PrivilegeState.Unavailable -> Column(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "高权限不可用，核心配置已锁定",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.size(4.dp))
            Text(
                text = state.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRetry) { Text("重试检测") }
                if (state.shizukuInstalled) {
                    TextButton(onClick = onRequestShizuku) { Text("授权 Shizuku") }
                }
            }
        }
    }
}

@Composable
private fun CheckingBanner(modifier: Modifier = Modifier) {
    InfoBanner(
        modifier = modifier,
        background = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = "正在静默检测权限…",
        detail = "优先尝试 Root，其次 Shizuku；不会弹出任何授权窗口",
    )
}

@Composable
private fun InfoBanner(
    modifier: Modifier,
    background: Color,
    contentColor: Color,
    title: String,
    detail: String,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (title.contains("已就绪")) Icons.Filled.CheckCircle else Icons.Filled.Security,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = contentColor)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = contentColor)
        }
    }
}
