# 参与贡献

先阅读 [README](README.md)、[工作原理](docs/wiki/Architecture.md) 和
[构建说明](docs/BUILDING.md)。当前为实验项目，兼容报告应明确 App、ROM 和管理器版本。

## 报告问题

1. 使用 Bug 模板填写设备型号、Android / HyperOS 版本、权限后端和最短复现步骤。
2. 分清观察模式、接管模式和停服问题，写明预期与实际结果。
3. 贴脱敏后的“当前状态”和“最近事件”；测试网址使用 `https://example.com/`。
4. 上传截图或日志前移除真实网址、查询参数、账号、序列号、局域网地址和配对码。

安全问题见 [SECURITY.md](SECURITY.md)，不要在公开 Issue 中上传凭据或敏感复现数据。

## 提交代码

1. 从 `main` 建立分支，保持改动集中，说明要解决的具体问题。
2. 行为修改补充有意义的测试；界面修改附脱敏实机截图和屏幕/字体设置。
3. 运行以下检查，并在 PR 中区分本地通过与未执行的真机验证。

```powershell
.\scripts\verify-repository.ps1
.\scripts\verify-ui.ps1
.\scripts\build.ps1 -Variant Release
```

原生跨平台构建命令见 [BUILDING.md](docs/BUILDING.md)。不要提交密钥、SDK、缓存、日志、APK
或设备证据原件。不要移除上游版权和许可证声明。

## 需要保持的行为

- 拦截范围限于小米浏览器网页请求；观察模式继续放行。
- 权限后端会话固定来源；停服未确认前不能静默换后端。
- 只有确认 UserService Binder 死亡才能显示停止完成。
- 浏览器选择草稿不能因刷新或重连自动改变运行目标。

提交原创贡献即表示你有权按本项目 MIT 许可证提供该贡献；涉及上游派生文件时保留该文件的原许可证。
