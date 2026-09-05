# 构建与签名

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

当前工作树输出 `dist/MiBrowserRedirector-debug-debug-signed-v0.3.2.apk` 和同名 `.sha256`。
新克隆会生成项目内 `.local/debug.keystore`，不同克隆的开发证书可能不同。

```powershell
.\scripts\install.ps1 -Apk .\dist\MiBrowserRedirector-debug-debug-signed-v0.3.2.apk
```

安装会操作 ADB 设备；多个设备时追加 `-Serial`。不能覆盖不同签名的现有安装，脚本不会自动卸载。

## Release 与签名类型

```powershell
.\scripts\build.ps1 -Variant Release
```

无签名配置时输出 `dist/MiBrowserRedirector-release-unsigned-v0.3.2.apk`。这是可检查的构建产物，
**不能直接安装或当作已签名发行包**。构建脚本为两种 Variant 执行单测、Lint、UI 结构检查、
Manifest/包名/版本检查，并按签名类型验证 APK；不会替代实机验收。

| 配置 | 输出标签 | 用途 |
|---|---|---|
| Debug 默认 | `debug-signed` | 自行开发测试 |
| Release 无签名配置 | `unsigned` | CI / 检查 / 后续签名 |
| 四项发布环境变量齐全 | `signed` | 使用维护者提供的证书；工具不会替你证明它是正式证书 |
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
历史测试口令不是正式发布凭据。既有 `dist/MiBrowserRedirector-release-v*.apk` 保留为历史产物，
新脚本使用带签名类型的文件名。切换正式证书前需考虑升级连续性；包名相同也不能直接跨证书覆盖。

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

```powershell
.\scripts\verify-repository.ps1
.\scripts\verify-ui.ps1
```

单测报告：`app/build/reports/tests/`；Lint：`app/build/reports/lint-results-*.html`。
GitHub Actions 运行同类静态检查，并上传未签名 APK 与报告；配置存在不等于远端已通过。
真机脚本会操作手机，执行前阅读[实机验收](实机验收.md)。

修改根许可证或第三方声明后，同步 `app/src/main/assets/licenses/` 对应文本；仓库检查会检查副本一致性。
