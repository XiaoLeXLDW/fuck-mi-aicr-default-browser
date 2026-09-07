# v0.3.3 构建与验收证据

记录日期：2026-09-07。当前版本 `0.3.3 (10)`，业务协议 `200`，服务代 `30004`。
本页只保留当前发行包的证据；旧版本资料可从 Git 提交历史查阅。
修复范围见 [v0.3.3 修复说明](FIX_REVIEW_0.3.3.md)，安装说明见 [Release](releases/v0.3.3.md)。

## 本地构建

使用 JDK 17.0.9、Gradle 8.9、项目内 Android SDK 35 / Build Tools 35.0.0：

```powershell
.\scripts\build.ps1 -Variant Release -LocalTestSigning
.\scripts\build.ps1 -Variant Debug -LocalTestSigning
.\tests\verify-stop.Tests.ps1
.\tests\verify-stop-process.Tests.ps1
.\tests\current-docs.Tests.ps1
```

| 检查 | 结果 |
| --- | --- |
| Release / Debug 构建 | 成功，分别 28 秒 / 52 秒；增量构建复用未变化任务 |
| Debug / Release JUnit 报告 | 每个变体 119 项，失败、错误、跳过均为 0 |
| Stellar 原生源码 harness 报告 | 14 项，失败、错误、跳过均为 0；作为两个变体的共同依赖 |
| 离线 PowerShell 回归 | 停服工作流 24 项、真实本地子进程边界 3 项、当前文档规则 3 项，本次全部执行通过 |
| Debug / Release Lint | 均为 0 错误、12 警告 |

共有 **133 项不同 Java 用例**；两个变体不能重复计数。增量构建中的 `UP-TO-DATE`
表示复用对应未变化源码的既有测试结果，不代表本次重新执行全部用例。
测试使用 mock Android 或可控传输，不是真实设备 Looper、管理器 Binder 服务器或 ROM 验收。

12 条 Lint 警告为 `ApplySharedPref` 2、`DataExtractionRules` 1、`MonochromeLauncherIcon` 1、
`ObsoleteSdkInt` 1、`Overdraw` 1、`SetTextI18n` 5、`UseCompoundDrawables` 1，未通过屏蔽规则隐藏。

## 发行包身份

| 项目 | 值 |
| --- | --- |
| APK | `MiBrowserRedirector-v0.3.3-universal.apk` |
| 大小 | 以 GitHub Release 最终附件记录为准 |
| 包名 | `dev.codex.mibrowserredirector` |
| 版本 | `versionName 0.3.3` / `versionCode 10` |
| APK SHA-256 | 以 GitHub Release 同名 `.sha256` 附件为准 |
| 证书 SHA-256 | `24211beec19c121f4135640e9c95c75c5b981726b3172b7658e169f28894d376` |

Universal 附件与 `dist/MiBrowserRedirector-release-local-test-v0.3.3.apk` 字节一致，
同名 `.sha256` 使用实际附件文件名。Debug 产物仅用于开发验证，不作为发行附件。

AGP 会在 APK 的 `META-INF/version-control-info.textproto` 写入构建时的 Git 提交号，
因此源码提交后必须重新构建再计算最终哈希。发行包从 `v0.3.3` 对应提交构建；
最终大小、哈希、源码提交和远端验证结果以 Release 正文及附件为准。

APK 包名、版本、双后端 Manifest、非 debuggable、无原生 ABI 限制、v2 签名、ZIP 对齐、
五份内置许可证、自适应/单色启动图标资源和校验文件已核验。沿用既有测试证书，
现场与保存的旧发行 APK 读取比较后证书一致；不将测试证书称为新的正式证书。
同包名、同签名、递增版本号满足覆盖升级条件，但本轮未在手机执行安装。

## GitHub 检查

[Android checks](https://github.com/XiaoLeXLDW/fuck-mi-aicr-default-browser/actions/workflows/android.yml)
对源码执行仓库检查、UI 结构检查、三组离线脚本回归，以及 Debug / Release 单测、Lint、构建。
CI 产物是开发签名 Debug 与未签名 Release，不包含发行私钥；可安装的预发布附件由本地签名构建提供。
对应源码以 `v0.3.3` 标签为准，实际远端运行与附件核验信息记录在
[GitHub Release](https://github.com/XiaoLeXLDW/fuck-mi-aicr-default-browser/releases/tag/v0.3.3)。

## 实机边界

本版未连接手机、未运行 ADB 或模拟器，尚未验证安装覆盖、HyperOS 桌面及 App 显示、
Shizuku / Stellar 授权、跳转、停服、进程冷启动和故障恢复。顶部安全距、折叠/横屏、
大字体与 TalkBack 也需实机确认，因此继续发布为 **Pre-release**。
辅助脚本的 `am start` 仅表示返回页面，不能证明冷启动。步骤见 [实机验收](实机验收.md)。
