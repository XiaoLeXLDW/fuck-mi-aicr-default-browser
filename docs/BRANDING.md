# 品牌与资源

“岛外打开”沿用 XiaoLeXLDW 工具家族的黑白猫娘、白色贴纸描边与石墨色底板，
用青色浏览器窗口和金色右上跳出箭头表达“把网页交给自己选择的浏览器”。
浏览器徽章第二版增加地球和地址栏，放大约 20%，并向内移动以适应圆形裁切；角色造型、表情和位置沿用原版。启动器延展底色为 `#141A20`，与第二版原画边缘衔接。
图标与仓库展示保持同一角色；应用功能、版本状态和兼容性仍以 [README](../README.md) 为准。

## 家族来源

本次参考以下仓库的实际图标，并固定到核验时的源码版本：

| 家族成员 | 已核验版本 | 图标与生成脚本 |
| --- | --- | --- |
| VBAN Receiver for macOS | `6461360443741600bf0ce8d06b0cab5d2131d493` | [实际图标](https://github.com/XiaoLeXLDW/vban-receiver-mac/blob/6461360443741600bf0ce8d06b0cab5d2131d493/Resources/AppIconTransparent.png) · [原画](https://github.com/XiaoLeXLDW/vban-receiver-mac/blob/6461360443741600bf0ce8d06b0cab5d2131d493/Resources/AppIconSource.png) · [生成脚本](https://github.com/XiaoLeXLDW/vban-receiver-mac/blob/6461360443741600bf0ce8d06b0cab5d2131d493/Scripts/build-app-icon.py) |
| NAT Moe | `a38fab3769bbe8841d15f3f7a7f7eca440313282` | [实际图标](https://github.com/XiaoLeXLDW/nat-moe/blob/a38fab3769bbe8841d15f3f7a7f7eca440313282/assets/app-icon-window.png) · [原画](https://github.com/XiaoLeXLDW/nat-moe/blob/a38fab3769bbe8841d15f3f7a7f7eca440313282/assets/app-icon.png) · [生成脚本](https://github.com/XiaoLeXLDW/nat-moe/blob/a38fab3769bbe8841d15f3f7a7f7eca440313282/Scripts/build-app-icons.py) |

家族识别点是左黑右银白的头发、粉色猫耳、单眼眨眼与红瞳、黑项圈和金铃铛，
以及包围角色的白色贴纸轮廓。VBAN 用音波和音频接口区分功能，NAT Moe 用网络节点和环绕箭头区分功能；
本应用使用浏览器窗口与跳出箭头，不增加音频或 NAT 测试含义。

两个参考项目的桌面图标生成器共用 `0.80` 主体缩放、`9%` 底板边距、底板宽度 `23%` 的圆角，
背景渐变为 `#2C363F` 到 `#090E12`。这些参数说明家族的桌面图标基准；Android 的裁切与留白采用下文的自适应规则。

## 当前配色与原画

| 色彩 | 色值 | 用途 |
| --- | --- | --- |
| 石墨色 | `#172027` | 图标底色与封面基底 |
| 青色 | `#43D9EA` | 浏览器窗口与视觉强调 |
| 金色 | `#FFD16A` | 跳出箭头与角色金色细节 |

[mascot-source.webp](assets/branding/mascot-source.webp) 是通过内置 ImageGen，参考上述两个家族图标生成的本应用原画，
不是下载的第三方图库素材。参考图片仅用于创作过程，原始参考下载留在被 Git 忽略的 `.local/` 中，未作为本项目发布资源复制。

原画使用**不透明石墨背景**，这是设计的一部分。不要把暗色像素假装成透明像素，也不要用抠除暗色的方法破坏黑发和描边。
需要新画面时保留角色身份和功能徽章，再由同一导出脚本生成目标尺寸。
生成提示词与底图修正过程见 [imagegen-prompts.md](assets/branding/imagegen-prompts.md)。App 浅色界面的按钮使用更深的青色 `#086F7E`，保证白色按钮文字的可读性。

## 资源入口

| 资源 | 尺寸 | 用途 |
| --- | --- | --- |
| [app-icon.png](assets/branding/app-icon.png) | 1024 × 1024 | 图标展示、README 顶部图标 |
| [readme-hero.png](assets/branding/readme-hero.png) | 1600 × 640 | 仓库 README 横幅 |
| [social-preview.png](assets/branding/social-preview.png) | 1280 × 640 | GitHub 社交预览，需手动上传 |
| [brand-preview.png](assets/branding/brand-preview.png) | 由脚本生成 | 集中查看图标与仓库展示效果 |
| [mascot-source.webp](assets/branding/mascot-source.webp) | 保留生成原画尺寸 | 可重复导出的输入原画 |

Android 启动图标由同一原画导出到 `app/src/main/res/`。API 26 的自适应图标定义位于
`mipmap-anydpi-v26`，API 33 的定义位于 `mipmap-anydpi-v33`，后者附带单色主题图标。
Manifest 的 `android:icon` 与 `android:roundIcon` 指向同一自适应图标资源。

## Android 留白与裁切

自适应图标使用 **108dp 画布**，把 **60dp 原画居中**，四边各留 **24dp**。
前景和背景由系统统一裁切，保留启动器的圆形、圆角矩形等蒙版，不把桌面图标外框硬编码成 Android 蒙版。

Android 13 的单色版本使用简化的猫耳、窗口和跳出箭头符号，由系统主题着色。
它不直接把复杂原画转灰阶，以保留小尺寸下的功能轮廓。
启动器是否显示主题图标取决于系统和用户设置，导出资源不能代替实际设备验收。

## 重建与发布

生成器为 [scripts/build-branding.cjs](../scripts/build-branding.cjs)，固定使用 **Sharp 0.34.5**。
在仓库根目录执行，依赖和 npm 缓存均留在项目内：

```powershell
npm install --prefix .tools/branding --cache .tools/npm-cache --save-exact sharp@0.34.5
node scripts/build-branding.cjs
```

重建使用已保存的原画导出全部尺寸，不需要重新调用 ImageGen。变更原画后应检查 README 图片、
常规与单色启动图标，以及系统蒙版下的主体完整性。
横幅文字使用系统中文字体（优先 Microsoft YaHei，其次 Noto Sans CJK SC）；不同系统的字体渲染可能不同。
不需要为常规 APK 构建安装 Sharp，Android 资源已随源码保存。

社交预览图不会随 Git 文件自动设置为仓库封面，需要在仓库设置的 Social preview 中上传。
当前 APK 的打包、签名和校验信息见[构建证据](BUILD_EVIDENCE.md)与[版本说明](releases/v0.3.3.md)。

## 视觉验证边界

已查看生成的 48px / 64px 图标、圆形和圆角裁切、单色主题及仓库横幅，当前浏览器徽章经过
重新导出与视觉检查。这些是静态资源预览，不是手机截图。HyperOS 桌面、App 实际显示与主题动画
仍待本版实机确认；统一构建证据中记录 APK 的实际检查结果。
