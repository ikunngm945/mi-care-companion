# 0.6.0 健康周期与同区间屏幕

## 周期口径

- 起点为前一次主睡眠结束，终点为 Hook 实际采集时刻。主睡眠按源 `duration >= 180` 分钟判定，午睡不切界；需两次有效主睡眠。
- 睡眠栏目保留当前主睡眠全部阶段；心率、压力、血氧使用全周期实测散点按当地半小时显示 min/max，缺测不继承。
- 屏幕使用相同冻结起止，不回退自然日；周期不可用则屏幕周期也不可用。
- Hook 刷新保留实际采集终点；缓存周期滞后超过 120 秒不可用，不推进终点补造“截至现在”。
- 活动后续多来源规则见[步数](<verification-step-sources.md>)与[其他活动](<verification-activity-sources.md>)，不能用早期单来源限制覆盖新版实现。

实现见[HealthCycle](<../android/src/cn/local/micompanion/HealthCycle.java>)、[HealthText](<../android/src/cn/local/micompanion/HealthText.java>)及[周期结构](<research/health-cycle-schema.md>)。

## 测试范围

[HealthCycleTest](<../android/tests/HealthCycleTest.java>)覆盖主睡眠选择、午睡、阶段、端点、热量小数和 DST；[HealthTextTest](<../android/tests/HealthTextTest.java>)覆盖事实格式、缺测、安全边界及显示时区；[SnapshotFreshnessTest](<../android/tests/SnapshotFreshnessTest.java>)覆盖缓存超时、未来与非法周期；屏幕测试覆盖相同起止、事件缺失与预算。

预算包括睡眠查询、阶段数、周期长度、每类样本数及 UTF-8 载荷上限。超限明确失败，不截断事实或用日报兜底。

## 边界

数据源类别陈旧与周期自身状态独立，不把历史指标陈旧视为全部周期失败。UsageEvents 缺失或未知初态可能低估时长。测试不代表真实模型送达、长期后台保活或医学准确性；官方工具执行与 IM 幂等限制仍适用。
