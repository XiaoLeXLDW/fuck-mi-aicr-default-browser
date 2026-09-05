# 开发说明

项目使用 Java 17、Android SDK 35 和 Gradle Wrapper 8.9，应用模块为 `app`，隐藏系统接口只在 `hidden-api-stub` 提供编译桩。当前版本 `0.3.1`、`versionCode 8`，应用 ID 为 `dev.codex.mibrowserredirector`。

## 获取与构建

克隆仓库后安装或指定 Java 17 和 Android SDK 35，通过 `ANDROID_HOME` 或本机 `local.properties` 指定 SDK；本机路径、SDK、缓存与签名文件不要提交。详细环境准备与签名参数以主仓库 `docs/BUILDING.md` 和 `scripts/` 为准。

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

Release 默认未签名；安装测试推荐构建 Debug，新克隆会生成项目内 `.local/debug.keystore`。`-LocalTestSigning` 只供持有原 `keys/redirector-test.jks` 的维护者显式复用历史测试证书；公开分发需配置自己的发布签名。不要把本地测试证书当成公开发布证书，也不要假定新证书能覆盖既有安装。

## 阅读顺序

1. 从[架构](Architecture.md)与 `MainActivity` 查看界面/daemon 的状态边界和浏览器草稿处理。
2. 阅读 `PrivilegeRuntime` 和两个 adapter，确认权限请求、会话归属与管理器 Binder 来源。
3. 阅读 `RedirectorUserService`、`UrlExtractor` 和系统接口适配，核对链接匹配与失败路径。
4. 阅读 `UserServiceStopper`、Binder helper 和现有测试，理解停止为何以 Binder 死亡为完成条件。

## 验证范围

本地测试覆盖 URL 解析、浏览器选择、后端选择/归属与停止辅助逻辑；Lint 和 Manifest 检查提供静态证据。这些检查不能验证目标 ROM 的隐藏 API、权限授权界面、真实超级岛链接、页面视觉与停止后的系统行为。

修改权限或生命周期后，分别执行官方 Shizuku、Stellar 原生与双管理器场景；修改 UI 后执行大字体、横屏/折叠与 TalkBack 场景。详细可填写清单位于主仓库 `docs/实机验收.md`，历史与当前构建记录位于 `docs/BUILD_EVIDENCE.md`。

## 文档与提交

Wiki 的可维护源文件在主仓库 `docs/wiki/`。修改页面时保留同级 `.md` 链接；发布时用 `scripts/export-wiki.ps1 -Repository owner/repo` 导出为实际仓库的绝对 Wiki 链接，再同步到单独的 Wiki 仓库。`owner/repo` 需替换为真实仓库名，导出器不执行网络写入。构建说明、发布清单与隐私说明在主仓库 `docs/`；贡献与问题反馈规则以仓库根目录文档和 Issues 模板为准。

提交中说明具体问题、最终行为和已执行检查；未完成的设备测试写明“未执行”。不要提交个人日志、UI 转储、签名私钥、工具缓存或带登录参数的示例网址。
