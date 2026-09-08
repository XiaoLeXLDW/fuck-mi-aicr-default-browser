# 岛外打开 · Wiki

“岛外打开”（Mi Browser Redirector）将系统明确交给小米浏览器 `com.android.browser` 的可解析 HTTP/HTTPS 链接，转交给你选择的浏览器。主要面向 Xiaomi / HyperOS 的超级岛、超级小爱等调用入口，无需 Root，需启动并授权 Shizuku 或 Stellar。

**当前版本为 v0.3.4 Pre-release，仍待本版手机验收。** 官方 Shizuku、Stellar 原生、冷启动、
菜单视觉与异常恢复需分别验证，旧版本反馈和静态构建通过不能替代本版实机结果。

本页是仓库内可维护的 Wiki 文档。当前通过源码仓库阅读这些页面，独立 GitHub Wiki 未启用。

| 你想做什么 | 从这里开始 |
| --- | --- |
| 安装并尝试接管 | [快速开始](Getting-Started.md) |
| 确认设备和服务是否适用 | [兼容范围](Compatibility.md) |
| 授权失败、链接未跳转或停止未完成 | [故障排查](Troubleshooting.md) |
| 了解网址与日志如何处理 | [隐私说明](Privacy.md) |
| 阅读源码、构建或参与维护 | [架构](Architecture.md) · [开发说明](Development.md) |

## 它处理什么

只处理目标包为 `com.android.browser`、动作是 `ACTION_VIEW`、`Intent.data` 可提取合法 HTTP/HTTPS 网址的启动事件。观察模式只报告并放行；接管模式提交转交任务后阻止原小米浏览器启动。

它不修改系统默认浏览器设置，也不处理应用内 WebView 或改写网页内容。没有网址的启动、其他包、其他动作和无法解析的数据会放行；接管任务入队后的目标浏览器启动失败没有自动回退。

页面“兼容服务（原生 API）”是 Stellar 原生后端的称呼，不是官方 Shizuku Manager。两套管理器的
下载来源与授权步骤见[快速开始](Getting-Started.md)。

## 退出方式

点“停用并退出服务”，等待“停止完成”与 UserService Binder 死亡确认。从最近任务划掉页面不会自动退出 daemon；重开页面也不能替代明确停用。

本项目与 Xiaomi、Shizuku、Stellar 无官方隶属关系。源码采用 MIT，第三方组件保留各自许可证与署名。
