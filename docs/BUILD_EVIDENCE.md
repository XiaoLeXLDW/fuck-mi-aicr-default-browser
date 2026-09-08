# v0.3.4 构建与验收证据

记录日期：2026-09-08。版本 `0.3.4 (11)`，业务协议 `200`，服务代 `30005`。
本版修复 App 操作提示、项目文档、图标声明和 Wiki 导出链接；变更见[更新记录](../CHANGELOG.md)。

## 本地检查

使用 JDK 17.0.9、Gradle 8.9、项目内 Android SDK 35 与 Build Tools 35.0.0。
Debug / Release 单测与 Lint 合并运行成功，用时 58 秒。

| 检查 | 结果 |
| --- | --- |
| Debug / Release Java 单测 | 每个变体 119 项，失败、错误、跳过均为 0 |
| Stellar 原生源码 harness | 14 项，失败、错误、跳过均为 0 |
| 离线停服工作流 / 子进程 / 版本字段回归 | 24 / 3 / 3 项，本次执行通过 |
| Wiki 导出回归 | Windows PowerShell 7 实际执行 17 项通过；真实 9 页 Wiki 的 GitHub 与本地预览导出链接通过 |
| Debug / Release Lint | 均为 0 错误、11 警告 |

共有 133 项不同 Java 用例，两个构建变体不重复计数；未变化的增量任务可能复用已验证结果。
这些检查使用 mock Android、可控传输或本地测试子进程，不连接手机。
Wiki 导出回归通过真实脚本生成文件，检查同级链接、跨目录链接、固定提交、路径编码与错误输入。
同一组 Wiki 用例已接入 Windows 与 Linux CI；远端结果以本页下方的固定运行记录为准。

`verify-current-docs.ps1` 只核对兼容表中的版本、code、协议、服务代及两份隐私说明开头的版本号。
仓库检查还覆盖本地 Markdown 链接、脚本语法、凭据路径、Wrapper 和许可证副本；这些检查不等于
所有文案语义、远端配置、图片视觉和手机行为均已验证。

## 发行包身份

附件为 `MiBrowserRedirector-v0.3.4-universal.apk` 与同名 `.sha256`，沿用既有测试证书：

```text
Package: dev.codex.mibrowserredirector
Version: 0.3.4 (11)
Protocol: 200
Service generation: 30005
Certificate SHA-256: 24211beec19c121f4135640e9c95c75c5b981726b3172b7658e169f28894d376
```

AGP 在 `META-INF/version-control-info.textproto` 中嵌入构建时的 Git 提交号。
源码提交并固定标签后再生成发行包、计算哈希，避免使用提交前产物的校验值。
本版本最终源码提交、APK 大小、SHA-256、CI 与回下载结果见
[v0.3.4 Release](https://github.com/XiaoLeXLDW/fuck-mi-aicr-default-browser/releases/tag/v0.3.4)。

## 已发布版本

v0.3.3 保持原标签和附件身份。其 Release 的构建证据已固定到
[原版发行记录](https://github.com/XiaoLeXLDW/fuck-mi-aicr-default-browser/blob/591339e07ea6740e5e0e76c7162ef73eadc2ec07/docs/BUILD_EVIDENCE.md)，
不会随当前文档更新而指向新版本。GitHub About 已同步为当前链接重定向功能描述。

## 实机边界

本版未运行手机或模拟器验收。覆盖安装、Shizuku / Stellar 实际授权、跳转、停服、重开和异常恢复，
以及 HyperOS 图标、页面安全区、大字体、横屏/折叠与 TalkBack 仍待验证。
测试证书沿用只表明签名连续性，单测、签名、校验和及文档检查不能替代设备行为验证。
操作步骤见[实机验收](实机验收.md)。

## 最终发行核验

发行源码提交：`982b6fa734896aadd2d91cceecf9948efca9bc81`（`v0.3.4` 标签）。
从该提交构建 Release 成功，用时 23 秒；APK 内嵌提交号已核对一致。

| 项目 | 核验值 |
| --- | --- |
| APK | `MiBrowserRedirector-v0.3.4-universal.apk` |
| 大小 | 184442 bytes |
| SHA-256 | `8cf4b9c18c66e01884dc1eb1e74782f16e2543ff65ec3e44b352c528de3ebfe3` |

包名/版本、非 debuggable、v2 签名、ZIP 对齐、无原生 ABI 限制和五份内置许可证/声明均通过。
包内第三方声明与当前源码一致，App 资源包含“网页重定向”与正确的观察模式操作提示。
未发现测试 harness 或 JUnit 类被打入 APK。APK 与同名校验文件上传后回下载，哈希逐一一致。

[主分支 CI](https://github.com/XiaoLeXLDW/fuck-mi-aicr-default-browser/actions/runs/34201422410) 与
[标签 CI](https://github.com/XiaoLeXLDW/fuck-mi-aicr-default-browser/actions/runs/34201422597)
均对应上述源码；Windows 离线脚本测试与 Linux Wiki 检查、Android 单测/Lint/构建均通过。
同一组用例在多个平台和运行中执行，不重复计为新增用例。

本页由发布后的文档提交记录最终结果；源码标签和安装包保持构建时的身份。
本版本 Release 的构建证据链接固定到保存本页的完整提交，避免未来文档更新改变证据含义。
