# 构建与发布 YggdrasilManager

[English](releasing.en.md) | [繁體中文](releasing.zh-TW.md) | **简体中文**

Manager 版本来自根目录的 `version.properties`。Android 主页读取 `BuildConfig.VERSION_NAME`，因此界面与 APK 使用相同的版本信息。内核模块有独立的版本与发布周期。

<a id="toolchain"></a>

## 工具链

| 组件 | 版本 |
| --- | --- |
| JDK | 21 |
| Node.js | 22 或更新版本；GitHub Actions 使用 22 |
| Gradle wrapper | 9.4.1，验证发行文件的 SHA-256 |
| Android Gradle plugin | 9.2.1 |
| Android compile SDK | 36.1（`platforms;android-36.1`） |
| Android build tools | 36.0.0 |
| Android NDK | 28.2.13676358 |
| CMake | 3.22.1 |

APK 仅包含 `arm64-v8a`；KPM ABI、系统调用目录与内置自测程序均以 arm64 为目标。Android 最低版本为 API 24，目标版本为 API 36。

通过 Android Studio 或 `sdkmanager` 安装 Android 软件包：

```sh
sdkmanager 'platforms;android-36.1' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
```

将 `ANDROID_HOME` 指向 SDK，或在已被 Git 忽略的 `local.properties` 中设置 `sdk.dir`。Windows PowerShell 请用 `.\gradlew.bat` 替换 `bash ./gradlew`。命令明确使用 `bash`，是因为仓库中的 wrapper 目前未设置 Unix 可执行权限。

## 版本管理

```sh
node scripts/version.mjs show
node scripts/version.mjs bump patch
```

`bump major`、`bump minor` 与 `bump patch` 会增加对应的 SemVer 版本字段、清除预发布后缀，并将 Android `versionCode` 加一。每次发布选择一种升版方式。

创建预发布版本，或将预发布版本升为正式版时，使用 `set`：

```sh
node scripts/version.mjs set 1.1.0-rc.1
node scripts/version.mjs set 1.1.0
```

每次 `set` 也会将 `versionCode` 加一。相同或更低的 SemVer 版本会被拒绝。版本名称不包含构建元数据（`+...`），让每个可接受的名称对应唯一的发布标签。Android 版本码必须介于 1 至 2,100,000,000，且高于所有已分发版本的版本码。增加主版本时也不可重置版本码。

私有前端包的 `version` 仅为工具包版本，不决定 Manager 版本；发布时以根目录版本文件为准。

每次创建标签前，先更新[更新日志](changelog.zh-CN.md)。发布流程只接受与 `v` 加上 `versionName` 完全一致的标签：

```sh
node scripts/version.mjs check-tag v1.0.0
```

## 发布密钥

在新项目或 fork 初次配置时运行：

```sh
node scripts/generate-signing-key.mjs
```

脚本会在 PKCS#12 密钥库中生成 4096 位 RSA 密钥，使用随机密码、SHA256withRSA 签名，证书有效期为 10,000 天。需要 JDK 的 `keytool`；若设置了 `JAVA_HOME`，优先使用 `JAVA_HOME/bin`。脚本不会覆盖已有密钥库或本地配置文件。

| 文件 | 处理方式 |
| --- | --- |
| `.signing/yggdrasilmanager-release.p12` | 私钥；由 Git 忽略；请私下备份。 |
| `keystore.properties` | 密码与别名；由 Git 忽略；请与密钥一起私下备份。 |
| `.signing/release-certificate.pem` | 生成的公开证书。 |
| `signing/release-certificate.pem` | 提交到仓库的公开证书，用于发布验证。 |

本仓库已生成发布密钥，后续更新请沿用。丢失密钥会导致无法更新由该密钥签名的安装版本；重新生成密钥不是常规发布步骤。请在工作区外保留加密备份，GitHub Secrets 无法导出备份。

Fork 若自行生成密钥，请在发布前将公开证书复制到 `signing/release-certificate.pem`。Gradle 不要求贡献者构建必须符合此证书，但发布流程会在公开产物前验证。不可将私钥库复制到 `signing/`。

使用已有密钥时，将 `keystore.properties.example` 复制为 `keystore.properties`，填好四个字段。`storeFile` 若不是绝对路径，则以仓库根目录为基准；Windows 请使用正斜杠。环境变量优先于本地配置：

| 变量 | 用途 |
| --- | --- |
| `YGGDRASIL_KEYSTORE_FILE` | 密钥库路径 |
| `YGGDRASIL_KEYSTORE_PASSWORD` | 密钥库密码 |
| `YGGDRASIL_KEY_ALIAS` | 签名别名 |
| `YGGDRASIL_KEY_PASSWORD` | 密钥密码 |

Release 构建缺少任一签名配置便会失败，不会改用 debug 密钥。代码压缩保持禁用，因为 JNI 与 libsu Root 服务需先经过独立的真机验证，才能启用。

## 本地构建与验证

前端同步会用当前源码的构建结果替换 `app/src/main/assets/observe-web/`。每次打包前都应重新构建：

```sh
npm ci --prefix frontend
npm test --prefix frontend
npm run sync:android --prefix frontend
node --test scripts/test/*.test.mjs
bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

输出位置：

- `app/build/outputs/apk/debug/app-debug.apk`：包名为 `ng1ok.yggdrasilmanager.debug`，版本后缀为 `-debug`。
- `app/build/outputs/apk/release/app-release.apk`：已签名的 `ng1ok.yggdrasilmanager` 软件包。
- `app/build/reports/`：单元测试与 lint 报告。

旧开发版曾使用正式包名搭配 debug 证书。Android 无法用新的正式证书直接更新这类安装；安装 release 前，请先保留需要的设置并卸载旧版。新的 debug 版使用独立的 `.debug` 包名，不会发生此冲突。

使用 SDK 的 `build-tools/36.0.0` 中的 `apksigner` 与 `zipalign`（Windows 为 `apksigner.bat` 与 `zipalign.exe`）：

```sh
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk
keytool -printcert -file signing/release-certificate.pem
```

将工具目录加入 `PATH`，或使用绝对路径调用，并在仓库根目录运行。确认 APK 签名的 SHA-256 摘要与公开证书一致。发布流程会自动执行此检查，并为 APK 与公开证书生成 `SHA256SUMS`。

## GitHub Actions 配置

仓库必须启用 Actions。使用已登录的 GitHub CLI 配置仓库 Secrets：

```sh
node scripts/configure-github-signing.mjs ngiokweng/YggdrasilManager
```

Fork 请使用自己的 `OWNER/REPOSITORY`。脚本通过标准输入传递数值，更新以下四项 Actions Secrets，不会打印内容：

- `YGGDRASIL_KEYSTORE_BASE64`
- `YGGDRASIL_KEYSTORE_PASSWORD`
- `YGGDRASIL_KEY_ALIAS`
- `YGGDRASIL_KEY_PASSWORD`

`ngiokweng/YggdrasilManager` 已完成配置。不可将 `keystore.properties` 或私钥作为 workflow artifact 上传。正式签名构建会禁用持久化 Gradle 缓存与 configuration cache，避免签名数据被保存在上传的缓存中。

| Workflow | 触发方式 | 结果 |
| --- | --- | --- |
| `CI` | 分支推送、PR 与手动运行 | 版本测试、npm audit、前端测试与构建、Android 测试、debug／release lint 与 APK 编译；上传 debug APK 与报告。 |
| `Release` | `v*` 标签；从标签手动重跑 | 检查标签与版本一致，执行测试、audit 与 lint，使用私钥构建，验证证书与 16 KB ZIP 对齐，再将 APK、证书与校验文件发布到 GitHub release。 |

PR（含 fork）会生成临时签名密钥，不需要正式 Secrets。CI 的 release APK 仅用于编译检查，不对外分发。Actions 通过 commit SHA 固定版本；Dependabot 每周检查 Actions、Gradle 与 npm 依赖。

## 发布

提交已审阅的源码、生成的浏览器资源、公开证书、版本与更新日志，再推送分支。CI 通过后，才对同一个 commit 创建标签。初始版本使用：

```sh
git tag -a v1.0.0 -m 'YggdrasilManager 1.0.0'
git push origin v1.0.0
```

后续发布必须使用升版后的版本。`v1.1.0-rc.1` 这类标签会创建 GitHub prerelease。已有 release 不会被覆盖，请勿移动已发布标签。Workflow 存在于默认分支后，可使用标签作为 ref 手动重跑，例如 `gh workflow run release.yml --ref v1.0.0`。

Workflow 不会更改仓库可见性。将仓库公开前，请先选定项目许可证并完成[开源待定事项](open-source-readiness.zh-CN.md)。Workflow 变更提交并推送后，才能验证 GitHub 托管环境的实际运行结果。
