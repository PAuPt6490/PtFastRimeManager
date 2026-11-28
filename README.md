<div align="center">

<img src="PtFastRimeManager.png" width="140" alt="PtFastRimeManager 图标">

# PtFastRimeManager

**面向 Android 的 Rime 输入法方案管理器，以后可能会加入方案市场（可能在建方案市场库了）**

因为嫌Rime配置太麻烦，最开始只是想让ai搞一个导入工具，写着写着直接变异成Rime管理器了

主要面向小企鹅输入法+中州韵Rime插件和同文输入法Trime，理论上支持所有Rime输入法（依输入法实际行为而定）。

</div>

---

## 简介

众所周知，小企鹅+Rime和同文都是基于Rime中州韵，要配置输入方案相当麻烦，于是我就用AI造了个配置管理器

## 特性
主要面向小企鹅Rime插件和同文Trime，理论上支持所有基于Rime的输入法
全程准备好zip包或配置文件，导入后管理器负责配置

## 快速开始
- 首先就是安卓8以上
- 然后就是要[https://shizuku.rikka.app/](Shizuku)或Root

### 安装
在[https://github.com/PAuPt6490/PtFastRimeManager/releases](Releases)下载安装
或
```bash
adb connect <手机IP>:<无线调试端口>
adb -s <手机IP>:<无线调试端口> install -r app/build/outputs/apk/debug/app-debug.apk
```

### 使用方法

1. 打开应用，顶部横幅显示「Root 模式已就绪」或「Shizuku 模式已就绪」即可使用。若显示不可用，先在Root管理器或Shizuku里授权，再点击重新检测
2. 在「目标输入法」下拉框里选要管理的输入法（只列出探到Rime相关文件/目录的）。
3. 点「导入本地方案」选 `.zip` 或 `*.schema.yaml`；也可以直接点刷新，把该输入法已有方案收编进来。

## 许可

采用 [MIT License](LICENSE)。
