# 构建与验收证据

## GitHub 首次远端验证（2026-09-05）

源码已推送至 [XiaoLeXLDW/fuck-mi-aicr-default-browser](https://github.com/XiaoLeXLDW/fuck-mi-aicr-default-browser)，
分支为 `main`。代码提交 `a0947c3ae27ca4c3af752dba4a8bd309b81734a1` 的
[Android checks 第 2 次运行](https://github.com/XiaoLeXLDW/fuck-mi-aicr-default-browser/actions/runs/33948990113)
已成功完成。

- GitHub Ubuntu 24.04 上的仓库检查、UI 检查、Debug / Release 单测、Lint 和 APK 构建均通过。
- 远端 Gradle 报告 `BUILD SUCCESSFUL in 1m 48s`。
- 已上传 `android-checks-2` 构建附件，包含开发 APK、未签名 Release 和检查报告；保留期为 14 天。
- 首次运行发现 Linux 隐藏文件读取差异，已为仓库检查的 `Get-Item` 添加 `-Force`；第 2 次运行
  确认修复。Gradle 使用 `basic` 缓存。

这次远端构建仍没有使用正式发行密钥或连接真实手机，不能代替实机兼容验收。
以下保留本地整理阶段及更早版本的原始记录，其中“尚未推送/远端 CI 未运行”是当时的状态。

## 本地 GitHub 仓库整理验证（2026-09-05）

当前源码仍为 `v0.3.1 / versionCode 8`，UserService 协议 `200`、服务代 `30002`。
本轮修改构建、脚本、文档和许可归属，未修改接管运行逻辑。

### 本地已验证

| 检查 | 结果 |
|---|---|
| Debug 与 Release 单测 | 各 28 tests，0 failure / error / skipped |
| Debug 与 Release Lint | 各 0 error / 9 warnings；不是零警告构建 |
| UI / 脚本 / 文档 | 固定 72dp 结构检查通过；PowerShell 语法、候选文件保护和本地 Markdown 链接通过 |
| Wrapper / 许可 | 固定 Gradle 8.9 JAR 与发行包校验值；三个 APK 内五份许可文本均与源码对应文件一致 |
| Wiki / 工作流 | 9 页 Wiki 本地导出与仓库地址转换通过；4 份 YAML 语法解析通过，远端 Actions 尚未运行 |

完整运行：

```powershell
.\scripts\bootstrap.ps1 -SkipSdkPackages
.\scripts\build.ps1 -Variant Debug
.\scripts\build.ps1 -Variant Release
.\scripts\build.ps1 -Variant Release -LocalTestSigning
.\scripts\verify-repository.ps1
.\scripts\verify-ui.ps1
.\scripts\export-wiki.ps1
```

在项目内建立不含 `keys/`、`.local/` 的隔离源码副本，执行 `:app:validateSigningDebug` 和
`:app:assembleRelease`，成功创建开发密钥并构建未签名 Release。该测试**复用了项目已有 SDK 和
Gradle 缓存**，不代表冷缓存下载、所有平台或远端 CI 已验证。

另外验证了两个拒绝路径：安装脚本拒绝未签名 APK，未调用 ADB；只有部分签名环境变量时构建
立即失败，未启动 Gradle。停服脚本已改为读取当前版本，并拒绝将 ADB 断线当作进程消失；
后者进行了静态复核，未运行真实设备停服测试。

### 本轮产物

全部产物仅在本地 `dist/`，由 Git 忽略，尚未作为 GitHub Release 发布。

| 文件 | 大小（bytes） | SHA-256 |
|---|---:|---|
| `MiBrowserRedirector-debug-debug-signed-v0.3.1.apk` | 149475 | `aae9a303dde8741e3cda35444e59ca93066e9c7416d8d291af9a8556cd142268` |
| `MiBrowserRedirector-release-unsigned-v0.3.1.apk` | 108196 | `0e06305531fdcd92fa63e0a005e4db6ae4d88321b11ea5ccef552ce0f90afd8b` |
| `MiBrowserRedirector-release-local-test-v0.3.1.apk` | 112292 | `82e65668e246a709c8a808aa3f1a665ca3f969e69a33edb23e55823258289693` |

三个 `.sha256` sidecar 均与 APK 一致；签名包验证通过，unsigned 已确认未签名，不能直接安装。
本轮 `local-test` 与历史 `release-v0.3.1.apk` 的证书 SHA-256 一致：
`24211beec19c121f4135640e9c95c75c5b981726b3172b7658e169f28894d376`。
原测试密钥与历史 APK 文件未改变。正式维护者签名路径尚未使用正式密钥验证。

### 尚未执行

未连接或操作手机，未做 v0.3.1 Shizuku / Stellar 真实授权、跳转、停服、重开及菜单视觉验收。
未推送 GitHub、未运行远端 Actions、未发布 Wiki 或 Release。详见[发布指南](GITHUB_RELEASE_CHECKLIST.md)
与[实机验收](实机验收.md)。

以下是**历史记录**。其中的旧构建命令、旧产物名、“许可证缺失”及 HOLD 等状态仅表示当时情况；
当前构建和发布流程以上文及[构建说明](BUILDING.md)为准。历史实机证据不自动继承到当前版本。

---

## v0.3.1 浏览器菜单更新（2026-09-05）

当前 APK：`dist/MiBrowserRedirector-release-v0.3.1.apk`，`98,820 bytes`。

```text
SHA-256: 9e3d26ca7e83f1a0998c02f14d088d092232f44ea21e70380a466359d1952015
versionCode: 8
versionName: 0.3.1
UserService: v0.3.1 / protocol 200 / generation 30002
```

本次将浏览器下拉框改为图标单选弹窗，菜单草稿与运行目标分别存储。保留原双后端与停止
实现，仅递增服务版本标记和服务代，避免覆盖安装后误把旧进程识别成当前构建。

最终运行 `scripts/build.ps1 -Variant Release`：

- Gradle `BUILD SUCCESSFUL in 54s`；28/28 单测通过，0 failure / 0 error / 0 skipped。
  新增 8 项选择测试覆盖包名持久选择、排序/改名、默认浏览器、卸载后不自动换目标、重装恢复、
  空列表及同名包区分；原 20 项 URL/停服/权限后端测试继续通过。
- Android Lint：0 error / 10 warning，与 v0.3.0 数量相同；本次没有遗留新增警告。
  菜单标题的 accessibilityHeading 仅在 API 28+ 生效，较老版本忽略该属性。
- `verify-ui.ps1` 确认固定 72dp 顶部距、滚动裁切和更新后的菜单 View ID；五个 PowerShell
  脚本均通过 AST 语法解析。
- APK 包名、版本、双 Provider 与权限 metadata 检查通过；APK v2 签名通过。
  与 v0.3.0 的签名证书 SHA-256 相同：
  `24211beec19c121f4135640e9c95c75c5b981726b3172b7658e169f28894d376`。
- 旧版 v0.3.0 APK 保持原哈希，未被覆盖；新版哈希 sidecar 与文件核对一致。

**真机未验：** 本轮未连接手机或运行 Android 模拟器。弹窗视觉、滚动/大字体/分屏、取消与
刷新/重开后的实际交互、TalkBack、官方 Shizuku 和 Stellar 真机回归均不能用上述静态结果
代替。手动验收步骤见 `docs/实机验收.md`。现有测试签名和 GitHub 首发 HOLD 状态不变。

## v0.3.0 历史构建记录

记录时间：2026-09-04。本文只把本机静态构建结果标为 GREEN；官方 Shizuku 与 Stellar 的
v0.3.0 真机行为仍是 RED/待验，不能用静态结果代替。

## Release 产物

- 文件：`dist/MiBrowserRedirector-release-v0.3.0.apk`
- 大小：`89,296 bytes`（`87.20 KiB`）
- SHA-256：`5a36559dff14ac4a168b8f71de38416fcbf6f087a15cc92652796315ab5fd577`
- 包名：`dev.codex.mibrowserredirector`
- 版本：`versionCode 7` / `versionName 0.3.0`
- SDK：`minSdk 26` / `targetSdk 35` / `compileSdk 35`
- 签名：APK Signature Scheme v2 验证通过，1 个签名者；当前仍是仓库内测试证书，不是正式
  GitHub Release 证书。

对应 `.sha256` sidecar 由 `scripts/build.ps1` 在每次构建后重写。

## 静态 GREEN

2026-09-04 运行：

```powershell
.\scripts\build.ps1 -Variant Release
```

结果：

- Gradle `BUILD SUCCESSFUL in 52s`。
- Release 单测 `20/20`，`0 failure`、`0 error`、`0 skipped`。
- Android Lint：`0 error`、`10 warning`；警告为翻译、备份配置、背景 overdraw、依赖更新和
  两处有意同步落盘的 SharedPreferences，不影响当前双后端协议检查。
- `verify-ui.ps1`：固定 `72dp` 顶部安全距、滚动裁切和全部控制器 View ID 通过。
- `bootstrap.ps1`、`build.ps1`、`install.ps1`、`verify-stop.ps1`、`verify-ui.ps1` 均通过
  PowerShell AST 语法解析。

## 双后端打包证据

最终 APK 的 Manifest 已确认同时包含：

- Stellar 原生 Provider：`dev.codex.mibrowserredirector.stellar.NativeStellarProvider`，authority
  为 `dev.codex.mibrowserredirector.stellar`。
- 官方 Shizuku Provider：`rikka.shizuku.ShizukuProvider`，authority 为
  `dev.codex.mibrowserredirector.shizuku`。
- `moe.shizuku.manager.permission.API_V23` 权限与
  `moe.shizuku.client.V3_SUPPORT=true` metadata。
- Shizuku `api/provider 13.1.5` 和两套独立 adapter；AUTO 优先原生路径，服务会话保存创建它的
  后端，停止时不会静默切换。

两个 UserService 身份分开：Stellar 使用进程后缀 `redirector` 和原生 token；Shizuku 使用
`redirector_shizuku`、稳定 tag `mi-browser-redirector` 和官方 `UserServiceArgs`。两者共用协议
`200`、服务代 `30001`，但不能同时持有全局 Activity Controller。

## 停止契约检查

Shizuku 13.1.5 官方契约规定 destroy 事务码为 `16777115`，AIDL 中写 `16777114`。最终 APK
DEX 已确认：

- 常量是 `0x00ffff9b`（十进制 `16777115`）；
- 调用前写入真实 Binder interface descriptor；
- `transact` 使用 `FLAG_ONEWAY`；
- 管理器 remove 后仍会轮询 Binder 生死，只有确认死亡才显示停止完成。

Shizuku session 还保存创建时的 manager Binder。若进程级 Shizuku Binder 被另一管理器替换，
会拒绝向非来源管理器执行 remove，再使用已持有的 UserService Binder 完成 disable/destroy。
检测到 Stellar 原生 Binder 时，新 Shizuku 会话会被阻止，以免把 Stellar 兼容层误当官方
Shizuku。停服 worker 还有进程级 owner/epoch：页面旋转或重开时，新 Activity 只能观察正在
执行的停服；旧异步回调失去所有权后不能再覆盖新会话状态。

## 真机证据边界

历史 v0.2.0 Stellar 真机结果只证明旧版原生链路曾完成以下行为：观察到真实网址、Firefox
接管成功、停止按钮确认 Binder 死亡、重开 App 未自动重建 daemon。它不能证明 v0.3.0 回归，
更不能证明官方 Shizuku 路径。

当前无可用 ADB 设备，因此以下仍未执行：

1. 仅官方 Shizuku：授权、观察、接管、停止、重开。
2. 仅 Stellar：原生 token 路径的 v0.3.0 回归。
3. 两者同时运行：AUTO 选择、显式 Shizuku 冲突拒绝、停止后切换。
4. 小米超级岛真机上的 `72dp` 顶距视觉确认。

完整步骤见 `docs/实机验收.md`。在上述矩阵、正式签名、根许可证和 Application ID 决策完成
前，GitHub 正式发布状态仍为 **HOLD**。
