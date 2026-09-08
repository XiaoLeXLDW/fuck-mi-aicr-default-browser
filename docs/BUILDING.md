# 构建与签名

下列输出名以当前版本 **v0.3.4（code 11 / 协议 200 / 服务代 30005）** 为例。
`build.ps1` 从源码读取版本并生成文件名；最终以 `app/build.gradle`、`ServiceIdentity.java` 和
构建日志为准。修改 APK 文件名不会改变包内版本或签名。当前产物、校验值与 CI 信息见
[版本说明](releases/v0.3.4.md)。

## 工具版本

| 工具 | 当前配置 |
|---|---|
| Java | JDK 17；需要 `java` 和 `keytool` |
| Gradle | Wrapper 固定 8.9，发行包 SHA-256 固定 |
| Android Gradle Plugin | 8.7.3 |
| Android SDK | Platform 35、Build Tools 35.0.0；App minSdk 26 / targetSdk 35 |
| Windows 脚本 | PowerShell 7；SDK、缓存、开发密钥保存在项目内 |

## Windows 开发构建

在项目根目录运行，确保 `JAVA_HOME` 指向已有 JDK 17：

```powershell
.\scripts\bootstrap.ps1
.\scripts\build.ps1 -Variant Debug
```

首次需要下载依赖和接受 Android SDK 许可；bootstrap 默认交互展示许可。
已阅读并同意许可的无人值守环境可显式使用 `-AcceptSdkLicenses`。
脚本不安装 JDK。若 PowerShell 阻止脚本执行，可在当前终端使用
`Set-ExecutionPolicy -Scope Process Bypass`，无需更改全局策略。

按上述版本构建会输出 `dist/MiBrowserRedirector-debug-debug-signed-v0.3.4.apk` 和同名 `.sha256`。
新克隆会生成项目内 `.local/debug.keystore`，不同克隆的开发证书可能不同。

```powershell
.\scripts\install.ps1 -Apk .\dist\MiBrowserRedirector-debug-debug-signed-v0.3.4.apk
```

安装会操作 ADB 设备；多个设备时追加 `-Serial`。不能覆盖不同签名的现有安装，脚本不会自动卸载。

## Release 与签名类型

```powershell
.\scripts\build.ps1 -Variant Release
```

无签名配置时输出 `dist/MiBrowserRedirector-release-unsigned-v0.3.4.apk`。这是可检查的构建产物，
**不能直接安装或当作已签名发行包**。每次构建脚本只为所选 Variant 执行单测、Lint，另做 UI 结构检查、
Manifest/包名/版本检查，并按签名类型验证 APK。

| 配置 | 输出标签 | 用途 |
|---|---|---|
| Debug 默认 | `debug-signed` | 自行开发测试 |
| Release 无签名配置 | `unsigned` | CI / 检查 / 后续签名 |
| 四项发布环境变量齐全 | `signed` | 使用指定证书；证书来源与用途由维护者核验 |
| `-LocalTestSigning` | `local-test` | 显式复用已有历史测试证书 |

正式签名读取四个环境变量：`REDIRECTOR_KEYSTORE`（密钥路径）、`REDIRECTOR_STORE_PASSWORD`、
`REDIRECTOR_KEY_ALIAS`、`REDIRECTOR_KEY_PASSWORD`。请通过私有终端或受保护的构建环境设置；
不要把真实密码写进命令历史、README、GitHub Actions 文件或提交的 `.properties`。
必须全部设置，或全部不设置；部分配置会报错。当前 CI 不读取发行密钥、不自动发布。

需要保持本机历史 APK 的签名兼容时：

```powershell
.\scripts\build.ps1 -Variant Release -LocalTestSigning
```

此模式要求已有 `keys/redirector-test.jks`，不会生成替代密钥，输出标签为 `local-test`。
切换正式证书前需考虑升级连续性；包名相同也不能直接跨证书覆盖。

## Linux / macOS / Android Studio

使用已有 JDK 17 与 Android SDK，设置 `ANDROID_HOME`，或在不提交的 `local.properties` 指定 `sdk.dir`。
SDK 需包含 `platforms;android-35` 和 `build-tools;35.0.0`。在项目根目录运行：

```bash
export GRADLE_USER_HOME="$PWD/.gradle-user-home"
bash ./gradlew --no-daemon :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

直接 Wrapper 的 APK 在 `app/build/outputs/apk/release/`，不会自动复制到 `dist/`；
没有签名配置时文件为 `app-release-unsigned.apk`。Wrapper 支持
`-PlocalTestSigning=true`，但只有保存着原测试密钥的维护者环境可使用。

Windows `bootstrap.ps1` / `build.ps1` / ADB 辅助脚本依赖 `.bat` / `.exe` 工具；
其他平台使用 Wrapper。`verify-ui.ps1` 与 `verify-repository.ps1` 可通过 PowerShell 7 跨平台运行。

## 验证与报告位置

Debug / Release 单测同时依赖 `:app:testStellarLifecycle`：直接编译原生会话生产源码，
使用只在测试目录内的 Android/管理器替身。测试替身不进入 APK。报告位于
`app/build/test-results/testStellarLifecycle/`。

离线辅助脚本回归：

```powershell
.\tests\verify-stop.Tests.ps1
.\tests\verify-stop-process.Tests.ps1
.\tests\current-docs.Tests.ps1
.\tests\export-wiki.Tests.ps1
```

这些测试不连接设备，分别检查 ADB 辅助脚本、当前文档版本字段和 Wiki 链接转换。
`verify-repository.ps1` 另检查兼容表的版本/code/协议/服务代及隐私页首段版本。

```powershell
.\scripts\verify-repository.ps1
.\scripts\verify-ui.ps1
```

单测报告：`app/build/reports/tests/`；Lint：`app/build/reports/lint-results-*.html`。
GitHub Actions 的 [android.yml](../.github/workflows/android.yml) 同时执行 Debug / Release 单测、
Lint 和构建，上传 `app-debug.apk`（开发签名，可安装测试）、`app-release-unsigned.apk`
（未签名，不可直接安装）及 `app/build/reports/`，构建附件保留 14 天。开发证书不是发行证书，
也不保证跨 CI 运行或本机安装可覆盖；当前工作流没有发行密钥、自动 Release 或 Wiki 发布步骤。
当前版本的校验与 CI 信息见[版本说明](releases/v0.3.4.md)。
真机脚本会操作手机，执行前阅读[实机验收](实机验收.md)。

修改根许可证或第三方声明后，同步 `app/src/main/assets/licenses/` 对应文本；仓库检查会检查副本一致性。
