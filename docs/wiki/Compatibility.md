# 兼容范围

**v0.3.4 实现两条权限后端，仍待本版手机验收。** 安装成功、授权成功与真实重定向成功是不同检查项。

| 范围 | 接入与证据 |
| --- | --- |
| 官方 Shizuku | 官方 API / Provider `13.1.5`，已实现独立授权与 UserService；官方管理器真机流程仍待验 |
| Stellar | 页面“兼容服务（原生 API）”；原生授权、Binder 与 token UserService 已实现，本版真机流程待验 |
| Sui | 未测试，不作兼容承诺 |
| Android | `minSdk 26`（Android 8.0），`targetSdk / compileSdk 35`；版本门槛不等于 ROM 兼容 |
| Xiaomi / HyperOS 入口与菜单 | 需按型号、ROM、实际调用入口验证；当前版视觉与无障碍待验 |

v0.3.4 的业务协议为 `200`，服务代为 `30005`。官方 Shizuku、Stellar 原生、双管理器、
ADB 冷启动与全部异常分支均需本版实机证据；旧版本反馈不能替代这些检查。

## 链接匹配条件

目标必须是 `com.android.browser`，动作必须是 `ACTION_VIEW`，并且网址出现在 `Intent.data`。直接合法 HTTP/HTTPS 网址优先原样转交，支持裸中文域名。

无法直接使用时，解析器尝试已知包装参数 `url`、`u`、`uri`、`link`、`target`、`targeturl`、`redirect`、`redirect_url`、`q` 和百分号解码，最多递归深度 4，每层输入不超过 16,384 字符。它不会扫描任意 extras 或网页正文。

其他包、非 VIEW 动作、空 data 或无法解析的网址均放行。观察模式也始终放行。接管模式成功入队后会立即拒绝原启动；后续命令等待预算为 10 秒，失败或超时仅显示错误，无自动回退。该预算不保证操作系统的进程创建/清理调用都能被强制中止。

不修改 Android 的系统默认浏览器设置，不处理应用内 WebView。目标启动用 `--user current`，
没有原请求的用户身份，因此不承诺工作资料、系统分身或跨用户接管。

## 为什么同时开两个管理器会限制 Shizuku

自动模式优先 Stellar 原生路径，再回退 Shizuku。Stellar 可能同时投递 Shizuku 兼容 Binder，因而它的原生服务可用时，应用拒绝新的 Shizuku 会话，以免混淆停止时应调用的管理器。

要验证官方 Shizuku，先完成当前停服，再停止 Stellar。运行中的会话不会静默更换后端，后端选择器在运行与停服期间锁定。

## 系统接口边界

应用依赖隐藏的全局 `IActivityController`，要求特权进程能调用相应系统接口并拥有 `SET_ACTIVITY_WATCHER` 权限。ROM 可改变、限制或移除它；本项目不保证所有 HyperOS 版本可用。

接管期间不要同时运行 `adb shell am monitor`、Monkey 或其他 Activity Controller 工具，它们使用同一系统控制器位置。完整实现见[架构](Architecture.md)，失败时见[故障排查](Troubleshooting.md)。
