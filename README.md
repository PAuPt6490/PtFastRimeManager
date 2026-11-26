<div align="center">

<img src="PtFastRimeManager.png" width="140" alt="PtFastRimeManager 图标">

# PtFastRimeManager

**面向 Android 的 Rime 输入法方案管理器：不联网、不走 SAF，借 Root / Shizuku 直接管理多个 Rime 前端的方案。**

主要面向小企鹅输入法 Rime插件和同文输入法Trime，理论上支持所有Rime输入法（依输入法实际行为而定）。

![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.10.01-4285F4?logo=jetpackcompose&logoColor=white)
![Root](https://img.shields.io/badge/Root-su%20%7C%20Magisk%20%7C%20KernelSU%20%7C%20APatch-orange)
![Shizuku](https://img.shields.io/badge/Shizuku-13.1.5-6C5CE7)
![Network](https://img.shields.io/badge/Network-none-critical)
![License](https://img.shields.io/badge/License-MIT-green)

[特性](#特性) · [支持的输入法](#支持的输入法) · [快速开始](#快速开始) · [工作原理](#工作原理) · [目录结构](#目录结构) · [常见问题](#常见问题) · [已知限制](#已知限制)

</div>

---

## 简介

Android 上的 Rime 前端都把方案放在各自的 **Rime 用户目录**里：`*.schema.yaml` 定义方案、`*.dict.yaml` 放词库、`default.custom.yaml` 里的 `patch: schema_list:` 决定键盘选单里出现哪些方案。

这些目录要么在 `/storage/emulated/0/Android/data/<包名>/…`，要么在应用私有的 `/data/data/<包名>/…`，普通应用无权读写。PtFastRimeManager 用 **Root 或 Shizuku** 拿到高权限，替你完成：

- 导入本地方案（`.schema.yaml` / `.dict.yaml` / `.zip` 方案包）
- 启用、停用、切换、删除方案
- 统一维护各输入法的 `default.custom.yaml`（覆盖前自动备份为 `*.ptfastbak`）
- 重启目标输入法，让改动立刻生效

全程**不联网**，没有遥测，也不申请存储权限。

## 特性

| 能力 | 说明 |
| --- | --- |
| **多输入法** | 用系统权威清单 `ime list -a` 枚举所有输入法，再按包名探测各自的 Rime 目录；目标输入法可随时切换，方案记录按输入法隔离 |
| **双权限通道** | 统一 `PrivilegedExecutor` 抽象，`RootExecutor`（`su`）优先，`ShizukuExecutor` 兜底；上层代码不感知具体实现 |
| **静默检测** | 启动即探测，不弹任何授权窗口；权限状态以横幅展示，检测失败时给出可操作提示（连续 3 次会提示去授权） |
| **方案导入** | 系统文件选择器选 `.schema.yaml` / `.dict.yaml` / `.zip`；zip 保留 `cn_dicts/` 等子目录结构，从 `schema:` 字段取 schema id |
| **方案管理** | 多方案共存、激活方案置顶、启停、按方案包整体删除、恢复输入法原始方案列表 |
| **收录已有方案** | 自动把目标输入法目录里已存在、但应用没登记过的 `*.schema.yaml` 收编成条目，避免出现「键盘里有、应用里没有」的幽灵方案 |
| **持久化** | Room 存方案清单（含归属输入法字段），DataStore 存激活方案 / 上次浏览目录 / 目标输入法 |
| **方案市场** | 底部导航占位页，仅一个 GitHub 链接，跳浏览器，不下载任何内容 |

## 支持的输入法

| 输入法 | 包名 | Rime 目录 | 状态 |
| --- | --- | --- | --- |
| 小企鹅（fcitx5-android + Rime 插件） | `org.fcitx.fcitx5.android` | `/storage/emulated/0/Android/data/<pkg>/files/data/rime` | ✅ 主力适配 |
| 同文（Trime） | `com.osfans.trime` | `…/files/rime` | ✅ 可切换；方案打包在 APK 内，目录里通常只有 `installation.yaml` |

判定规则：候选目录里存在 `*.schema.yaml` 或 `installation.yaml` 才认定为「真正的 Rime 数据目录」；探不到目录的输入法（搜狗、Gboard 等）不会出现在下拉框里。

## 快速开始

### 环境要求

- Android 8.0（API 26）及以上，arm64-v8a
- Root（Magisk / KernelSU / APatch 任一）**或** Shizuku（已激活并授权）
- 构建：JDK 17+、Android SDK（compileSdk 35）

### 构建

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.10"
.\gradlew.bat assembleDebug
```

- 首次构建需联网拉依赖：AGP 8.7.3、Kotlin 2.0.21、Compose BOM 2024.10.01、Room 2.6.1、Shizuku 13.1.5。
- Gradle 缓存不可写时，可把 `GRADLE_USER_HOME` 指到工程内目录，再用 `GRADLE_RO_DEP_CACHE` 复用已有缓存。
- **仓库不附带任何签名文件**（私钥不入库）。本地构建时若存在 `keystore/debug.keystore` 就用它，否则回退到 Android 默认调试签名（`~/.android/debug.keystore`）。想固定签名就在本地放一份，该路径已被 `.gitignore` 忽略。

### 安装

```bash
adb connect <手机IP>:<无线调试端口>
adb -s <手机IP>:<无线调试端口> install -r app/build/outputs/apk/debug/app-debug.apk
```

### 首次使用

1. 打开应用，顶部横幅显示「Root 模式已就绪」或「Shizuku 模式已就绪」。若显示不可用，点「重试检测」，并先在 Root 管理器 / Shizuku 里授权。
2. 在「目标输入法」下拉框里选要管理的输入法（只列出探到 Rime 目录的）。
3. 点「导入本地方案」选 `.zip` 或 `*.schema.yaml`；也可以直接点刷新，把该输入法已有方案收编进来。
4. 改动会写入 `default.custom.yaml`，并自动重启目标输入法使配置重新部署。

## 工作原理

### 权限通道

| 通道 | 实现 | 注意事项 |
| --- | --- | --- |
| Root | `su -c "( 命令 ) 2>&1; printf '\n__PT_EXIT__%s\n' \"$?\""` | 命令末尾打一行哨兵，用来拿真实退出码并确认输出写完（`su` 可能先返回、子 shell 还在写） |
| Shizuku | `Shizuku.getBinder()` → `IShizukuService.newProcess(sh -c …)` | 以 `shell`(uid 2000) 身份运行，读不到应用私有目录，因此待复制的文件会先转存到 `/sdcard/Android/data/com.pt.fastrime.manager/files/stage/` |

输出统一落临时文件再整块读回，不使用管道：`Process.waitFor(timeout)` 返回时会关闭管道，异步读只能拿到空串（表现为「命令成功但没有输出」）。

### 输入法探测

1. **列清单**：`ime list -a` 一次，得到系统认可的输入法（比 `PackageManager` 干净，不带自动填充、语音输入）。
2. **扫目录**：一条高权限命令里跑两轮遍历 —— 第一轮走所有输入法的**公有目录**（`/storage/emulated/0/Android/data/<pkg>/…`），第二轮走**私有目录**，输出 `OK|包名|目录` / `DIR|包名|目录` 一次拿全。所以无论装了多少输入法，一次启动只花 2 次高权限调用。

候选子路径：`files/data/rime`、`files/rime`、`files/data`、`files`、`rime`、`app_rime` × 三类根目录：外部私有目录、`/data/data/<pkg>`、`/proc/1/root/data/data/<pkg>`。

> **为什么需要 `/proc/1/root`**：Android 11+ 给每个应用一套 mount namespace，`/data/data` 里只挂着自己和少数可见包。从应用进程 fork 出来的 `su` 看不到别的应用目录（`ls /data/data` 只剩自己和 gms）。`/proc/1/root/data/data/…` 走 init 的根，绕开这层 namespace，指向同一个 inode，`cp` / `chown` / `rm` 照常生效。界面展示时会把该前缀还原成 `/data/data/…`。

### 部署链路

```
文件选择器 → cacheDir/picked → 解析（zip 保留子目录，读 schema: 字段）
    → 私有副本 filesDir/schemas/<schemaId>/ + 写入 Room
    → 高权限复制进目标 Rime 目录（逐文件 cp + chown <目录属主> + chmod 660）
    → 重写 default.custom.yaml（受管方案列表，激活方案置顶）与 user.yaml
    → 清掉相关编译产物（build/default.yaml、<id>.prism.bin 等）
    → 重启目标输入法，让 Rime 重新部署
```

- 覆盖配置前把原文件备份成 `*.ptfastbak`（只在首次备份，保留一个可回滚点）。
- 「恢复原始方案列表」会删掉本应用生成的 `default.custom.yaml`。
- 方案记录带 `imePackage` 字段按输入法隔离（Room v3，含 `MIGRATION_2_3`）。老版本没归属的记录会按「schema 文件是否在当前目录」认领，不会错记到别的输入法名下。

## 目录结构

```
app/src/main/kotlin/com/pt/fastrime/manager/
├── MainActivity.kt              # 入口：Scaffold + 底部导航 + Shizuku 授权回调 + Snackbar
├── PtFastRimeApp.kt             # Application：单例装配
├── privilege/
│   ├── PrivilegedExecutor.kt    # 统一接口 + ShellResult + exists/readText/listDir + shellQuote()
│   ├── ShellRunner.kt           # 子进程执行：输出落文件 + 哨兵行确认写完 + 超时保护
│   ├── RootExecutor.kt          # su -c 实现，复制后修正属主/权限
│   ├── ShizukuExecutor.kt       # 通过 IShizukuService.newProcess 执行
│   └── PrivilegeManager.kt      # 静默检测 + 状态流 + 当前 executor
├── rime/
│   ├── ImeTargets.kt            # 已安装输入法枚举（PackageManager + InputMethodManager）
│   ├── RimeEnvironment.kt       # 候选目录探测：scanAll() 一次命令遍历公有/私有路径
│   ├── RimeDeployer.kt          # 文件安装、备份、default.custom.yaml / user.yaml 生成、重启输入法
│   ├── SchemaRepository.kt      # 导入 → 私有副本 → 安装 → 部署 全链路 + 方案清单维护
│   └── RimeNative.kt            # 可选 librime JNI 封装（默认不启用）
├── data/                        # Room 实体/Dao/数据库（v3）+ DataStore 设置
└── ui/                          # Compose：主题、权限横幅、本地方案页、市场页、文件浏览器
app/src/main/cpp/                # 可选 JNI：rime_jni.cpp + CMakeLists.txt + rime_api.h
```

## 关于 JNI（可选）

- 已提供 `rime_jni.cpp`（dlopen `librime.so` → `rime_get_api()` → initialize / deploy / select_schema）与 `CMakeLists.txt`、`rime_api.h`。
- **默认不编译**：没有 NDK 时强行启用 `externalNativeBuild` 会直接构建失败，因此用开关控制：

```powershell
# 装好 NDK，并把 librime.so 放到 app/src/main/jniLibs/arm64-v8a/ 之后
.\gradlew.bat assembleDebug -PenableNativeRime=true -PndkVersion=<你的 NDK 版本>
```

- 运行时 `RimeNative.load()` 依次尝试：本应用 `nativeLibraryDir/librime.so` → 目标输入法的 `nativeLibraryDir/librime.so` → `librime.so`。
- 加载失败属于正常降级：native 调用返回 false，应用仍可用「写配置文件 + 重启输入法」完成部署。

## 常见问题

<details>
<summary><b>Root 明明授权了，应用却检测不到高权限？</b></summary>

某些 su 实现会先返回、子 shell 仍在往后写，之前会读到空输出。现在输出统一落文件并等哨兵行 `__PT_EXIT__` 出现，同时 `isAvailable()` 只跑 `id` 且 6 秒超时，不会再卡住界面。若仍失败，先在 Root 管理器里对 `com.pt.fastrime.manager` 重新授权（重装 APK 后部分管理器会清掉授权记录）。

</details>

<details>
<summary><b>下拉框里看不到我装的输入法？</b></summary>

只列出**探到 Rime 目录**的输入法。若目标输入法没出现：先启动它一次（多数前端首次运行才会创建 Rime 目录），再回到本页点刷新。非 Rime 输入法（搜狗、Gboard 等）本身就不该出现，探不到 Rime 目录的也一律隐藏。

</details>

<details>
<summary><b>方案列表是空的，但键盘里明明有方案？</b></summary>

有些输入法（例如同文）把方案打包在 APK 内，目录里没有散装 `*.schema.yaml`，因此只能切换过去、无法逐条管理。其余情况点一次刷新，应用会把目录里已有的方案收编进来。

</details>

<details>
<summary><b>应用会改坏我的输入法配置吗？</b></summary>

只会写 `default.custom.yaml` / `user.yaml`，覆盖前备份为 `*.ptfastbak`；再点「恢复原始方案列表」可删除本应用生成的文件。注意：**停用**「设备上已有的方案」只把它从 `schema_list` 里摘掉，不会删除输入法自己的文件。

</details>

<details>
<summary><b>方案文件会不会被输入法自己覆盖回去？</b></summary>

部分 Rime 前端启动时会按自己的清单补齐或清理数据目录，我们写进去的方案可能被它重写。遇到这种情况：改完先别启动该输入法，或改完立刻刷新一次，让应用重新收编当前实际存在的方案。

</details>

## 已知限制

- 仅 `arm64-v8a`；32 位设备需自行调整 `abiFilters`。
- 方案打包在 APK 内的输入法（如同文）只能识别、切换，无法逐条启停其内置方案。
- 改动需要重启目标输入法才生效；没有启动器 Activity 的输入法服务无法用 `monkey` 拉起，需用户手动切换一次键盘。
- 修改的是第三方输入法的实时数据目录，**风险自担**；请在改动前保留可回滚的备份（应用会自动留 `*.ptfastbak`）。
- 方案市场是纯占位页，不包含任何网络代码。

## 许可

采用 [MIT License](LICENSE)。

## 免责声明

本应用通过 Root / Shizuku 直接改写第三方输入法的实时数据目录，属于侵入式操作。使用前请自行确认风险，作者不对数据丢失、输入法异常等后果负责。
