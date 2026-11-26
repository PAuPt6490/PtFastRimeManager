package com.pt.fastrime.manager

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pt.fastrime.manager.privilege.PrivilegeManager
import com.pt.fastrime.manager.ui.AppTab
import com.pt.fastrime.manager.ui.MainViewModel
import com.pt.fastrime.manager.ui.components.PermissionBanner
import com.pt.fastrime.manager.ui.screens.FileBrowserDialog
import com.pt.fastrime.manager.ui.screens.MarketScreen
import com.pt.fastrime.manager.ui.screens.SchemaScreen
import com.pt.fastrime.manager.ui.theme.PtFastRimeTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Shizuku 授权结果回调：仅在用户主动点击“授权 Shizuku”后才可能被触发。 */
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PrivilegeManager.SHIZUKU_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    viewModel.retryDetect()
                } else {
                    viewModel.showMessage("Shizuku 授权被拒绝")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 静默注册监听，不触发任何授权请求；Shizuku 未安装时这里会抛出并被安全忽略
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }

        setContent {
            PtFastRimeTheme {
                AppRoot(viewModel = viewModel, onRequestShizuku = ::requestShizukuPermission)
            }
        }
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
    }

    private fun requestShizukuPermission() {
        val pingOk = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!pingOk) {
            Toast.makeText(this, "Shizuku 服务未运行，请先启动 Shizuku", Toast.LENGTH_LONG).show()
            return
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) {
            Toast.makeText(this, "Shizuku 版本过低，请升级到 v11 及以上", Toast.LENGTH_LONG).show()
            return
        }
        runCatching { Shizuku.requestPermission(PrivilegeManager.SHIZUKU_REQUEST_CODE) }
            .onFailure { Toast.makeText(this, "无法请求 Shizuku 授权：${it.message}", Toast.LENGTH_LONG).show() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(viewModel: MainViewModel, onRequestShizuku: () -> Unit) {
    val state by viewModel.ui.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // 系统文件选择器：选取 .yaml / .dict.yaml / .zip，选中后复制到私有目录再安装
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.importUris(uris) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.tab == AppTab.SCHEMAS,
                    onClick = { viewModel.setTab(AppTab.SCHEMAS) },
                    icon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                    label = { Text("本地方案") },
                )
                NavigationBarItem(
                    selected = state.tab == AppTab.MARKET,
                    onClick = { viewModel.setTab(AppTab.MARKET) },
                    icon = { Icon(Icons.Filled.Storefront, contentDescription = null) },
                    label = { Text("方案市场") },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PermissionBanner(
                state = state.privilege,
                onRetry = { viewModel.retryDetect() },
                onRequestShizuku = onRequestShizuku,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f)) {
                when (state.tab) {
                    AppTab.SCHEMAS -> SchemaScreen(
                        state = state,
                        onImport = { picker.launch(arrayOf("*/*")) },
                        onActivate = { viewModel.activate(it) },
                        onToggle = { schema, enabled -> viewModel.setEnabled(schema.schemaId, enabled) },
                        onDelete = { viewModel.delete(it) },
                        onRestart = { viewModel.restartIme() },
                        onRestoreIme = { viewModel.restoreIme() },
                        onRestoreDefault = { viewModel.restoreDefaultList() },
                        onSelectIme = { viewModel.selectIme(it) },
                    )

                    AppTab.MARKET -> MarketScreen()
                }
            }
        }
    }

    if (state.browserOpen) {
        FileBrowserDialog(
            initialPath = state.lastBrowseDir,
            listDir = { viewModel.listDir(it) },
            onDismiss = { viewModel.closeBrowser() },
            onConfirm = { paths -> viewModel.importFiles(paths) },
        )
    }
}
