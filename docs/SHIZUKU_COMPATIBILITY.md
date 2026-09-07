# Shizuku 与 Stellar 兼容性

**v0.3.3 实现了官方 Shizuku API 与 Stellar 原生 API 两条后端。**
双后端“已实现”不等于两套管理器“已实机验证”。本版仍待手机验收。

## 当前实现

| 项目 | 官方 Shizuku 路径 | Stellar 原生路径 |
| --- | --- | --- |
| 客户端 | `dev.rikka.shizuku:api/provider:13.1.5` | 本地 `NativeStellar` 与 Stellar AIDL |
| Provider | `${applicationId}.shizuku` | `${applicationId}.stellar` |
| 权限流 | `Shizuku.checkSelfPermission/requestPermission` | Stellar 原生 `stellar` 权限 |
| UserService 身份 | `redirector_shizuku`、稳定 tag、`UserServiceArgs` | `redirector`、原生 token 与 verification token |
| 停止请求 | `unbindUserService(args, connection, true)` | `stopUserService(token)` |

两条路径共享 `RedirectorUserService`，每个会话保存创建它的后端。版本身份按构建区分：

| 版本 | versionCode | 业务协议 | 服务代 | 状态 |
| --- | --- | --- | --- | --- |
| v0.3.3 | 10 | 200 | 30004 | Pre-release；本版手机验收待完成 |

页面“兼容服务（原生 API）”对应 **Stellar 原生路径**；文案保留，不把它改称官方 Shizuku。
状态里的“服务 API 103”等数值来自权限管理器，不是本应用的业务协议 `200` 或服务代。
管理器版本、Binder 可用性与服务 UID/PID 均由运行状态提供，不应从按钮名称推断真实连接。

源码入口：[PrivilegeRuntime](../app/src/main/java/dev/codex/mibrowserredirector/privilege/PrivilegeRuntime.java)、[ShizukuBackendAdapter](../app/src/main/java/dev/codex/mibrowserredirector/privilege/ShizukuBackendAdapter.java)、[StellarBackendAdapter](../app/src/main/java/dev/codex/mibrowserredirector/privilege/StellarBackendAdapter.java)、[Manifest](../app/src/main/AndroidManifest.xml)。

## 后端如何选择

1. “自动（推荐）”在可用时优先 Stellar 原生后端，否则选择 Shizuku API。
2. 手动选择只在停止状态下更改；授权或绑定开始后，本次操作固定到同一后端。
3. 检测到 Stellar 原生服务时，应用拒绝新建或重新附着 Shizuku 会话。Stellar 可能同时投递 Shizuku 兼容 Binder，单靠 Shizuku API 的进程级 Binder 无法可靠区分来源。
4. 如需单独验证官方 Shizuku，先完成当前会话停服，再停止 Stellar，最后启动官方 Shizuku 路径。

这不是两个服务同时接管。`IActivityController` 是系统全局接口，应用只允许一个后端持有接管会话，也不能与 `am monitor`、Monkey 等控制器工具同时使用。

## 停止为什么需要确认 Binder 死亡

界面退出和 UserService 退出是两件事。应用先保存停用意图，调用业务服务 `disable()` 注销 Controller，再通过创建会话的后端请求停止。如果仍能访问业务 Binder，则按 UserService destroy 契约补充清理；只有确认 Binder 死亡才显示“停止完成”。

Shizuku 会话还保存创建时的管理器 Binder。若来源已变化，应用拒绝向替换后的管理器执行 remove；超时会保留原后端归属供重试，不将不确定状态显示为成功。AIDL 的 destroy 方法编号为 `16777114`，实际 Binder 事务码为 `16777115`。

v0.3.3 的 Stellar 原生连接记录也固定保存来源管理器和其 Binder，不将旧 token 发给
后来替换的管理器。token/协议不匹配或协议验证超时的 Binder 只保留作清理身份，禁止业务
enable/disable/destroy，仅向来源管理器请求停止；原生拒绝状态跨页面保留。token 晚到或
停止 RPC 失败时保留记录供受监督清理/重试。请求停止成功仍不等于已确认 Binder 死亡，异常
路径的端到端行为需由本版测试和手机验收分别核验。

停止协调和普通查询/开启使用独立任务通道。超时表示“未确认”，不是 Binder 已退出；已经发出的
同步 Binder 请求不能仅靠取消本地等待撤回。不要靠连续点击或切换管理器绕过未完成清理。

## 证据与兼容边界

| 范围 | 当前结论 |
| --- | --- |
| v0.3.3 构建、单测、Lint、双 Provider 打包 | 当前结果及执行范围见[构建证据](BUILD_EVIDENCE.md)；静态通过不代表设备兼容 |
| 官方 Shizuku 与 Stellar 原生路径 | 本版待手机验收；双管理器、ADB 冷启动及异常恢复也无完整实机证据 |
| 浏览器弹窗 | 真实候选过滤、大字体、横屏/折叠、TalkBack 仍待专项验收；正常跳转反馈不证明菜单全测通过 |
| Sui 与其他设备/ROM | 未测试；不作兼容承诺 |

`minSdk 26` 只表示 Android 8.0 起可满足安装版本门槛，不保证 ROM 提供或允许 `SET_ACTIVITY_WATCHER` / `setActivityController`，也不保证超级岛把网址放入 `Intent.data`。完整测试步骤见[实机验收](实机验收.md)。

应用不修改系统默认浏览器，不处理 WebView；目标启动使用 `--user current`，
工作资料/系统分身等跨用户场景不在已验证范围。

## 上游来源

Stellar 的 Shizuku 兼容层让采用 Shizuku API 的客户端连接 Stellar；它不能让只使用 Stellar AIDL 的旧客户端反向连接官方 Shizuku。本项目当前保留两条独立路径，不依赖这一反向假设。

以下是原协议审计采用的一手源码快照，固定提交便于复核，不表示上游当前最新版：

| 来源 | 固定提交 |
| --- | --- |
| Shizuku-API | [a27f6e4](https://github.com/RikkaApps/Shizuku-API/tree/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5) |
| Shizuku | [b844bc4](https://github.com/RikkaApps/Shizuku/tree/b844bc491f1790c72328e1a8e5b2349f8978f0ea) |
| Stellar | [abba17f](https://github.com/roro2239/Stellar/tree/abba17f64737663e06d87fa82eb57dfbb982f163) |
| Stellar-API | [e22b3a0](https://github.com/roro2239/Stellar-API/tree/e22b3a0c76305c57a36696b069938d3c356a290b) |
