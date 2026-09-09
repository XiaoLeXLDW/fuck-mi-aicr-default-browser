# 常见问题

基本使用步骤见[README](../README.md)。本页面向 v0.3.5 正式版。
已在小米 17 Ultra、系统 `3.0.309.0.WPACNXM.C11`、Stellar 环境下测试成功。

## 权限服务怎么启动和授权

| 管理器 | 来源与启动方式 |
| --- | --- |
| 官方 Shizuku | [官方下载](https://shizuku.rikka.app/zh-hans/download/)；按[官方手册](https://shizuku.rikka.app/zh-hans/guide/setup/)启动。Android 11 及以上可用无线调试配对；较旧系统无 Root 时使用电脑 ADB，已有 Root 也可按管理器指引启动 |
| Stellar | [上游 Releases](https://github.com/roro2239/Stellar/releases)；按管理器内指引启动，不把 Shizuku 的启动命令直接用于 Stellar。本应用称其为“Stellar（原生 API）” |

安装管理器、完成配对都不等于服务已经运行。ADB 启动通常需在手机重启后重新执行。
先在管理器确认服务运行，再回到本应用选择后端、目标浏览器和观察模式，点“开启接管”。
按提示授权；Stellar 原生路径申请 `stellar` 基础权限，不需要额外开启跟随启动或无障碍。

若没有弹窗或此前拒绝授权，在**当前选中的管理器**授权列表中检查“岛外打开”，然后刷新并重试。
应用不会静默换另一后端绕过拒绝。反馈问题时可展开“诊断信息”查看服务详情。

<details>
<summary>技术细节：服务身份</summary>

诊断信息包含与所装 APK 一致的版本、协议、服务代和 UID/PID。
v0.3.5（code 12）对应协议 `200`、服务代 `30006`。“服务 API 103”等值来自管理器，不是业务协议。

</details>

## 两个管理器能同时开吗

自动模式优先 Stellar 原生路径，否则选 Shizuku。检测到 Stellar 原生服务时会拒绝新建或
重新附着 Shizuku 会话：Stellar 也可能投递 Shizuku 兼容 Binder，单靠该 Binder 无法可靠区分来源。
页面上的名称也不能证明连接了官方 Shizuku Manager，应看实际运行状态。

验证官方 Shizuku 时，先在本应用停止当前会话并确认完成，再停止 Stellar，最后启动并选择官方
Shizuku。运行或停服期间后端选择器锁定；一次会话固定归属于创建它的后端，不能同时接管。
Stellar 的 Shizuku 兼容层不等于只使用 Stellar AIDL 的旧客户端也能反向连接官方 Shizuku。

## 哪些设备和链接能用

| 范围 | 当前边界 |
| --- | --- |
| Android / ROM | Android 8.0 及以上；能安装不等于系统允许接管 |
| 官方 Shizuku | 已实现独立授权与 UserService；本版独立真机测试结果尚未记录 |
| Stellar | 小米 17 Ultra、系统 `3.0.309.0.WPACNXM.C11` 实机测试成功 |
| Sui / 其他 ROM | 未测试，不作兼容承诺 |
| 工作资料 / 系统分身 | 不承诺跨用户接管 |

只接管交给小米浏览器、能提取到网页地址的链接，不修改系统默认浏览器，也不处理应用内嵌网页。
不要同时运行 `am monitor`、Monkey 或其他 Activity Controller 工具，以免接管冲突。

<details>
<summary>技术细节：兼容接口与网址解析</summary>

minSdk 为 26，targetSdk / compileSdk 为 35。ROM 必须允许隐藏接口 `setActivityController`
和 `SET_ACTIVITY_WATCHER`；官方 Shizuku API / Provider 版本为 `13.1.5`。
目标启动使用 `--user current`，没有原请求用户身份。

只处理 `com.android.browser` 的 `ACTION_VIEW`，且网址必须位于 `Intent.data`。
直接合法 HTTP/HTTPS 网址优先原样转交，支持裸中文域名；否则尝试包装参数 `url`、`u`、`uri`、
`link`、`target`、`targeturl`、`redirect`、`redirect_url`、`q` 和百分号解码。
最多递归深度 4，每层输入不超过 16,384 字符；不扫描任意 extras 或网页正文。

其他包、非 VIEW、空 data、无法解析的地址和观察模式均放行。不修改系统默认浏览器，不处理
应用内 WebView。Controller 是全局接口，可能被其他工具覆盖且无法可靠查询所有权。

</details>

## 为什么找不到浏览器或仍打开旧目标

菜单只接受同时声明通用 HTTP 与 HTTPS 入口的小米浏览器以外的浏览器，排除受域名、路径或
MIME 限制的入口。安装或启用合适浏览器后点“刷新状态”。原目标卸载或禁用时需手动重新选择，
不会自动切到第一项；无候选时不能开启。

菜单显示图标、名称、包名和选中标记，同名应用用包名区分。选择保存为草稿，刷新或重开保留，
**再次点“开启接管”才更新运行目标**；取消、返回或点外部不改变选择。
观察模式同样要关闭后再次点“开启接管”才生效。观察到网址但继续打开小米浏览器是观察模式的预期行为。

顶部保留 `72dp` 最小安全距并结合系统栏/刘海 Insets；大字体、横屏、折叠与 TalkBack 的实际
效果仍需设备确认，具体方法见[实机验收](BUILDING.md#实机验收)。

## 已拦截为什么还没打开网页

| 状态 | 能得出的结论与下一步 |
| --- | --- |
| 观察到可接管网址 | URL 捕获成功，仍会放行；关闭观察模式并再次开启后复测 |
| 已拦截，准备交给目标浏览器 | 任务已入队，尚不能证明浏览器打开成功 |
| 已打开目标包 | `am start` 返回成功；仍需目视确认目标浏览器、页面和网络加载 |
| data 为空 / 非 VIEW / 无有效网址 | 入口不符合接管条件；记录入口，用同功能中提供公开网址的入口复测 |
| 启动失败或超时 | 原启动已被阻止，**没有自动回退**；先检查目标浏览器是否能手动打开，必要时停用接管恢复原跳转 |

如果完全没有捕获事件，先确认入口真正启动的是 `com.android.browser`，并排除其他 Controller
覆盖。命令等待预算为 10 秒，但操作系统的进程创建/清理调用不保证可强制中止。

## 安装或注册 Controller 失败

未签名 Release 不能直接安装；覆盖安装须满足包名、版本与证书要求。先核对 APK 来源、校验值
和签名指纹，不为处理签名冲突直接卸载已有数据。[构建与签名](BUILDING.md#release-与签名类型)
说明开发签名、历史测试签名与正式签名的区别。

“无法注册 Controller”或缺少 `SET_ACTIVITY_WATCHER` 表示 ROM 或当前权限身份不允许该接口。
记录设备型号、完整 ROM、管理器版本和脱敏错误；管理器授权成功不代表系统接口可用。

## 停止未完成怎么恢复

正常停止应显示“停止完成”；展开“诊断信息”可检查版本、停止步骤与“已确认 UserService Binder 死亡”。只关闭页面或从最近
任务划掉不会停止 daemon；进程消失也不能单独证明 Controller 与原链接行为恢复。

1. 保存当前状态和最近事件，确认原会话属于 Shizuku 还是 Stellar，保持后端不变。
2. 原管理器仍可用、旧调用已结束且按钮允许时，再点“停用并退出服务”。超时只表示未确认；同步 Binder 调用可能仍在进行，此时不能重复清理或重新开启。
3. 仍未完成时，在能接受其他依赖应用一同中断的情况下，停止对应管理器的服务；必要时自行重启设备。
4. 恢复后检查原链接行为，重新打开“岛外打开”应保持停用，再决定是否重新开启。

有 ADB 时可补充查询进程，正常停用后两个查询均应无 PID：

```text
adb shell pidof dev.codex.mibrowserredirector:redirector
adb shell pidof dev.codex.mibrowserredirector:redirector_shizuku
```

第一个是 Stellar，第二个是 Shizuku。不要用 `am monitor` 排查正在运行的接管，以免覆盖控制器。
停止协议、受控故障测试与 ADB 辅助验证见[BUILDING](BUILDING.md)。

## 怎么报告问题

用 `https://example.com/` 等公开网址复现，记录 APK 版本/来源、设备/ROM、实际后端及管理器版本、
目标浏览器版本、入口和最短步骤，分清观察、接管与停服问题。附期望/实际行为及最小脱敏状态片段，
未测试场景写“未执行”。截图和日志可能含网址、token、账号、设备序列号和无线调试地址，
分享前按[隐私说明](PRIVACY.md)检查；安全问题按[SECURITY](../SECURITY.md)私下报告。
