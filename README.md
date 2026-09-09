<p align="center">
  <img src="app/src/main/res/drawable-nodpi/brand_app_icon.png" width="112" height="112" alt="岛外打开 App 图标">
</p>

# 岛外打开 · Mi Browser Redirector

让小米系统交给小米浏览器的网页，改由你选择的浏览器打开。
界面跟随系统自动切换浅色与深色模式。

[下载 APK](https://github.com/XiaoLeXLDW/fuck-mi-aicr-default-browser/releases/tag/v0.3.5) ·
[问题反馈](https://github.com/XiaoLeXLDW/fuck-mi-aicr-default-browser/issues) ·
[自动构建](https://github.com/XiaoLeXLDW/fuck-mi-aicr-default-browser/actions/workflows/android.yml)

**当前版本：v0.3.5 正式版。** 面向 Xiaomi / HyperOS 的 Android 工具。
已在小米 17 Ultra、系统 `3.0.309.0.WPACNXM.C11`、Stellar 环境下测试成功。
最低安装版本为 Android 8.0（minSdk 26），不代表所有机型和 ROM 都能接管。

应用通过 **Shizuku 或 Stellar** 提供的特权服务观察网页启动请求，支持 ADB 启动权限服务，
无需 Root；本项目与 Xiaomi、Shizuku、Stellar 均无官方隶属关系。

## 开始使用

1. 从 [GitHub Release](https://github.com/XiaoLeXLDW/fuck-mi-aicr-default-browser/releases/tag/v0.3.5) 下载已签名 universal APK 与同名 `.sha256`，核对 APK 的 SHA-256 与校验文件一致后安装；安装一个可通用处理 HTTP 与 HTTPS 的目标浏览器。
2. 安装并启动 [Shizuku](https://shizuku.rikka.app/zh-hans/download/) 或 [Stellar](https://github.com/roro2239/Stellar/releases)。回到本应用选择后端；“Stellar（原生 API）”指 Stellar，自动模式优先 Stellar。[启动与授权说明](docs/FAQ.md#权限服务怎么启动和授权)。
3. 选择目标浏览器，保持“观察模式”，点“开启接管”并完成所选管理器授权；从真实超级岛/超级小爱入口触发公开测试网址。
4. 确认最近事件出现“观察到可接管网址”后，关闭观察模式，**再次点“开启接管”**使设置生效；重复触发并检查目标浏览器实际显示页面。
5. 使用结束后点“停用并退出服务”，等待“停止完成”；划掉最近任务或关闭页面不等于停止后台服务。

观察模式继续打开小米浏览器是预期行为。目标浏览器选择保存为草稿，也要再次点“开启接管”才应用。
切换权限后端前必须停止当前服务；如果显示“停止未完成”，按[恢复步骤](docs/FAQ.md#停止未完成怎么恢复)处理。

## 适用范围

- 只接管系统交给小米浏览器、能提取到网页地址的链接；不修改系统默认浏览器，也不处理应用内嵌网页。
- 观察模式会继续打开小米浏览器；正式接管后若目标浏览器启动失败或超时，**不会自动回退小米浏览器**。
- 依赖系统特权接口，可能受 ROM 限制或与其他监控工具冲突；工作资料、系统分身和跨用户场景未验证。
- 最近事件通常仅展示域名，但完整网址会短暂进入内存和启动参数，系统或浏览器可能记录它；应用没有遥测或上传功能，具体留存与诊断边界见[隐私说明](docs/PRIVACY.md)。

当前 APK 沿用历史测试证书。覆盖安装要求包名与签名兼容；换正式证书前需处理升级关系。
更新说明与安装包见 [GitHub Release](https://github.com/XiaoLeXLDW/fuck-mi-aicr-default-browser/releases/tag/v0.3.5)，校验值在同名 `.sha256` 附件中；文件校验不能代替手机验收。

## 从源码构建

Windows：准备 PowerShell 7 与 JDK 17，在项目根目录运行：

```powershell
.\scripts\bootstrap.ps1
.\scripts\build.ps1 -Variant Debug
```

首次需要联网下载 Android SDK、Gradle 和 Maven 依赖，工具及缓存保存在项目内。
Debug 可供自行测试，Release 默认未签名；CI 不持有发行密钥、不自动发布。
跨平台命令、测试、签名与验收见下方构建文档。

## 项目导航

| 内容 | 入口 |
| --- | --- |
| 授权、兼容、特殊场景与故障恢复 | [FAQ](docs/FAQ.md) |
| 构建、测试、架构、发布与实机验收 | [BUILDING](docs/BUILDING.md) |
| 数据处理与敏感信息 | [PRIVACY](docs/PRIVACY.md) · [SECURITY](SECURITY.md) |
| 参与贡献与版本历史 | [CONTRIBUTING](CONTRIBUTING.md) · [CHANGELOG](CHANGELOG.md) |
| 许可证与第三方来源 | [MIT](LICENSE) · [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md) |

## 许可证与致谢

本项目原创代码和文档采用 MIT；上游接口与依赖保留各自许可证，全文见第三方声明。
感谢 [Shizuku](https://github.com/RikkaApps/Shizuku-API)、
[Stellar](https://github.com/roro2239/Stellar-API) 和
[Android Open Source Project](https://android.googlesource.com/platform/frameworks/base/)。
