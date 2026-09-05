# 文档导航

首次使用从[快速开始](wiki/Getting-Started.md)进入。当前版本是 **v0.3.1**；双后端已实现，当前版本实机兼容性与菜单视觉验收仍待完成。

| 要做什么 | 阅读位置 |
| --- | --- |
| 安装、授权、选择浏览器、停止接管 | [快速开始](wiki/Getting-Started.md) · [故障排查](wiki/Troubleshooting.md) |
| 判断设备/权限后端是否适用 | [兼容范围](wiki/Compatibility.md) · [协议与历史依据](SHIZUKU_COMPATIBILITY.md) |
| 了解数据处理与反馈前脱敏 | [隐私说明](PRIVACY.md) |
| 编译、理解结构、参与改进 | [构建与签名](BUILDING.md) · [开发说明](wiki/Development.md) · [架构](wiki/Architecture.md) |
| 验证或准备发布 | [构建证据](BUILD_EVIDENCE.md) · [实机验收](实机验收.md) · [发布清单](GITHUB_RELEASE_CHECKLIST.md) |

## Wiki 源文件

[`wiki/`](wiki/Home.md) 保存可独立阅读的 Wiki 页面。页面间使用同级 Markdown 链接，`_Sidebar.md` 与 `_Footer.md` 分别提供 GitHub Wiki 导航和页脚。主仓库中的这些文件不会自动出现在 GitHub 的 Wiki 标签页，需要单独同步到 Wiki 仓库。

发布时使用 `scripts/export-wiki.ps1 -Repository owner/repo`，将 `owner/repo` 替换为实际仓库名。导出器在 `dist/wiki/` 生成页面并转换为目标仓库的绝对 Wiki 链接；不加 `-Repository` 只生成本地预览。脚本不执行 Git 推送，后续步骤见[发布清单](GITHUB_RELEASE_CHECKLIST.md)。

维护时以当前源码为准；历史哈希、旧版测试结论与当前结果分开记录。未执行的真机步骤保留“待验”，不要用 APK 签名、单测通过或文档截图替代实际行为证据。
