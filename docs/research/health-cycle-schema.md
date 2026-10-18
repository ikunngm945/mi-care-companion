# 0.6.0 健康与屏幕周期：结构、固定规则和证据边界

本文记录健康与屏幕周期的固定规则及源码证据，不包含健康记录、账号、来源标识、坐标、凭据或私有快照。版本号表示项目规则演进，不表示源应用安装版本；合成测试不能替代运行时兼容性验证。

## 1. 来源和适用范围

- `com.mi.health` 的相关静态研究：[健康日报/最近记录结构](<health-expanded-schema.md>)提供实体字段、分钟单位、真实epoch与日报标签的区别；[健康显示规则](<health-display-rules.md>)提供半小时显示、来源阶段及UI缺测继承风险。这两份历史研究不是0.6.0周期协议，旧“四行/无数组/64 KiB/活动次数未知”等描述不能覆盖本周期规则。
- 静态逆向证据只记类名与语义：`SleepExtKt.filterAndSortSleepLong`使用 `duration >= 180` 分钟；`SleepSegmentReport` / `SleepStateItem`说明睡眠起止与阶段；卡路里、步数、中高强度及运动报告的实体字段语义以本节已有研究和下述采集实现交叉核对。临时逆向资料不复制、不打包，不写入本机私密值；不将临时路径作为交付依赖。
- 可随源码复核的实现证据：[HealthCycle](<../../android/src/cn/local/micompanion/HealthCycle.java>)、[HealthSource](<../../android/src/cn/local/micompanion/HealthSource.java>)、[HealthText](<../../android/src/cn/local/micompanion/HealthText.java>)。源数据库为独立 `OPEN_READONLY | NO_LOCALIZED_COLLATORS` 连接，不迁移、不checkpoint、不改权限、不主动联网刷新；只使用已定位的当前账号，账号歧义不混合。

## 2. 周期选择与冻结

1. 以健康Hook实际采集时刻 `now` 为终点，在此前14天查询 `sleep_segment` 中未删除、`key='sleep'` 且数据库结束时刻不晚于采集时刻的睡眠，最多64条，加1条sentinel判断超限。0.6.5移除旧版额外的 `isComplete=1` 条件：该标志是源端睡眠完整状态，不是同步成功或记录可读取的标志；原应用普通读取链路不据此排除记录。标志为0不代表最终定稿，仍需后续起止、时长和阶段校验，详见[睡眠筛选修复](<../verification-sleep-selection.md>)。
2. 用真实 `bedtime` / `wake_up_time` 和分钟 `duration` 校验来源。主睡眠阈值为 **`duration >= 180` 分钟**，不是仅用卧床起止差替代。相同结束时刻的完全相同睡眠去重；互相冲突的记录安全失败，不能借重复设备睡眠制造零长度周期。
3. 最近两次有效主睡眠中，前一次的结束为 `start`；`end = now`，不是当前主睡眠结束。午睡（小于180分钟）不切界；不足两次主睡眠则 `missing`。周期必须大于0且不超过7天。
4. 睡眠展示仅选当前（最新）主睡眠，保留其全部来源阶段，不补齐缺失阶段、不附加午睡阶段。心率/压力/血氧及活动则覆盖整个周期，包括最新主睡眠结束后直到Hook采集时刻的记录。
5. [HookEntry](<../../android/src/cn/local/micompanion/HookEntry.java>)在源应用主进程每60秒只读发布，后台存活与准点不作保证。[SnapshotProvider](<../../android/src/cn/local/micompanion/SnapshotProvider.java>)保留健康真实采集时间；[SnapshotFreshness](<../../android/src/cn/local/micompanion/SnapshotFreshness.java>)在接收读取时检查周期：滞后 **`> 120000 ms`** 为 `stale`，恰好120秒仍可用。未来终点、非整数边界、非正起点、结束不大于起点或超过7天为 `error`。失效后不移动 `start/end`，不把旧快照补造成“截至现在”；不可用周期不能授权屏幕或模型周期事实。

## 3. 周期白名单与缺测

位于 `health.fields.cycle`；仅 `status='ok'` 可用于模型周期事实。

| 字段 | 语义 |
|---|---|
| `status` | `ok/missing/error`；接收端过期可标 `stale` |
| `start_ms/end_ms/timezone` | 同一被冻结周期的真实epoch毫秒及采集时区；格式器显示本地日期时间 |
| `activity` | `calories_kcal/steps/mh_minutes/sport_count`，每项独立为实测汇总或null |
| `sleep` | 仅当前主睡眠，含真实起止及全部 `stages` |
| `stages[]` | 起止、源 `state`、片段内实测 `heart_rate/spo2` 的min/max或null |
| `heart_rate/stress/spo2` | 全周期实测散点派生的本地半小时桶，每桶有起止和min/max；查询失败为null，成功无有效样本为空数组 |
| `measurement_note` | 固定规则说明，不复制源自由文本 |

### 3.1 实测半小时桶与睡眠阶段

- 真实散点分别来自 `hr_record`（`heart_rate/single_heart_rate`）、`stress_record`（`stress/single_stress`）、`spo2_record`（`spo2/single_spo2`）。使用明细真实秒级 `time`，不使用日报 `timeInZero/timeIn0Tz` 标签或缓存更新时间代替采样epoch。
- 样本采用 **`(start,end]`**，局部阶段/桶同样左开右闭，恰落半小时末端的样本属于前桶，不造零长桶、不双计。按周期时区对实测散点分本地半小时桶，首尾裁切；DST重复时段保留不同真实时间桶。不冒称复刻原应用日报按槽key匹配的算法。
- 心率仅正值，压力/血氧为1..100；完全相同时间和值去重。桶只列有效样本min/max，单值简写，不从桶极值算全天平均，不推断采样频率。
- 阶段保留来源顺序、起止和状态，不按半小时重新切睡眠；片段HR/SpO2只统计本段实测，**缺测不继承前段**。与已有研究中原应用UI闭区间/缺测沿用行为有意区分。
- 源状态映射：0未睡眠、2深睡、3浅睡、4快速眼动、5清醒、6睡眠未分期；未知代码保留为未知阶段，不把trace当REM。

### 3.2 活动总数：不能替换为日报或stand

| 输出 | 明细与固定算法 | 缺失/冲突 |
|---|---|---|
| `calories_kcal` | 0.6.4：`calorie_record`，`key='calories'`，先裁切`(start,end]`，同来源同时间去重，各来源按当地半小时累计，逐槽取大再求和；`BigDecimal`保留小数，不照搬原生日报的每槽roundToInt，不除1000 | 无明细/未知来源/坏值/冲突/超限为null |
| `steps` | 0.6.3：`step_record`，`key='steps'`，先裁切 `(start,end]`；同来源同时间完全相同去重，冲突失败；各来源按当地半小时 `[slot,next)` 累计，逐槽取较大来源后求和 | 不用STEP日报替代，来源未知/坏值/溢出/超限为null |
| `mh_minutes` | 0.6.4：`mh_strength_record`，`key='intensity'`，跨来源以真实timestamp并集计样本分钟，不擅自floor到整分钟或取每槽来源最大计数 | 不使用站立次数/日报替代；无明细/坏值/超限为null |
| `sport_count` | 0.6.4：time为通常由start_time生成的DB键（可保留dbKeyTime）；下界回查`start - 7 days`，仍按 **`end_time ∈ (start,end]`** 归属；保留起止+sport_type精确投影去重；互不重叠多来源可计数 | 成功无归属报告为0；失败/超限/坏身份/同原生身份冲突/跨来源非相同投影重叠为null |

步数自0.6.3按已核实的来源内半小时累计、跨来源取大规则消歧，首尾部分槽仅统计周期内实测；重复DST时段保留真实offset，详见[核查证据](<step-multisource-rules.md>)和[修复验收](<../verification-step-sources.md>)。0.6.4热量/中高强度/运动的多来源规则分别按[原生核查和工程边界](<activity-multisource-rules.md>)实现，不因全周期来源多就统一未知。热量保留小数而非日报整数；中高强度按精确时间并集而非来源相加；运动精确投影去重与跨源重叠unknown是工程保守策略，不能冒称原生按sid/key/time计报告就是物理运动去重。`sport_report`还校验起止顺序和不超过7天的单次时长。`VALID_STAND`、自然日日报、累计`sport_summary`或未核实业务字段都不能代替周期运动次数。历史本地 `daily_activity.activity_count=null` 保持历史含义，与新周期 `sport_count` 分离。

## 4. 屏幕与模型事实

- [EventClient](<../../android/src/cn/local/micompanion/EventClient.java>)先冻结健康类别，再取其有效周期采集屏幕；[ReadOnlyProbe](<../../android/device-tests/ReadOnlyProbe.java>)使用同口径。没有周期、未来/非法区间或超过7天时，不查询自然日兜底。
- [UsageCollector](<../../android/src/cn/local/micompanion/UsageCollector.java>)和[UsageTimeline](<../../android/src/cn/local/micompanion/UsageTimeline.java>)将UsageEvents积分裁切至相同起止，输出亮屏、解锁使用时长及前台时长top10。仅前查一日建立状态；未知初态不补造，缺事件会低估，并非系统数字健康全天精确统计。事件乱序或超过100000条则失败，不返回部分结果。
- [HealthText](<../../android/src/cn/local/micompanion/HealthText.java>)开头写一次本地读取区间，活动各一行，当前主睡眠所有阶段和全周期有效桶逐项显示；缺失明确“暂无数据”。屏幕起止不能与健康精确核对则“未知”。不给模型源epoch、账号、sid/did、自由文本或未核实元数据。
- [OfficialApi](<../../android/src/cn/local/micompanion/OfficialApi.java>)按 `WeatherText.snapshot` → `HealthText.snapshot` → `AdvicePrompt.message` 生成内存视图：0.5.0天气固定格式器仍使用；原输入、本机冻结任务不改写。固定整理不调用AI，只有随后官方chat生成建议的一次调用，没有额外AI预处理。
- 官方路由、人格匹配、prompt/Key保存、自动trigger规则及uncertain send（`delivery_unknown`不重发）均不变。图标在后续 0.6.1 版本更新，与周期算法无关。

## 5. 安全上限与失败范围

| 对象 | 上限与处理 |
|---|---|
| 睡眠查找 | 14天、64条 + 1 sentinel；超限周期不可用，不只取最新64条伪装完整 |
| 周期 | 7天；超过则不可用，不回退自然日 |
| 每指标/活动明细查询 | 12000条 + 1 sentinel；超限相应指标/总数未知，不截断累加 |
| 当前主睡眠阶段 | 512；超限周期失败，不裁掉后续阶段 |
| 周期JSON | 160 KiB UTF-8；超限周期失败 |
| 健康类别/Provider单类别 | 各256 KiB UTF-8；超限拒绝/安全失败 |
| 完整事件 | 256 KiB UTF-8；超限停止，不发送、不截断事实 |

源单行JSON仍有1 MiB输入保护，严格JSON/结构深度及后续白名单边界继续生效。各限额分别检查，不意味着类别以内就一定能放入完整事件。缺表/坏JSON/明细超限通常隔离为相应指标null；睡眠边界/阶段冲突、阶段或周期大小超限使整个周期不可用。未知不是0，完整成功查询的真实零才保留。

## 6. 验证状态与交付边界

- 主机合成证据：[HealthCycleTest](<../../android/tests/HealthCycleTest.java>)覆盖午睡不切界、仅当前主睡眠、全部阶段、缺测不继承、半小时端点、小数卡路里、跨界运动及多sid歧义；[HealthTextTest](<../../android/tests/HealthTextTest.java>)覆盖固定文本、输入不变、缺失、全部有效分段及严格JSON；[ScreenCycleBoundaryTest](<../../android/tests/ScreenCycleBoundaryTest.java>)和[SnapshotFreshnessTest](<../../android/tests/SnapshotFreshnessTest.java>)覆盖冻结边界、过期/未来/非法周期及不补造终点。合成测试不能代替运行时兼容性验证。
- 完整回归入口见[测试脚本](<../../tools/test.sh>)。隔离 HTTP 或接口预检不能证明真实模型输出、机器人送达或已读。
- 实现及交付清单见 [周期计划](<../superpowers/plans/2026-10-17-sleep-cycle.md>)。源码包仅明确白名单，不包含临时逆向资料、私有快照、账号库、构建私有数据或密钥。
