# GitHub 小米运动健康数据读取 / Hook / 导出调研

## 范围与结论

- 调研目标为 **`com.mi.health` 小米运动健康** 的数据读取、Hook 与导出能力；证据来自公开仓库，不作为任一设备或应用安装版本的兼容性证明。
- **本次未找到经过核实、可直接安装并满足「读取本机 `com.mi.health` 健康数据 / Hook 导出」的成熟成品。** 这是检索范围内的结论，不是断言 GitHub 上绝对不存在。
- 最接近本机 Hook 的项目是 **[A5245/Wearable-Debug](https://github.com/A5245/Wearable-Debug)**，但实际功能不是健康数据导出，而且许可证未知。
- 最值得复用健康协议 / 数据模型的是 **[Misty02600/mi-fitness-python](https://github.com/Misty02600/mi-fitness-python)**，但它使用亲友共享云 API，不是本机数据库读取，发行物也是 Python 包而不是 APK。
- 候选的本地数据库、表结构、加密方式和跨版本兼容性仍需独立核查，不能仅凭名称给兼容性打勾。

## 包名与产品必须区分

1. 本机目标：`com.mi.health`。Wearable-Debug 的 Xposed 作用域同时列出 `com.mi.health` 和 `com.xiaomi.wearable`，可以确认其包含本次目标，而非仅凭「小米手环」名称推断。来源：[作用域源码](https://github.com/A5245/Wearable-Debug/blob/master/app/src/main/res/values/arrays.xml)。
2. mi-fitness-python 常量明确使用 `APP_NAME = "com.mi.health"`、`SERVICE_SID_HEALTH = "miothealth"`、`HEALTH_API_BASE = "https://hlth.io.mi.com"`、`REGION_TAG = "cn"`。因此这是与当前国内小米运动健康有关的云接口线索，不是旧华米服务的误匹配。来源：[const.py](https://github.com/Misty02600/mi-fitness-python/blob/main/src/mi_fitness/const.py)。
3. 旧 Mi Fit / Zepp Life 的 `com.xiaomi.hm.health` 不等于 `com.mi.health`。检索命中的刷步数、旧 Mi Fit / Zepp 导出类工具，不能因为名称含 Mi Fit 就当作本机目标的读取器。本次未对旧应用导出器做完整源码适配核验，故不推荐其作为目标实现基础。

## 候选逐项核实

### 1. A5245/Wearable-Debug：真实目标 Hook 参考，但不是健康导出器

- **项目及用途**：[仓库](https://github.com/A5245/Wearable-Debug)、[README](https://github.com/A5245/Wearable-Debug/blob/master/README.md)。作者说明其是 Xposed 插件，开启 Mi Fitness 隐藏的第三方小程序安装 / 卸载、表盘及固件安装；还提供设备日志拉取与 EncryptKey 展示。
- **实际支持**：README 仅声明 **8Pro + Mi Fitness 3.43.0i / 国内小米运动健康 3.44.0** 测试正常。这不是 Android 16、HyperOS 3 或任意应用版本兼容保证。来源：[README](https://github.com/A5245/Wearable-Debug/blob/master/README.md)。
- **源码证据**：作用域包含目标包；`EntryPoint` 使用 DexKit 查找 `com.xiaomi.fitness.about.AboutActivity` 的调用及 `com.xiaomi.fitness.webview.WebViewUtilKt`，并保留旧 `startWebView` 签名回退。可借鉴“定位类 / 方法并处理版本差异”的思路，但这不是健康数据 API。来源：[作用域](https://github.com/A5245/Wearable-Debug/blob/master/app/src/main/res/values/arrays.xml)、[EntryPoint.java](https://github.com/A5245/Wearable-Debug/blob/master/app/src/main/java/test/hook/debug/xp/EntryPoint.java)。
- **EncryptKey 含义**：源码调用设备管理器 `getDeviceList`，再取每台设备 `getDetail().getEncryptKey()`，输出 `did -> [设备名称, EncryptKey]`。这是穿戴设备密钥；**没有证据表明它是健康 SQLite / SQLCipher 数据库解密密钥**，不能混用。来源：[EncryptKey.java](https://github.com/A5245/Wearable-Debug/blob/master/app/src/main/java/test/hook/debug/xp/EncryptKey.java)。
- **不能满足**：README 和已核查源码没有提供心率 / 睡眠 / 步数数据库导出、本地健康数据 schema 或通用 JSON 导出功能；不能直接满足本需求。
- **维护风险**：用户协议入口曾随国内版 3.36.x、3.44.0 失效；固件管理内部方法也曾被移除。这说明 Hook 点存在实际版本漂移。来源：[issue #11](https://github.com/A5245/Wearable-Debug/issues/11)、[issue #12](https://github.com/A5245/Wearable-Debug/issues/12)、[PR #13](https://github.com/A5245/Wearable-Debug/pull/13)。这些报告只证明对应版本的问题，不能外推当前版本必坏。
- **发行 / 活跃**：GitHub API 返回 `pushed_at = 2025-09-23T14:13:47Z`、`archived = false`；releases 查询为空，因此本次未找到官方 GitHub Release APK。不能用仓库 `updated_at` 当作最后代码维护时间。来源：[仓库元数据](https://api.github.com/repos/A5245/Wearable-Debug)、[releases API](https://api.github.com/repos/A5245/Wearable-Debug/releases)。
- **许可证**：GitHub API `license = null`，仓库树未发现 LICENSE；记为 **未知 / 未发现明确授权**。公开源码不等于可以直接复制后分发，复用前应取得许可。来源：[仓库元数据](https://api.github.com/repos/A5245/Wearable-Debug)、[源码树](https://api.github.com/repos/A5245/Wearable-Debug/git/trees/master?recursive=1)。

### 2. Misty02600/mi-fitness-python：健康数据类型最完整，但走亲友云 API

- **用途**：README 明确“通过亲友列表获取其他账号的心率、睡眠、步数等健康数据”，并警告 API 端点可能随版本更新变化。来源：[README](https://github.com/Misty02600/mi-fitness-python/blob/main/README.md)。
- **已声明支持的数据**：心率（日均 / 静息 / 最大 / 最小 / 最新采样）、睡眠（时长 / 评分 / 深浅睡 / REM / 片段）、步数 / 距离 / 卡路里，以及活动卡路里、有效站立、中高强度时长、血氧、体重、血压、目标、日摘要。这里是作者接口能力说明，不是本次对真实账号逐项实测。来源：[API 表](https://github.com/Misty02600/mi-fitness-python/blob/main/README.md#api-一览)。
- **来源通道**：`/app/v1/relatives/get_relative_list`、`get_latest_data`、`get_aggregated_data`、`get_fitness_data`；请求基于 service token 与 `ssecurity`，使用 RC4 加密参数与响应解密。来源：[const.py](https://github.com/Misty02600/mi-fitness-python/blob/main/src/mi_fitness/const.py)、[client/base.py](https://github.com/Misty02600/mi-fitness-python/blob/main/src/mi_fitness/client/base.py)。
- **先决条件 / 限制**：小米账号登录（支持二维码登录示例）、有效令牌、亲友关系、对应数据类型已共享、查询处于允许共享的时间范围；数据需要先同步到云端。源码对非亲友、未共享、超共享时间范围、认证过期分别报错。**不能默认可用同一账号读取自己的全部数据，也不能把云上最新同步日等同于本机实时数据。** 来源：[README](https://github.com/Misty02600/mi-fitness-python/blob/main/README.md)、[client/base.py](https://github.com/Misty02600/mi-fitness-python/blob/main/src/mi_fitness/client/base.py)。
- **不能满足**：不是 LSPosed 模块、不读取本地数据库、不提供本需求成品 APK，也没有熄屏计时 / AstrBot 提交功能；作为替代需要用户接受云端登录和共享条件。
- **发行 / 活跃**：`v0.2.0` 发布于 **2026-03-21**，发行物为 `.whl`、`.tar.gz`；API `pushed_at = 2026-03-21T11:26:45Z`、`archived = false`。Issues API 当次为空不代表稳定性已被广泛验证。属于有发布和测试目录、但历史较短的 SDK，不宜称为多年成熟方案。来源：[v0.2.0 Release](https://github.com/Misty02600/mi-fitness-python/releases/tag/v0.2.0)、[元数据](https://api.github.com/repos/Misty02600/mi-fitness-python)、[源码树](https://api.github.com/repos/Misty02600/mi-fitness-python/git/trees/main?recursive=1)。
- **许可证**：**GPL-3.0**。复制 / 改写进分发项目应考虑相应许可证义务，不应无提示地当作宽松许可库使用。来源：[LICENSE](https://github.com/Misty02600/mi-fitness-python/blob/main/LICENSE)、[元数据](https://api.github.com/repos/Misty02600/mi-fitness-python)。
- **文档瑕疵**：README 的源码安装命令仍指向 `MistEO/MiSDK`，与当前仓库不一致；应以当前源码、版本及发布地址为准，不盲抄命令。来源：[README](https://github.com/Misty02600/mi-fitness-python/blob/main/README.md)。

### 3. XiaoSiHwang/garmin-weight-sync：可用成品偏体重，不是通用健康读取

- **用途 / 支持**：把已同步到小米运动健康云端的体重记录拉取到本地 JSON，生成 Garmin FIT 并上传佳明；包含体重 / BMI / 体脂等过滤。作者称支持已导入小米运动健康的体重数据，但不能外推睡眠 / 心率读取。来源：[README](https://github.com/XiaoSiHwang/garmin-weight-sync/blob/main/README.md)。
- **先决条件**：小米账号登录及 token；可能需要图形验证码 / 短信二次验证；原始数据必须已上传云端。README 推荐 Python 3.12.6，注明 3.14 存在同步问题。来源同上。
- **不能满足**：不 Hook 本机 `com.mi.health`，不读取其数据库；健康范围偏体重；现有桌面客户端不是 APK，也没有本需求的熄屏触发及 AstrBot 接入。
- **发行 / 活跃**：`V2.0.1` 发布于 **2026-01-18**，附件是 macOS arm64 / x64 `.dmg` 与 Windows x64 `.zip`；API `pushed_at = 2026-01-25T07:40:12Z`、`archived = false`。来源：[Release](https://github.com/XiaoSiHwang/garmin-weight-sync/releases/tag/V2.0.1)、[元数据](https://api.github.com/repos/XiaoSiHwang/garmin-weight-sync)。
- **问题证据**：存在肌肉质量与骨骼肌质量映射错误报告，说明跨平台字段含义需要核对，而非只转字段名。来源：[issue #15](https://github.com/XiaoSiHwang/garmin-weight-sync/issues/15)。
- **许可证**：README 声称 **MIT License**，但 GitHub API `license = null`，本次未核实独立 LICENSE 正文；记为“README 声称 MIT，完整授权文本待确认”，不直接按已核实 MIT 分发。来源：[README 许可证段](https://github.com/XiaoSiHwang/garmin-weight-sync/blob/main/README.md#️-许可证)、[元数据](https://api.github.com/repos/XiaoSiHwang/garmin-weight-sync)。
- **安全注意**：README 说明配置含明文密码 / token；其紧接着关于 `.gitignore` 的文字有明显不合理之处，不应照做。敏感凭据不得提交仓库。来源：[README 安全提示](https://github.com/XiaoSiHwang/garmin-weight-sync/blob/main/README.md#️-安全提示)。

## Gadgetbridge / Health Connect：仅作为条件性替代，不当成本次成品

### Gadgetbridge

- 官方 GitHub 描述自身是厂商封闭 Android 应用的无云替代品，并明确已迁至 Codeberg。其产品定位是与穿戴设备交互的替代应用，**不能仅凭支持 Xiaomi 设备就当作 `com.mi.health` 本地历史数据库读取器**。来源：[官方 GitHub](https://github.com/Freeyourgadget/Gadgetbridge)、[官方当前仓库](https://codeberg.org/Freeyourgadget/Gadgetbridge)。
- 本次没有完成当前 Codeberg 版本、用户具体穿戴设备型号、历史数据迁移、Health Connect 互通和许可证正文核验；这些全部记为 **未知**，不推荐用户为本需求立即重新配对或迁移。
- 如未来考虑该路线，必须先确认具体设备支持、绑定密钥需求及与原应用并存限制；它并不等价于“保留原 App 并 Hook 数据”。

### Android Health Connect

- 应作为“源应用主动同步到标准健康存储后，再由获授权的目标应用读取”的条件性路线核验；不能把其存在当成绕过另一个 App 私有数据库权限的工具。
- 本次未成功完整读取官方文档，也未找到并核实 **用户这个国内 `com.mi.health` 版本** 能写入哪些 Health Connect 数据的第一方承诺。因此：Android 16 系统是否可用、源应用是否支持及实际同步字段 / 延迟均需单独核验，本报告不将其列为已可用方案。
- 待核查官方入口：[Android Health Connect 读取数据文档](https://developer.android.com/health-and-fitness/guides/health-connect/develop/read-data)。这是后续核验入口，不作为本次已验证功能证据。

## 给主任务的复用建议

1. **本地优先**：本地 Hook 仍需针对目标版本做只读 schema / 存储层调查；GitHub 调研没有提供可以跳过该步骤的可靠成品。不要预填数据库文件名、表名、密钥或数据单位。
2. **Hook 参考**：Wearable-Debug 可证明目标应用存在可 Hook 内部类及版本适配实践，但许可证未知，不宜直接搬运；它提取的设备 EncryptKey 不是已知健康数据库密钥。
3. **健康语义参考 / 云端备选**：mi-fitness-python 是本次最有价值的复用仓库，优先参考其数据类型、日期范围、共享和认证错误边界；接受云方案后才考虑实际接入，不能宣称它实现本机采集。
4. **不为需求增加无关迁移**：Gadgetbridge / 旧 Mi Fit 导出器 / 体重到佳明工具都不是本需求的一键替代。
5. 三个已核查仓库均无证据提供用户完整 APK 的“天气 + 屏幕使用时长 + 健康，连续熄屏超过 3 小时后向自定义 AstrBot API/key 提交”链路；本报告也没有测试机器人或服务器。

## 方法与证据边界

- 使用 GitHub 搜索、作者 README、源码、GitHub REST 仓库元数据 / Release / Issue；已核查原文，而非只依据搜索摘要。
- 调研不执行候选项目、不登录任何小米账号、不接触手机；支持范围来自作者说明或源码静态核查，不等同于当前用户设备实测。
- 活跃日期采用 GitHub `pushed_at` / Release 时间，保留接口返回原值；没有可靠信息的字段明确标为未知。
