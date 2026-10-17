# 小米健康固定显示规则与数据来源

本文记录 `com.mi.health` 的静态显示算法，证据以类、方法及资源语义描述，不依赖临时反编译文件。内部类或混淆名可能随版本变化，不构成运行时兼容保证。

## 半小时图

- 心率、血氧、压力日图使用 48 个半小时槽；这是 UI 显示粒度，不是采样频率。
- `HrmDataUtil.fillDaySegmentBarEntry` 从 DailyHrReport.hrRecords 按记录 time 精确匹配槽 key，再合并 valueArray，不是对任意散点直接 floor。
- `SpO2DataUtil.fillDaySegmentBarEntries` 同样读取 spo2Records；convertToIntegerListAndFilterZero 保留正值，不在该方法设置 100 上限。
- `StressDataUtil.fillDaySegmentBarEntry` 读取 stressRecords，removeZeroValue 实际保留 1..100。
- `SegmentBarEntry.getRangeStr` 在 max > min 时显示 min-max，否则显示单值。getRectModels 的差值阈值只用于拆绘图矩形，不改变极值或采样时间。

## 全天统计

HrmDataUtil、SpO2DataUtil、StressDataUtil 直接读取日报的 avg/min/max；静息心率与睡眠平均心率另有来源。不能从半小时 min/max、中点或桶均值恢复准确全天平均。

显示层直接读字段不证明上游采样加权、舍入、跨来源去重或静息算法。原始日期标签不能无条件当真实本地午夜 epoch。

## 睡眠阶段

SleepLandScapeActivity 调用 `nfo.b` 从 SleepSegmentReport.sleepItems 构造阶段，起止与状态来自 SleepStateItem，不按半小时重新识别。

该 UI 路径以闭区间筛选 hrDetailRecords 与 spo2DetailRecords，相邻阶段可能共享边界样本。极值缺失时会沿用上一阶段非零端值；这不是本阶段实测或插值，不能静默继承到保真采集。

DaySleepHighLightFormatter 使用独立阶段时间与范围文案，相等极值仍可能显示两端，与日图单值规则不同。分钟计算采用整数时间边界，不能把展示分钟当原始精确持续时间。

## 工程选择与边界

- 当前助手从真实散点派生周期半小时桶并明确时区与 `(start,end]` 边界，不冒称复刻原生日报精确 key 算法。
- 保留来源睡眠阶段；阶段 HR/SpO2 仅用本段实测，缺失不借前段。
- sleepItems 的持久字段名为 items，hrDetailRecords/spo2DetailRecords 为 transient，不能凭实体字段存在推断完整细点直接存在于 JSON。
- 未检查的上游生成、细点装配、过滤及跨版本语义仍需独立核验。

当前协议与枚举详见[周期结构](<health-cycle-schema.md>)，早期日报字段详见[健康扩展结构](<health-expanded-schema.md>)。
