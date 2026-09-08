# GitHub 推送与发布指南

当前仓库：[XiaoLeXLDW/fuck-mi-aicr-default-browser](https://github.com/XiaoLeXLDW/fuck-mi-aicr-default-browser)。
当前版本为 **v0.3.4 Pre-release**（code `11` / 协议 `200` / 服务代 `30005`），
包名为 `dev.codex.mibrowserredirector`。下载、签名和校验值见[版本说明](releases/v0.3.4.md)，
本地及远端验证状态见[构建证据](BUILD_EVIDENCE.md)。

Wiki 内容通过[仓库内文档](wiki/Home.md)阅读；启用独立 Wiki 的步骤见下文。
发布新版本时保留已发布版本的标签、附件与校验值，确保下载文件可追溯到对应源码。

## 1. 检查当前源码与文档

在项目根目录运行：

```powershell
.\scripts\verify-repository.ps1
.\tests\verify-stop.Tests.ps1
.\tests\verify-stop-process.Tests.ps1
.\tests\current-docs.Tests.ps1
.\tests\export-wiki.Tests.ps1
.\scripts\build.ps1 -Variant Release -LocalTestSigning
```

当前 Pre-release 沿用历史测试证书以保持升级连续性。`-LocalTestSigning` 要求本机已有
`keys/redirector-test.jks`；新克隆应按[构建与签名](BUILDING.md)使用 Debug 或未签名 Release。
测试签名不代表正式稳定版。设备验收按[实机清单](实机验收.md)独立记录，未测场景标“待验”。
版本字段检查只覆盖指定表格和首段，不能替代完整文案审核；另行核对功能描述、链接和远端发布信息。

## 2. 审阅并推送源码

```powershell
git remote -v
git status --short
git diff --check
git add --dry-run .
```

核对 `origin` 指向当前仓库。候选文件只应包含源码、文档、Wrapper 和配置；签名私钥、工具、
缓存、设备日志和 `dist/` 产物留在本地。仓库检查覆盖常见凭据格式及候选路径，不扫描已有 Git 历史。

```powershell
git add .
git diff --cached --stat
git diff --cached --check
git commit -m "Release v0.3.4 with revised documentation and Wiki export"
git push origin main
```

推送后检查 Actions 的 `Android checks`。该工作流运行 Debug / Release 单测、Lint、构建及离线
PowerShell 回归，不持有发行密钥、不自动创建 Release。只有实际完成的对应提交运行才能记为 CI 通过。

## 3. 发布可安装 APK

1. 从将要打 tag 的源码构建，核验包名、版本、签名证书指纹、APK 大小与 SHA-256。
2. 将已签名产物命名为 `MiBrowserRedirector-v0.3.4-universal.apk`，同步生成同名 `.sha256`。
3. 在对应源码提交创建 `v0.3.4` 标签，以[本版说明](releases/v0.3.4.md)为正文发布 **Pre-release**，附 APK 和校验文件。
4. 回下载发布附件，核对大小、SHA-256、签名和标签指向；保留对应源码与第三方许可证。
5. 更新 README、快速开始、更新记录与构建证据，使下载入口和本版验证边界一致。

不要把静态构建通过写成双管理器或所有 ROM 真机兼容。若未来更换正式证书，需先处理 Android
覆盖安装的签名关系。已发布版本的更正通过新版本交付，不移动旧标签或覆盖旧附件。

## 4. 维护 Wiki 与仓库展示

Wiki 的可维护源文件在 [docs/wiki](wiki/Home.md)。本地预览：

```powershell
.\scripts\export-wiki.ps1
```

若将来启用独立 GitHub Wiki，先在网站创建初始页面，再按
[GitHub Wiki 文档](https://docs.github.com/en/communities/documenting-your-project-with-wikis/adding-or-editing-wiki-pages)
准备独立的 `.wiki.git` 仓库。运行：

```powershell
.\scripts\export-wiki.ps1 -Repository XiaoLeXLDW/fuck-mi-aicr-default-browser -SourceRevision HEAD
```

`-SourceRevision` 默认 `HEAD`，也可指定待发布的标签或提交，导出时解析为完整 SHA。
同级页面链接指向 Wiki，跨目录文档和资源指向该源码提交；先确认对应提交已推送到主仓库。
审阅 `dist/wiki/` 的导出内容后再同步到 Wiki 克隆。导出器只写文件，不自动提交或推送。

展示名称使用“岛外打开 · Mi Browser Redirector”。About 可使用：

> 实验性小米浏览器重定向工具：通过 Shizuku / Stellar 将系统交给小米浏览器的网页转到指定浏览器。

社交预览图为 [social-preview.png](assets/branding/social-preview.png)，尺寸 1280 × 640，需在仓库设置的
Social preview 中上传；提交图片不会自动更新该设置。图标来源与重建方法见[品牌与资源](BRANDING.md)。
