# 隐私与数据处理说明

本说明面向 **v0.3.3**，按现有 `RedirectDispatcher`、`AmProcessLauncher` 和本地配置实现说明数据流。“岛外打开”在设备上读取浏览器启动事件，将符合规则的网址交给所选浏览器。当前源码没有账号系统、分析 SDK 或上传实现；应用 Manifest 不声明 `INTERNET` 权限。

无网络权限不等于整个链路无法联网：UserService 使用权限管理器提供的 shell/root 身份，所选浏览器也会访问网址。管理器、浏览器与 Android 系统各自的数据处理不由本应用控制。

## 处理哪些数据

| 数据 | 用途与保存位置 |
| --- | --- |
| 声明通用 HTTP 与 HTTPS 入口的应用名称、图标与包名 | 在本机生成浏览器候选列表；选择结果保存为包名，不上传应用清单 |
| 目标包、`ACTION_VIEW` 与 `Intent.data` | 判断是否接管；解析和转交完整 HTTP/HTTPS 网址 |
| 最近一次事件 | UserService 内存中保留一条状态；正常网址事件在 UI 仅显示域名 |
| 完整网址与目标包 | `RedirectDispatcher` 的有界 `LinkedHashMap` 按配置代、目标包、网址去重；`ArrayDeque` 最多保留 8 个等待任务，另有 1 个活动任务；`AmProcessLauncher` 参数列表及 `am start -d` 使用完整网址 |
| 本地配置 | 私有 SharedPreferences 保存浏览器草稿/生效目标、模式、启用意图、后端归属及 Stellar 随机 verification token |

### 内存留存与输出

排队或执行中的任务占用去重项；成功后以单调时钟记录时间，1.5 秒内抑制重复，失败则移除该
去重项以允许重试。成功记录在后续提交时清理过期项或受容量限制淘汰，并无 1.5 秒自动删除定时器。
停用、更新配置和关闭调度器会清空等待队列与去重表，并标记活动任务取消；已交给系统的启动无法撤回，
活动任务和参数引用可能继续保留到清理完成并由运行时回收。以上不是安全擦除或精确删除期限。

`am` 的 stdout/stderr 合并后被持续读取以避免管道堵塞；解析器仅暂存每行至多 192 个字符用于
错误判定，不将原始输出或进程启动异常消息直接写入最近事件，也不保存输出文件。正常网址事件只
展示域名，但域名本身也可能敏感；权限、连接和 Controller 等其他错误路径仍可能展示错误消息。
不能因此宣称全部异常输出自动脱敏。

本应用不建立浏览历史数据库。Android 系统日志、进程诊断、浏览器历史以及自行收集的 ADB 日志
仍可能包含完整网址、路径、查询参数或登录令牌；这部分不由本应用统一删除。

## 权限与本地存储

应用通过 Shizuku 或 Stellar 的授权启动 UserService，并通过全局 Activity Controller 观察启动回调。业务只处理目标包 `com.android.browser`；框架回调本身属于系统范围的特权能力。

当前源码未声明存储、无障碍服务或 VPN 权限，也不读取网页正文、浏览器 Cookie 或账号密码。`allowBackup=false` 关闭应用声明的系统备份；它不覆盖设备厂商、调试工具或用户自行制作的备份。

本地 `redirector` SharedPreferences 包含 `browser_package`、`selected_browser_package`、
`observe_only`、`desired_enabled`、`privilege_backend`、`active_privilege_backend` 和
`stellar_verification_token`。后者是本机随机 UUID，用于 Stellar UserService 身份校验，
不是账号或遥测 ID。原生服务连接的 Binder、管理器返回的 token 和回调记录保存在进程内存，
不是网址历史；不要把这些 token 放进公开反馈。

页面“兼容服务（原生 API）”指 Stellar；它与官方 Shizuku 的授权分别处理。

## 反馈前先脱敏

1. 尽量使用 `https://example.com/` 等公开测试网址重现，不使用个人搜索、内网地址或带登录参数的链接。
2. 提交截图、日志与状态文本前，检查完整网址、账号、设备序列号、无线调试地址及 token，删除与问题无关的内容。
3. 只提供重现所需片段；问题模板中的环境、后端、应用版本与期望/实际行为通常足够开始排查。

`scripts/verify-stop.ps1` 会安装/启动应用并操作界面；UI 自动化还会在设备 `/sdcard/codex-redirector-window.xml` 写入页面转储。它不是只读诊断脚本，运行后生成的文件与终端输出也需要检查后再分享。

## 停用与清除

先在应用内点“停用并退出服务”，等待“停止完成”及 Binder 死亡确认。随后如需清除配置，可通过 Android 的应用存储设置清除数据或卸载；这会重置已保存选择。清除应用数据不会清除浏览器历史、系统日志或手动导出的诊断文件。

使用步骤见[快速开始](wiki/Getting-Started.md)，故障处理见[故障排查](wiki/Troubleshooting.md)。
