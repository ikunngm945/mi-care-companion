# 0.6.1 按钮阅读位置与应用图标

## 阅读位置

[MainActivity](<../android/src/cn/local/micompanion/MainActivity.java>)反馈曾主动 requestFocus、scrollTo 并在 preDraw 重复滚动，导致操作结果把用户拉离阅读位置。

修复删除反馈主动焦点与滚动，仅在原位更新脱敏文本。不延迟恢复旧 scrollY，避免覆盖用户在异步操作期间的新位置。系统输入聚焦和键盘布局不属于主动反馈滚动。

[UI 测试](<../android/device-tests/MainActivityUiProbe.java>)覆盖同步和异步反馈不改变位置、超长诊断隔离、无效草稿不联网、提示词长度及安全窗口；不以有效发送验证布局。

## 图标

[应用清单](<../android/AndroidManifest.xml>)普通和圆形图标都使用[自适应图标](<../android/res/mipmap-anydpi-v26/ic_launcher.xml>)。[图像资源](<../android/res/drawable-nodpi/ic_companion_art.png>)配合[前景 inset](<../android/res/drawable/ic_launcher_foreground.xml>)和[背景色](<../android/res/values/colors.xml>)预留遮罩安全边距，不增加运行时依赖。

该修复不改变采集、周期、配置、提示词或投递逻辑。不保证第三方桌面图标缓存立即刷新，也不承诺系统窗口变化或用户主动聚焦时页面完全不动。
