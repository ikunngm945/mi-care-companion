# APK 与 AstrBot：GitHub 复用调研结论

## 功能范围

- 读取本机小米天气 `com.miui.weather2`、屏幕使用时长、小米运动健康 `com.mi.health`。
- 连续息屏严格超过 3 小时只进入待触发状态，不上报。
- 等用户下一次打开手机且连续使用严格超过 10 分钟，才汇总并发送给用户配置的 AstrBot API URL / key；由 AstrBot 处理后通过机器人通知目标用户。
- 用户已选择 A：10 分钟内再次息屏仍保留待触发资格；下次解锁重新从零计算连续使用时间，不累计零碎使用。“打开手机”建议定义为解锁后持续亮屏，完整设计仍待确认。

## 结论

检索范围内未核实到完整覆盖所有要求的现成 APK。可复用局部方案，但不能把“屏幕时长 + Health Connect + 定时 Webhook”冒充“本机小米数据 Hook + 长息屏后连续使用触发”。未安装或运行任何候选项目，未编写 APK 实现。

## 候选与边界

### Life Dashboard Companion

- [作者 README](https://github.com/owen282000/life-dashboard-companion-app/blob/main/README.md) 明确支持每应用屏幕时长、Health Connect 数据和自定义 Webhook，支持自定义 headers、定时同步、离线重试；README 链接官方签名 APK Release。
- [许可证](https://github.com/owen282000/life-dashboard-companion-app/blob/main/LICENSE) 已读取，MIT，Copyright (c) 2025-2026 Owen Vogelaar。
- 可作为使用时长、HTTP 配置和健康标准存储读取的候选参考；本次未核查这些功能源码和实际 APK，不将文档声明等同于实测。
- 不提供本次已核实的小米天气采集或本机 `com.mi.health` Hook；国内小米运动健康是否可将所需数据写入 Health Connect 未知。README 的按间隔 / 固定时刻同步也不能直接替代最新触发规则。

### HyperCeiler

- [作者 README](https://github.com/ReChronoRain/HyperCeiler/blob/main/README.md) 声明面向 Android 15–16 HyperOS，基于 Android 16 / HyperOS 3 适配，作用域含 `com.miui.weather2`；但“作用域包含天气”不是“导出当前天气”。未核查到可直接满足本需求的导出入口。
- [许可证](https://github.com/ReChronoRain/HyperCeiler/blob/main/LICENSE) 已读取开头及主要条款，为 GNU AGPL v3。若实际复制其代码，必须核对完整授权义务；不为小需求搬入整个系统修改模块。

### AstrBot External Trigger

- 已读取 [README](https://github.com/naer-lily/astrbot_plugin_external_trigger/blob/master/README.md) 与 [main.py](https://github.com/naer-lily/astrbot_plugin_external_trigger/blob/master/main.py)。
- API-key 端点为 `POST /api/v1/plug/hook/external-event`，`Authorization: Bearer <api_key>`；JSON 为 `{"umo":"平台ID:消息类型:会话ID","message":"提示及汇总数据"}`。
- 源码构造 `ExternalTriggerEvent` 并经实际平台 adapter 的 `commit_event` 入队；`send` 委托 `context.send_message`，意图为走完整 LLM 流水线并向目标会话回复。
- HTTP 成功响应只代表事件入队，不代表 LLM 已生成或机器人已送达。
- 实现通过 monkey-patch `AstrBotDashboard.__init__` 注册 `/api/v1/` 路由，存在版本 / 加载顺序耦合；其关于未知路径 scope 的注释仅代表作者实现假设，不能视为当前 AstrBot 鉴权契约。尚未在用户服务版本实测。
- 许可证未核实，不直接复制分发。优先作为用户自行安装的独立插件候选；若不可兼容则需写配套 AstrBot 插件。

### AstrBot Webhook Receiver

- 已读取 [README](https://github.com/3265204/astrbot_plugin_webhook_receiver/blob/main/README.md)，作者要求 AstrBot >=4.18。
- 接口形如 `/api/v1/plugins/extensions/astrbot_plugin_webhook_receiver/push/monitor`，使用 `X-API-Key` 和 `X-Webhook-Token`，按配置目标 UMO 推送。
- 文档描述直接通知转发，没有证明调用 LLM 后再回复；不能单独替代用户要求的 AstrBot 分析链路。

### 小米运动健康

详见同目录 `github-health.md`。Wearable-Debug 有真实目标 Hook，但仅设备调试和安装功能；mi-fitness-python 提供亲友共享云 API，不是本机数据库导出。没有已核实的本机健康成品 APK。

### 其他检索线索

- [WebhookInvoker](https://github.com/Nosskirneh/WebhookInvoker) 仅从搜索结果获得“亮屏 / 息屏 HTTP 请求”的描述。raw 的 `master/README.md` 返回 404，不能推定仓库不存在或功能已核实；后续 GitHub API 返回 `403: rate limit exceeded`。未作为可用成品推荐。

## 实施前建议

1. 推荐精简本机 APK：原生使用时长与屏幕状态计时，加针对当前版本的小米数据只读采集 / LSPosed Hook；AstrBot 使用经实测兼容的外部事件入口，必要时提供配套插件。
2. 不推荐为了复用引入小米账号云登录、亲友共享或重配穿戴设备，除非用户明确接受这些额外条件。
3. 必须先确认中途息屏规则、AstrBot 版本及目标 UMO；API URL / key 可留给用户在 APK 填写，不把凭据写入源码。
4. 后续验证需区分：APK 构建 / 安装、三类真实数据读取、时间状态机边界、HTTP 入队、LLM 完成和机器人送达；缺少服务器配置时不能宣称全链路实测。

## 方法与限制

优先 GitHub 作者 README、源码及 LICENSE，公开文档声明不等于本项目运行时兼容性证明。此为早期候选调查，当前实现以[项目说明](<../../README.md>)为准。
