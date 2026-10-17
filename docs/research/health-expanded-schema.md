# 扩展健康每日快照：结构、单位和时间证据

本文记录 `com.mi.health` 的结构与静态类/方法证据，不包含健康记录或运行环境。内部实体与 schema 可能随版本变化，不是所有版本通用协议。

## 范围与接口

- `HealthSource.collect(Context, JSONObject)`：在健康应用 UID 内运行，返回规范六键 `status/source_package/collected_at/source_updated_at/fields/reason`。传入 base 不原样拷贝，避免未知键带出。
- 独立 `SQLiteDatabase.OPEN_READONLY | NO_LOCALIZED_COLLATORS` 连接读取 `fitness_summary` 与 `fitness_data`，由 SQLite 正常读取 WAL；不使用 immutable、不 checkpoint、不迁移、不改源权限。
- 路径优先用 `LocalCollector.activeHealthPath`（canonical 路径限定应用数据库根下且文件名为 summary），否则仅在唯一非 `no_account` 候选时选取。多个账号拒绝猜测，不合并账号。
- 每个日报/明细查询最多四行，目标一行；原始 JSON 单行上限 1 MiB。只解析这些最新有限行，而不是读取全量历史 JSON。最近记录无七天限制。
- 固定白名单不输出原 JSON、sid/sidList、did、账号、坐标、自由文本、音频、完整序列；没有输出数组。固定文本均小于 4096 字符；最终 UTF-8 类别小于 64 KiB，超限则安全失败。
- `metric_status` 逐项 `ok/missing/error`，缺表/坏 JSON 仅隔离该指标；不传源异常文本。缺失数值为 null，不补零。步数等有意义的真实零保留。

## 证据来源和结构

以下字段语义来自实体、资源及定向 DEX 核查。读取实现应以只读 schema 探测为准，不能根据字段名或数值量级猜单位：

- `DailyStepReport`、`FitnessDailyReportDao`、`FitnessDailyReportDaoProxy` 确认日报结构与源已聚合语义。
- `CalorieDataUtil.createSummaryModelList(Context, DailyCalorieReport, int)` 直接把 `getCalories()` 交给 `common_unit_calorie` 资源；`DailyCalorieReport.getCalories()` 原值返回。ARSC `common_unit_calorie`（资源 ID `2131951634`）为 `kcal` / `千卡`，所以 **CALORIE 日报值直接 kcal，不除以 1000**。
- `StepDataUtil.createDistanceSummaryModel` 调用 `no7.e(int)` 和 `no7.i(int)`：小于 1000 原值展示米，达到 1000 除以浮点 1000 并展示公里；`no7.d` 同时有明确 `common_unit_meter_desc_new/common_unit_kilometer_desc_new` 分支。因此 **STEP.distance 是米**。
- `WeightUnitHelper.convertKgToDisplayUnit(float)` / `convertDisplayUnitToKg(float)` 明确以 kg 为持久基础，展示可切换 jin/lb；输出 kg，不按用户展示偏好二次缩放。明细 `weight_item_record.value.weight` 与汇总 `weightRecordList[].weight` shape 已核验。
- `BloodSugarItem` 的 `@SerializedName("blood_sugar")` 与本地 shape 相符；添加页直接构造该实体，ARSC `health_blood_glucose_unit_dialog` / `health_blood_glucose_finger_meal_time_title_unit` 明确 mmol/L。
- `BloodPressureItem` 的 `@SerializedName("systolic_pressure")` / `@SerializedName("diastolic_pressure")` 分别映射 highPressure/lowPressure；ARSC `health_blood_pressure_unit_short` 明确 mmHg，输入范围资源以相同单位标注。
- `DailyMHStrengthReport.duration`、`recordList[].minutes`，及 `widget_tb_rainbow_has_strength` 的中高强度分钟文案共同支持分钟。`VALID_STAND.standCount` 按站立次数导出，中文 widget 文案也是“次”；不升级声称每次严格等于达标一小时。
- `GoalItem` 的 SerializedName 确认 `field/target_value/achieved_value`；`DailyGoalReport` 为 `goal_items/date_time`。`GoalType` 静态常量 `STEP=1, CALORIE=2, VALID_STAND=3, MH_STRENGTH=4`。本地 `rainbow.value` shape 相符。只取当天目标；未知 field 不输出。
- `SleepSegmentReport` SerializedName 明确 `duration/sleep_deep_duration/sleep_light_duration/sleep_rem_duration/sleep_trace_duration/sleep_awake_duration/awake_count/bedtime/wake_up_time`。`SleepState` 为 `NOT_SLEEP=0, DEEP_SLEEP=2, LIGHT_SLEEP=3, RAPID_EYE_MOVEMENT=4, AWAKE=5, SLEEP_TRACE=6`。**trace 不是 REM**；无 rem 字段时 null，禁止用余数补齐。分钟单位延续此前明细时长与起止时间差算术核验。

## 时间语义

- `today_*` 与 `daily_activity` 只代表本地当前日期对应的应用日期标签；日报 `timeInZero` 以 UTC 格式化为报告日期，不能当真实午夜采样 epoch。查询当天标签区间；同日取 `updateTime DESC` 最新来源，不 SUM sid。
- `report_time_label_epoch_seconds` 是日报标签，不冒充测量 epoch。`source_updated_at_ms` 是日报缓存/聚合更新时间，不冒充传感器测量时间。
- 每个日报附 `report_date/is_today/is_historical`；每个最新记录附 `report_date/is_today/is_historical/sample_time_epoch_seconds`。真实记录 `time` 是 Unix 秒，**不使用 timeIn0Tz 伪 epoch**。
- 睡眠最近记录按 `endTime DESC` 选取，`sample_time_epoch_seconds` 使用 `wake_up_time`，并输出 `bedtime_epoch_seconds/wake_up_epoch_seconds`。不因入睡跨午夜而丢弃。
- 每类最近记录不限七天，但排除未来时间、已删除记录，最多尝试四条坏/无效记录后的有效样本。日报与明细存在同一指标最新采样时，比较真实 epoch，不让更旧明细替换较新日报内的最新样本。
- 类别 `source_updated_at` 仅取已投影日报更新时间或真实样本时间的最大值，不表示所有指标同等新鲜。主调用方负责发送时再次重算跨日状态。

## fields 白名单

| fields 项 | 数值白名单 / 单位 | 时间与状态 |
|---|---|---|
| `today_steps` | `steps` 次、`distance_m` 米；`step_kcal=null`（STEP 热量缩放未单独确认） | 当天标签；无当天数据则 null |
| `today_calories` | `activity_kcal`，直接 CALORIE 日报 kcal | 当天标签；不把 STEP 热量相加 |
| `today_moderate_vigorous` | `moderate_vigorous_minutes` 分钟 | 当天标签 |
| `today_stand` | `stand_count` 次 | 当天标签 |
| `today_goals` | `steps/activity_kcal/stand_count/moderate_vigorous_minutes` 各 `{target,achieved}` | 当天标签；目标本身不使类别变成已测量 |
| `daily_activity` | 汇集上述当天数值及 `goals`，不重复计算；`activity_count=null` | 当天标签；每个子项独立缺失 |
| `heart_rate_summary` | `average_bpm/minimum_bpm/maximum_bpm/resting_bpm` | 最新日报日期，不限七天 |
| `spo2_summary` | `average_percent/minimum_percent/maximum_percent` | 最新日报日期 |
| `stress_summary` | `average_score/minimum_score/maximum_score`，无医学单位 | 最新日报日期 |
| `sleep_summary` | `duration_minutes/deep_minutes/light_minutes/rem_minutes/awake_minutes/trace_minutes/score/average_bpm/average_spo2_percent` | 最新日报日期；日报得分不强行拼到单个睡眠片段 |
| `training_load_summary` | `weekly_load/load_level_code`，应用负荷分数/枚举 | 最新日报日期 |
| `hearing_summary` | `total_duration_raw/high_duration_raw/duration_unit=unverified` | 最新日报日期；不猜原始时长单位 |
| `latest_heart_rate` | `bpm` | 真实样本 epoch |
| `latest_spo2` | `percent`，1..100 | 真实样本 epoch |
| `latest_stress` | `score`，1..100，不是血压 | 真实样本 epoch |
| `latest_sleep` | 分钟分期、起止 epoch、`awake_count`、片段自身得分/平均HR/SpO2（若存在） | 真实醒来 epoch |
| `latest_weight` | `kg/bmi` | 真实样本 epoch；非常旧记录保留并标历史 |
| `latest_blood_sugar` | `mmol_per_l/measurement_period_code` | 真实样本 epoch |
| `latest_blood_pressure` | `systolic_mmhg/diastolic_mmhg/pulse_bpm` | 真实样本 epoch；缺少有效行时不造示例数据 |
| `latest_training_load` | `weekly_load/load_level_code` | 真实表记录 epoch；date_time 不替代真实 epoch |
| `latest_vitality` | `accumulated_score/high_intensity_score/medium_intensity_score/low_intensity_score` | 真实表记录 epoch |
| `latest_hearing` | `start_epoch_seconds/end_epoch_seconds/high_noise` | 真实表记录 epoch；不输出音频/设备信息 |
| `metric_status` | 固定指标名各 `{status}` | error 与 missing 隔离 |
| `measurement_note` | 固定解释文案 | 无源自由文本 |

## 尚未核实 / UI 对照局限

- STEP.calories 没有独立证据确认缩放，故不输出 kcal；CALORIE 是独立应用日报，不以数量级猜测。
- `sport_summary` 的累计汇总不能当当天活动次数；`activeStageList` 的业务语义未确认，历史 activity_count 保持 null，独立周期运动次数另见[周期结构](<health-cycle-schema.md>)。
- 未核实的体成分、体温、VO2max、能量、PAI 等字段不能猜单位或拼值；无有效行时明确缺失。
- 实体、schema 和静态代码核查不等于每张 UI 卡片都已动态验证；历史记录不能伪装为今日测量。
- 若最新四行均坏或无效，只返回该项 error/missing；不会为找数据无界扫描历史。不同表使用独立只读查询，非跨表同一事务原子快照。

## 合成验证

`HealthProjectionTest` 与 `HealthSourceTest` 使用手写非真实 fixtures，javac + json.jar 运行。覆盖真实零、非有限/越界值、历史日期、不限七天、同指标最新 epoch 优先、未来样本剔除、目标代码、未知目标剔除、表异常/坏JSON隔离、所有查询有 LIMIT、无 SUM sid、base/异常隐私过滤、输入/输出大小保护、目标配置不伪造测量。Android API 仅在只读连接边界；纯 JVM 的 Reader 测试断言实际 category 输出，不执行 android.jar stub 方法。
