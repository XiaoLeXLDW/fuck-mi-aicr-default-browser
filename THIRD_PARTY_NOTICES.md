# Third-party notices / 第三方来源

本项目原创代码与文档采用根目录 [MIT 许可证](LICENSE)。以下上游文件、派生实现和依赖保留各自许可，
根 MIT 不重新授权这些内容。记录核对日期：2026-09-05。

## Stellar 原生集成

来源：[Stellar-API 固定提交](https://github.com/roro2239/Stellar-API/tree/e22b3a0c76305c57a36696b069938d3c356a290b)
`e22b3a0c76305c57a36696b069938d3c356a290b`。

上游该提交的 README 声明：Stellar 修改部分为 MPL-2.0，继承 Shizuku 的部分保留 Apache-2.0。
该提交没有根 LICENSE 文件，以下对应源码也没有逐文件版权头。这里保留其已声明的双重来源信息，
不虚构版权姓名、年份或更细的继承边界，不将它们标为项目 MIT。

| 本地路径（相对于 app/src/main/） | 上游路径 | 本地改动 |
|---|---|---|
| `aidl/com/stellar/server/IStellarService.aidl` | `aidl/src/main/aidl/com/stellar/server/IStellarService.aidl` | 保留 wire 声明，调整导入、顺序和排版 |
| `aidl/com/stellar/server/IStellarApplication.aidl` | 同目录同名 AIDL | 排版调整 |
| `aidl/com/stellar/server/IUserServiceCallback.aidl` | 同目录同名 AIDL | 排版调整 |
| `aidl/com/stellar/server/IRemoteProcess.aidl` | 同目录同名 AIDL | 排版调整 |
| `aidl/com/stellar/server/IRemotePtyProcess.aidl` | 同目录同名 AIDL | 排版调整 |

| 本地 Java 路径（相对于 app/src/main/java/） | 上游 Kotlin 路径 | 本地改动 |
|---|---|---|
| `com/stellar/api/BinderContainer.java` | `provider/src/main/kotlin/com/stellar/api/BinderContainer.kt` | Java Parcelable 改写 |
| `dev/codex/mibrowserredirector/stellar/NativeStellarProvider.java` | `provider/src/main/kotlin/roro/stellar/StellarProvider.kt` | 精简 Java Provider，仅保留所需传输 |
| `dev/codex/mibrowserredirector/stellar/NativeStellar.java` | `api/src/main/kotlin/roro/stellar/Stellar.kt`、`shared/src/main/kotlin/roro/stellar/StellarApiConstants.kt` | 精简 Binder/权限连接及回调 |
| `dev/codex/mibrowserredirector/stellar/NativeUserServiceArgs.java` | `userservice/src/main/kotlin/roro/stellar/userservice/UserServiceArgs.kt` | 精简 Java 参数封装 |
| `dev/codex/mibrowserredirector/stellar/NativeStellarUserService.java` | `userservice/src/main/kotlin/roro/stellar/userservice/StellarUserService.kt` | Java 改写及连接身份、解绑、旧会话清理 |

以上派生文件中的本地修改按 MPL-2.0 提供，并保留上游声明的 Apache-2.0 继承部分。
许可证全文：[MPL-2.0](licenses/MPL-2.0.txt)、[Apache-2.0](licenses/Apache-2.0.txt)。
分发二进制时保留本声明和许可证，并同时提供与该构建对应的这些文件源码及修改；
本仓库源码及对应 Release 的源码归档用于这一目的，不能用不同版本源码替代。

## Shizuku-API · MIT

Maven 依赖：`dev.rikka.shizuku:api:13.1.5`、`dev.rikka.shizuku:provider:13.1.5`，以及其
`aidl`、`shared` 传递模块。来源：[Shizuku-API](https://github.com/RikkaApps/Shizuku-API)。

此前文档把官方 Shizuku-API 写成 Apache-2.0，现根据其
[实际 LICENSE](https://github.com/RikkaApps/Shizuku-API/blob/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5/LICENSE)
更正为 **MIT，Copyright (c) 2021 RikkaW**。完整原文见 [Shizuku-API-MIT.txt](licenses/Shizuku-API-MIT.txt)。
这与 Stellar 对其继承来源的声明分开记录。

## AOSP 隐藏接口 · Apache-2.0

`hidden-api-stub/src/main/aidl/android/app/IActivityController.aidl` 是编译期接口声明，
对应 [AOSP 接口](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/app/IActivityController.aidl)。
保留 `Copyright (C) 2009 The Android Open Source Project`。本地只保留需要的声明和说明；
该模块通过 `compileOnly` 引用，不作为运行时实现打包。

## 构建与辅助依赖

- AndroidX Annotation：Android Open Source Project，Apache-2.0；用于注解。
- Gradle Wrapper 8.9：Gradle 作者，Apache-2.0；Wrapper 脚本保留原始版权头。
- Android Gradle Plugin 8.7.3：Android Open Source Project，Apache-2.0；构建期使用。
- JUnit 4.13.2：EPL-1.0；Hamcrest Core 1.3：BSD-3-Clause。仅测试期使用，不随应用发布。

Android SDK、JDK、Gradle 完整发行包和本地缓存不纳入源码提交。
`licenses/` 中原文取自 Apache、Mozilla 与上述固定 Shizuku-API 提交；应用 APK 也携带这些文本和本声明。

## 图标

`app/src/main/res/drawable/ic_launcher.xml` 是本项目的 Android 矢量图标（橙底、白色转向箭头），
按根 MIT 提供；不是 Xiaomi、Shizuku 或 Stellar 的官方品牌图标。
