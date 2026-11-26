package com.pt.fastrime.manager.rime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethod
import android.view.inputmethod.InputMethodManager

/**
 * 设备上已安装的一个输入法（一个包可能有多个输入法服务，这里按包去重）。
 *
 * @param packageName 输入法包名，例如 org.fcitx.fcitx5.android
 * @param serviceName 输入法服务类名（`ime set` 需要的完整 id 是 `包名/服务类`）
 * @param label       展示给用户的名称，取不到时退回包名
 */
data class ImeTarget(
    val packageName: String,
    val serviceName: String,
    val label: String,
) {
    val id: String get() = "$packageName/$serviceName"
}

/**
 * 枚举设备上所有可用的输入法。
 *
 * 需求是不把任何具体输入法写死：用户装了几个输入法（小企鹅、同文、Trime、系统输入法…），
 * 就在这里全部列出来，由用户自己选哪个是「目标输入法」。
 *
 * 为什么用 PackageManager 扫而不是只问 InputMethodManager：
 * `getEnabledInputMethodList()` 只返回**已启用**的输入法，用户装了但没启用的（比如刚装的 Trime）
 * 就看不到；这里直接扫已安装应用里声明了 BIND_INPUT_METHOD 的服务，能拿到全部输入法。
 */
object ImeTargets {

    fun installed(context: Context): List<ImeTarget> {
        val pm = context.packageManager
        val fromPackages = runCatching {
            val intent = Intent(InputMethod.SERVICE_INTERFACE)
            @Suppress("DEPRECATION")
            pm.queryIntentServices(
                intent,
                PackageManager.GET_META_DATA or PackageManager.GET_DISABLED_COMPONENTS,
            ).mapNotNull { info ->
                val service = info.serviceInfo ?: return@mapNotNull null
                // 只有声明了输入法权限的服务才是真正的输入法
                if (service.permission != android.Manifest.permission.BIND_INPUT_METHOD) return@mapNotNull null
                val label = runCatching { service.loadLabel(pm).toString() }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: service.packageName
                ImeTarget(service.packageName, service.name, label)
            }
        }.getOrDefault(emptyList())

        // 再从 InputMethodManager 补一份（少数 ROM 里 QueryIntentServices 拿不到，或者标签更准）
        val fromImm = runCatching {
            val imm = context.getSystemService(InputMethodManager::class.java) ?: return emptyList()
            imm.inputMethodList.map { info ->
                val label = runCatching { info.loadLabel(pm).toString() }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: info.packageName
                ImeTarget(info.packageName, info.serviceName, label)
            }
        }.getOrDefault(emptyList())

        return (fromPackages + fromImm).distinctBy { it.packageName }
    }

    /** 系统当前默认输入法的包名，读不到返回 null。 */
    fun defaultImePackage(context: Context): String? = runCatching {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
        )?.substringBefore('/')
    }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
}
