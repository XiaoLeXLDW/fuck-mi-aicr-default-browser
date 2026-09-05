# GitHub 推送与发布指南

源码整理、Wiki 发布和 APK 发行为三个独立动作。当前源码版本 `0.3.1 (8)`，包名保留
`dev.codex.mibrowserredirector`；原创代码采用 MIT。不要把静态检查通过写成双后端真机兼容已通过。

## 1. 源码提交前

```powershell
.\scripts\verify-repository.ps1
.\scripts\build.ps1 -Variant Release
git status --short
git add --dry-run .
```

检查应通过，候选文件应仅含源码、文档、Wrapper 与配置。私钥、`.tools/`、缓存、设备附件及
`dist/` 被忽略，既有历史 APK 留在本地。检查脚本覆盖当前候选文件的常见凭据格式和路径，
不替代人工审阅，也不扫描已有 Git 历史。完整本地结果见[构建证据](BUILD_EVIDENCE.md)。

仓库名可沿用当前目录名；展示名使用“岛外打开 / Mi Browser Redirector”。推荐仓库描述：

> 实验性小米浏览器重定向工具：通过 Shizuku / Stellar 将系统交给小米浏览器的网页转到指定浏览器。

Topics 可填写 `android`、`hyperos`、`xiaomi`、`shizuku`、`stellar`。

## 2. 首次推送源码

在 GitHub 创建**空仓库**，不要额外生成 README、LICENSE 或 `.gitignore`。复制它提供的远端地址。
本地已经初始化时不需要再次 `git init`；全新目录可使用 `git init --initial-branch=main`。

```powershell
# 把下面地址换成刚创建的仓库；先确认 git remote -v 没有同名 origin。
$repoUrl = Read-Host 'GitHub 仓库 HTTPS 或 SSH 地址'
git add .
git diff --cached --stat
git diff --cached --check
```

确认暂存内容后再创建提交并推送：

```powershell
git commit -m "Prepare project documentation and reproducible builds"
git remote add origin $repoUrl
git push -u origin main
```

如已有 `origin`，先检查其地址，不要直接覆盖。Git 身份和 GitHub 登录由维护者在自己的终端配置。
推送后查看 Actions 的 `Android checks` 工作流；只有实际完成的远端运行才能记为 CI 通过。

## 3. 发布 Wiki

Wiki 源文件在 [docs/wiki](wiki/Home.md)，在普通源码仓库内即可阅读。
GitHub Wiki 使用单独的 `.wiki.git` 仓库；需先在网站 Wiki 创建并保存一个初始页面，才可克隆。
此流程依据 [GitHub 官方 Wiki 文档](https://docs.github.com/en/communities/documenting-your-project-with-wikis/adding-or-editing-wiki-pages)。

本地预览，无网络操作：

```powershell
.\scripts\export-wiki.ps1
```

准备发布时，在项目根目录运行；`$repository` 填实际的 `owner/repo`：

```powershell
$repository = Read-Host 'GitHub owner/repo'
git clone "https://github.com/$repository.wiki.git" .local/wiki
.\scripts\export-wiki.ps1 -Repository $repository -OutputDirectory .local/wiki -Force
git -C .local/wiki diff --stat
git -C .local/wiki status --short
```

导出只更新已准备的 Markdown 页面，不删除 Wiki 其他页面、不提交、不推送。
`-Repository` 会把同级 `.md` 链接转换成实际 Wiki 绝对地址，避免源码浏览与 Wiki 地址规则的差异。
审阅后发布：

```powershell
git -C .local/wiki add -- '*.md'
git -C .local/wiki commit -m "Publish project wiki"
git -C .local/wiki push
```

`_Sidebar.md` 和 `_Footer.md` 会成为 Wiki 导航；若已有本地 Wiki 克隆，先检查并同步其远端状态，
不要再克隆覆盖。今后先维护 `docs/wiki/`，再导出，避免两份内容不一致。

## 4. APK 首发

源码与 Wiki 可以按实验项目公开。对外发布可安装 APK 前，需要完成以下事项：

- [ ] 确定并备份维护者的长期签名密钥，明确与历史测试签名的升级关系。
- [ ] 按[实机矩阵](实机验收.md)记录当前版本实际结果；未测场景标“未验证”，不写全兼容。
- [ ] 从将要打 tag 的提交构建，核验包名、版本、签名证书指纹和 SHA-256。
- [ ] 发布已签名 APK、同名 `.sha256`，同时提供对应源码与第三方许可；不发布私钥或测试日志。
- [ ] 首发标记 **Pre-release**，用[版本说明草稿](releases/v0.3.1.md)填写该实际产物的证据。

当前 Actions 只构建未签名包，不使用发布凭据、不创建 Release。`signed` 文件名只表示使用了传入的
签名配置，维护者仍需核实证书身份。不要上传旧测试证书 APK 并称为正式稳定版。
