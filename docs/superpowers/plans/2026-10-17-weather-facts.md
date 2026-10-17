# 天气固定事实精简实施计划

> **设计参考：** 本计划描述官方 HTTP 出站路径中的天气固定事实投影，不改变原始快照或自动触发语义。它是[初始方案](<2026-10-17-mi-companion.md>)之后的增量设计；下列任务是实现与验收目标，不记录个人设备结果或交付状态。

**Goal:** 仅将已采集天气转换成紧凑事实；原始快照、健康与屏幕、触发与授权保持不变。

**Architecture:** 纯 WeatherText 类通过 snapshot(String) 只将 weather.fields 转成 weather.facts，保持包络及其他类别；OfficialApi.generate 在组装 message 时调用。没有新依赖、新 AI 调用或新采集。

**Tech Stack:** Java `--release 8` / org.json / java.time，Android min API 28、target API 36。

**Spec:** [天气显示规则](<../../research/weather-display-rules.md>)。

## 全局约束

逐小时/逐日所有已采集项、预警、生活指数、空气质量与分钟数组均保留。未知值不补 0；单位未知不猜；hour_index 加上已证实的温度起点按小时增加，独立源时间不同不能默默配对；日期不以数组位置决定今天。整数 UV 才映射等级；未知天气码保留原码。输出有界，失败不可静默截断。纯函数、稳定排序，不依赖当前时钟或默认时区。

## 1. 纯格式化

文件：[WeatherText](<../../../android/src/cn/local/micompanion/WeatherText.java>)、[WeatherTextTest](<../../../android/tests/WeatherTextTest.java>)。

- 测试先覆盖功能缺失，再实现固定投影。
- WeatherText.snapshot(String) 严格校验 JSON，在新对象中替换天气 fields，健康/屏幕内容不变；不存在天气 fields 时保持原字符串。
- 覆盖手写映射、UV、日期、hour_index 缺口与不同时间、完整 schema 事实和 unknown 额外字段、emoji、缺失、确定性与字节减少。

## 2. 出站集成

文件：[OfficialApi](<../../../android/src/cn/local/micompanion/OfficialApi.java>)、[RouteApiTest](<../../../android/tests/RouteApiTest.java>)。

- 使用回环 HTTP fixture 输入天气，断言模型 message 包含固定 facts、health/screen 不变、原输入不变，并且只有一个 chat 调用。
- OfficialApi.message 参数使用 WeatherText.snapshot(snapshot)，不改变 EventClient.payload 与 AdvicePrompt 安全边界。
- 严格 JSON 非法或存在重复 key 时不得产生 chat POST；运行相关回归和[完整测试脚本](<../../../tools/test.sh>)。

## 3. 验收目标

- 审查事实、单位、时间及生产集成，缺陷以回归测试覆盖。
- 使用合成或经授权脱敏的输入检查输出大小和事实完整性；不将原始个人快照纳入公开材料。
- 在 Android JSON 与 JVM 实现上比较相同输入的 facts；只读预检不等同于真实模型或消息发送。
- 验证升级保留加密配置、事件及授权，构建与签名检查不替代运行验证。
- 交付说明区分格式化验证、真实 AI 生成、机器人送达和完整长阈值耐久测试，不以其中一项代替其他项。
