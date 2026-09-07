# v0.3.3 修复说明

版本身份：`versionCode 10 / versionName 0.3.3 / 协议 200 / 服务代 30004`。
本版修复服务身份验证、清理归属与页面状态竞争问题，回归测试直接编译生产源码或调用真实
Activity 回调。发布信息见[版本说明](releases/v0.3.3.md)；本版手机验收仍待执行。

## 原生会话与清理

| 问题 | 修复后的约束 |
| --- | --- |
| 验证失败在页面 Handler 上直接 stop，管理器卡住会阻塞页面 | 回调只保存身份、报告清理请求；停止由独立协调器和有截止时间的 RPC worker 执行 |
| 回调早于 start 返回，token 未到就删除记录 | `onBindingPrepared` 先交付页面句柄；保存原始 Binder、晚到 token 和失败信息，限时等待可中断 |
| 停服读取最新全局管理器，旧 token 被发给替代来源 | 记录捕获创建它的管理器对象及 Binder；仅向原来源发出停止，不回退到替代管理器 |
| 移除失败或 RPC 成功返回即忘记会话 | 保留身份供重试；RPC 返回不等于死亡，只有观测到 Binder 死亡后才确认清理 |
| 新旧页面共享记录，旧句柄能删掉当前会话 | 每次绑定领取独立租约；过期的回调、detach 和停止完成回调不能撤销当前所有者 |

所有记录锁只保护本地状态，锁内不进行远程 RPC。清理等待超时不会在 token 晚到后自行恢复
已经取消的调用；身份保留，后续手动重试使用新的有限预算。已经发出的 Binder RPC 可能不响应
中断，不能把本地超时当作远端停止，旧调用仍在执行时禁止重新开启。

验证 token 拒绝与业务协议拒绝都禁止 `disable` / `destroy` 等业务调用。协议读取失败、
不兼容或验证超时只允许来源管理器清理；原生拒绝状态随记录保存，换页面也不能恢复信任。
原始 Binder 仍用于死亡检查。尚未取得 Binder 表示缺少证据，不能报告“已死亡”。

官方 Shizuku 也在应用进程内按“来源管理器 Binder + 服务 Binder”保存协议拒绝身份，
新页面/新 adapter 不能重置信任；只依据本地 Binder 死亡知识回收记录，不把拒绝传给不同
来源或新服务。回归直接构造实际 adapter 会话并调用真实连接回调，不声称已测试管理器服务器。
权限判断与拒绝接口显式接受本次操作捕获的 Binder，不读取可能被晚到连接覆盖的字段；
交错回归检查“验证 A 时收到 B，随后拒绝 A”不会误记成 B，也不会让 A 的新会话恢复信任。

## 页面状态与安全距

断连、替换绑定、取消操作都释放 `stateReadPending`，使新会话能够重新读取状态。
停止工作已开始后，晚到的连接/断开回调不再覆盖清理对象或重新填入业务端点。
实际 `MainActivity` 回调回归覆盖这些分支；AGP mock Android 不是设备 Looper 或完整渲染环境。

根页面保留固定顶部 `72dp`，应用 `max(72dp, 顶部系统栏/刘海 inset)`，左右和底部使用
对应系统安全区，重复分发不累加。观察模式增加可访问名称，固定行高改为最小高度加自适应。
实现参考 [Android Insets 文档](https://developer.android.com/develop/ui/views/layout/insets) 与
[Views 无障碍原则](https://developer.android.com/guide/topics/ui/accessibility/views/principles-views)。
这不代表已验证具体小米超级岛布局、大字体、横屏或 TalkBack，仍需目视验收。

## 辅助脚本与文档

`verify-stop.ps1` 在可能执行开启点击之前标记恢复责任；随后任何测试失败，finally 都会使用
独立预算，尽力通过同一应用的“停用并退出服务”恢复停用。原测试判定与恢复结果分别报告。
不自动停权限管理器、不 force-stop 应用；仅删除本次创建的唯一 UI 转储。
设备不可达等情况仍可能恢复未确认，需用户在手机上手动停止。

脚本的 `am start` 只证明返回页面，不声称 Activity 重建或进程冷启动。官方 Shizuku、
Stellar 原生与本地静态结果分别核验，旧版本的反馈不能替代本版验收。

## 回归与验证入口

```powershell
.\scripts\test-stellar-lifecycle.ps1
.\tests\verify-stop.Tests.ps1
.\tests\verify-stop-process.Tests.ps1
.\tests\current-docs.Tests.ps1
.\scripts\verify-repository.ps1
.\scripts\build.ps1 -Variant Debug -LocalTestSigning
.\scripts\build.ps1 -Variant Release -LocalTestSigning
```

原生 harness 编译实际 `NativeStellarUserService`、参数与 `UserServiceStopper`，仅替换 Android /
管理器传输。它作为 `testStellarLifecycle` 被 Debug/Release 单测依赖，测试替身不进入 APK。
PowerShell 脚本测试全部离线模拟，没有运行手机 ADB。各项计数、最终哈希和签名核对见
[构建证据](BUILD_EVIDENCE.md)；设备步骤见 [实机验收](实机验收.md)。

本版官方 Shizuku、Stellar 原生、冷启动与异常恢复仍待真机验收。全局 Controller 互斥、当前用户限制以及
异步打开失败无自动回退的原有边界保持不变。
