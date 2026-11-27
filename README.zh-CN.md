<div align="left">
    <picture>
        <img alt="YggdrasilManager 树形图标" src="docs/brand/yggdrasil-icon.png" width="96">
    </picture>

<h1>YggdrasilManager</h1>

![CI](https://github.com/ngiokweng/YggdrasilManager/workflows/CI/badge.svg)

</div>

[English](README.md) | [繁體中文](README.zh-TW.md) | **简体中文**

Yggdrasil KPM 模块的 Android 管理工具，使用 Compose 界面，并提供硬件断点与 arm64 系统调用观察的浏览器控制台。也能管理调试状态与内存映射策略，内置可独立运行的模块自测程序。

## 使用要求

- 运行 Android 7.0／API 24 或更新版本的 arm64 Android 设备。
- Root 权限，以及已加载的兼容 Yggdrasil KPM 模块。Manager 不负责安装内核模块。
- 构建环境：JDK 21、Node.js 22 或更新版本，以及 Android SDK。SDK、NDK 与 CMake 的确切版本请见[构建指南](docs/release/releasing.zh-CN.md#toolchain)。

## 安装

从源码构建 debug APK：

```sh
git clone https://github.com/ngiokweng/YggdrasilManager.git
cd YggdrasilManager
npm ci --prefix frontend
npm run sync:android --prefix frontend
bash ./gradlew :app:assembleDebug
```

`sync:android` 会用重新构建的前端文件替换 App 内置的浏览器资源。在 Windows PowerShell 中，请将最后一行改为 `.\gradlew.bat :app:assembleDebug`。

使用 Android 平台工具安装 APK：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug 版的包名为 `ng1ok.yggdrasilmanager.debug`，可与包名为 `ng1ok.yggdrasilmanager` 的签名 release 版共存。正式 APK 的构建方式请见 [release 构建与签名](docs/release/releasing.zh-CN.md)。

## 配置

使用设备上的模块工具加载 Yggdrasil 模块，再打开 Manager 并授予 Root 权限。主页会分别显示模块状态、Root 状态与 Manager 版本。

浏览器控制台没有身份验证。请仅在可信网络中使用，并在使用完毕后停止服务器。

## 快速开始

1. 确认主页显示模块可用，且已取得 Root 权限。
2. 打开 **功能 → Arm64 Syscall Observe** 或 **HWBP Observe**。
3. 在同一网络的浏览器中打开手机显示的控制台网址。HWBP 使用端口 `8765`，Syscall 使用 `8766`。

[图文使用指南](docs/user-guide/guide.zh-CN.md)提供四项功能的操作步骤与截图说明。

## 功能

- **Arm64 Syscall Observe**：拦截与改写系统调用、捕获参数，并在浏览器中查看事件。
- **HWBP Observe**：配置硬件断点，查看命中事件与调用栈。
- **Hide DBG**：按应用管理调试器与停止状态策略。
- **Hide Maps**：隐藏内存映射条目，或改写显示的访问权限。
- **模块自测**：通过 Root 运行内置的 arm64 程序，并查看各测试部分的结果。

实际可用的操作取决于已加载的模块与设备。

## 文档

- [图文使用指南](docs/user-guide/guide.zh-CN.md)
- [构建、签名、版本管理与 GitHub 发布](docs/release/releasing.zh-CN.md)
- [开源准备与待定事项](docs/release/open-source-readiness.zh-CN.md)
- [浏览器控制台开发](docs/development/frontend.zh-CN.md)
- [更新日志](docs/release/changelog.zh-CN.md)

## 测试

```sh
node --test scripts/test/*.test.mjs
npm test --prefix frontend
npm audit --prefix frontend --audit-level=high
bash ./gradlew :app:testDebugUnitTest :app:lintDebug
```

CI 还会重新构建浏览器资源，并编译 debug 与 release APK。CI 的 release 构建使用临时密钥；版本标签触发的发布流程则使用正式签名私钥，并在发布前核对产物的证书。

## 致谢

原生调用栈回溯使用项目内置的 `libunwindstack-ndk`，Root 操作使用 `libsu`。组件来源与保留的许可声明请见[第三方组件](docs/project/credits.zh-CN.md)。

项目尚需选定整体许可证，才能以开源项目的名义对外提供；请见[待定事项](docs/release/open-source-readiness.zh-CN.md)。
