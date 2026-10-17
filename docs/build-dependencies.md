# 构建依赖

构建脚本使用标准命令行工具，不需要 Gradle 或 Android Studio。

## 工具

- 支持 `javac --release 8` 的 JDK。
- Python 3.12，用于 Python 测试。
- `aapt2`、R8/D8、`zipalign`、`apksigner`、`zip`。

## 编译依赖

将依赖放在 `tools/deps/`，该目录不提交到仓库。

| 文件 | 来源及用途 |
|---|---|
| `android.jar` | Android 官方 SDK API 36，Java 编译 |
| `resources-api34.jar` | Android 官方 SDK API 34 的 `android.jar`，资源链接；不改变 targetSdk |
| `r8.jar` | [Google Maven R8/D8 8.9.35](<https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.9.35/r8-8.9.35.jar>) |
| `xposed-api.jar` | [Xposed API 82](<https://maven.aliyun.com/repository/public/de/robv/android/xposed/api/82/api-82.jar>)，仅编译使用，不包含在 APK DEX |
| `json.jar` | [org.json 20240303](<https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar>)，仅 JVM 测试；Android 使用系统实现 |

从可信 SDK Manager 或上游 Maven 获取依赖，核对来源与完整性，不仅根据文件名信任下载。

## 执行

```sh
bash tools/test.sh
bash tools/build.sh
```

测试使用隔离服务及合成输入，不代表真实模型或机器人端到端送达。Android instrumentation 测试另需自行准备执行入口、签名及授权，不属于通用构建前提。

## 签名与隐私

构建脚本首次生成 `signing/local.p12`，后续复用以支持覆盖安装。脚本中的开发密码不作为正式发行安全保护；生产签名请自行维护并妥善备份，换签名不能直接覆盖旧安装。

不要提交签名私钥、真实连接配置、健康数据库、探针缓存、依赖二进制或构建目录。
