# 开发说明

项目使用 Java 17、Android SDK 35 和 Gradle Wrapper 8.9，应用模块为 `app`，隐藏系统接口只在
`hidden-api-stub` 提供编译桩。本文面向 v0.3.4（code 11 / 协议 200 / 服务代 30005），
应用 ID 为 `dev.codex.mibrowserredirector`。

## 获取与构建

克隆仓库后安装或指定 Java 17 和 Android SDK 35，通过 `ANDROID_HOME` 或本机 `local.properties` 指定 SDK；本机路径、SDK、缓存与签名文件不要提交。详细环境准备与签名参数见[构建与签名](../BUILDING.md)及仓库 `scripts/`。

在仓库根目录运行：

```bash
export GRADLE_USER_HOME="$PWD/.gradle-user-home"
bash ./gradlew --no-daemon :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

Windows PowerShell 对应：

```powershell
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user-home'
.\gradlew.bat :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

Release 默认未签名；安装测试推荐构建 Debug，新克隆会生成项目内 `.local/debug.keystore`。`-LocalTestSigning` 只供持有原 `keys/redirector-test.jks` 的维护者显式复用历史测试证书。当前 Pre-release 沿用该证书以保持升级连续性，它仍是测试签名；未来采用正式发布证书时需另行处理升级关系，不能假定新证书能覆盖既有安装。

## 阅读顺序

1. 从[架构](Architecture.md)与 `MainActivity` 查看界面/daemon 的状态边界和浏览器草稿处理。
2. 阅读 `PrivilegeRuntime` 和两个 adapter，确认权限请求、会话归属与管理器 Binder 来源。
3. 阅读 `RedirectorUserService`、`UrlExtractor`、`RedirectDispatcher` 和 `AmProcessLauncher`，核对匹配、配置隔离、去重、任务取消和子进程清理。
4. 阅读 `ServiceTaskRunner`、`UserServiceStopper`、Binder helper 与测试，理解独立停止通道、限时等待和 Binder 死亡确认。

## 验证范围

本地测试覆盖 URL 解析、浏览器选择/资格判断、后端选择/归属、停止、独立任务通道、跳转调度和
子进程清理；Lint 和 Manifest 检查提供静态证据。这些检查不能验证目标 ROM 的隐藏 API、权限
授权界面、真实超级岛链接、页面视觉与停止后的系统行为。

GitHub `Android checks` 同时运行 Debug / Release 单测、Lint 和 APK 构建，上传开发签名
`app-debug.apk`、未签名 `app-release-unsigned.apk` 及报告，保留 14 天；不使用发行密钥，
不自动创建 Release 或发布 Wiki。开发 APK 可安装测试，但不保证与已有发行安装签名相容。

修改权限或生命周期后，分别执行官方 Shizuku、Stellar 原生与双管理器场景；修改 UI 后执行大字体、横屏/折叠与 TalkBack 场景。设备测试步骤见[实机验收清单](../实机验收.md)，当前版本的校验与 CI 信息见[版本说明](../releases/v0.3.4.md)。

离线文档回归入口为 `tests/current-docs.Tests.ps1` 与 `tests/export-wiki.Tests.ps1`，分别验证
版本字段检查和 Wiki 链接转换。

## 文档与提交

Wiki 的可维护源文件在主仓库 `docs/wiki/`。页面间保留同级 `.md` 链接，主仓库文档与资源使用正确的相对路径。发布时用 `scripts/export-wiki.ps1 -Repository owner/repo` 导出：同级页面指向目标 Wiki，跨目录链接指向固定源码提交，再同步到单独的 Wiki 仓库。`-SourceRevision` 默认 `HEAD`，可指定标签或提交，导出时解析为完整 SHA；对应提交需已推送到主仓库。`owner/repo` 需替换为真实仓库名，导出器不执行网络写入。贡献与问题反馈规则见仓库根目录文档和 Issues 模板。

提交中说明具体问题、最终行为和已执行检查；未完成的设备测试写明“未执行”。不要提交个人日志、UI 转储、签名私钥、工具缓存或带登录参数的示例网址。
