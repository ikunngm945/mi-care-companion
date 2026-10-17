# 活动热量、中高强度、运动报告：多来源核查与周期口径

本文记录 `com.mi.health` 的静态算法、表结构与工程边界，不包含个人设备、健康样本或部署配置。内部类与 schema 可能随版本变化。0.6.4 实现沿用睡眠周期，不替换成自然日日报。

## 热量

- `CaloriesBiz.splitDailyReport`：收集`calories`（原生同时支持开放数据key，本助手本轮仍仅读已支持key），`RepoUtils.groupedBySid`按来源分组；每来源以`RepositoryManager.GAP_X_MINUTE=1800`分槽，累计来源槽内`CaloriesItem.getCalories()`；`BigCaloriesDistinct`在相同槽timestamp选择较大热量。
- `CaloriesBiz$BigCaloriesDistinct.distinct`比较的是calories，不是步数、时长、设备品牌、全周期总和或逐分钟极值。`CaloriesItem`的calories是float，`add`相加。
- 原生日报把最终半小时热量`roundToInt`，再加总图表整数；不能把日报与本助手保留小数的周期实测混称。周期实现以BigDecimal保留小数，不做中间浮点累计误差或每槽整数取整。
- 真实表`calorie_record`主键`(key,sid,time)`，value包含`time,calories`；`timeIn0Tz`为本地日期标签轴，不是采样epoch。
- 周期扩展：先裁切真实`time ∈ (start,end]`，同来源同time重复相同值去重、不同值unknown；再来源内本地半小时sum、槽间来源max，最后sum。未知来源不能合并到虚构设备。首尾部分槽仅用周期内实测，DST重复时段保留真实offset。

## 中高强度

- `MHStrengthItem`仅含time，未包含可累加duration或强度数值。
- `StrengthBiz.splitDailyReport`把`intensity`样本按真实`getTimestamp()`写入`LinkedHashMap<Long,MHStrengthItem>`，跨来源同timestamp覆盖后计数。半小时记录`MHStrengthRecord.minutes`为去重后桶内样本数；全天duration为时间并集样本数。sidList只用于元数据。
- 因此不是每来源分钟相加，也不是每个半小时取最多来源的计数。不能假定 timestamp 总为60秒对齐，不能擅自floor到分钟或加容差；本轮保留原生精确timestamp去重和原有分钟标签。
- 周期仍先裁切`(start,end]`，验证JSON/SQL时间一致，计数exact timestamp并集；空/坏/超限仍unknown，不把缺测当零。

## 运动次数：必须区分仓储身份与物理运动

独立静态审查以下路径：`SportReportEntity`、`FitnessDatabase`、`SportReportDao`、Room生成DAO`e0q`、`SportDataExtKt.toSportDBKey`、`SportReportUpdater`、`SportReportDaoUtils`及公开getter/来源名称补充。

- 原生唯一身份为`(sid,key,time)`；生成SQL `INSERT OR REPLACE`。key由sportType或schema映射，time优先dbKeyTime，否则startTime；endTime不参与主键。故不能一律断言SQL time必定等于start_time。
- 原生列表按timeIn0Tz和未删除过滤，逐行解析返回；近期数直接`count(*)`。同sid/key/time可替换，云同步对本地未上传记录有保护；不同sid、不同key、不同开始时间可并存。
- 检查路径未见跨sid精确或近似运动合并；不能凭相同起止type证明同一物理运动，更不能杜撰5秒/1分钟近似合并或来源优先级。服务器/固件内部行为未核查，结论只涵盖所述路径。
- 本助手现有规则与原生有意不同：完成时间`end_time ∈ (start,end]`归属，最多7天的报告并回查7天；精确`(start_time,end_time,sport_type)`投影去重。本轮保留此规则，不冒称原生物理去重。
- 改善：取消“只要整个周期多sid就unknown”；互不重叠来源报告可计数；非完全相同投影在跨来源有时间重叠时，因证据不能区分同运动重复采集和不同运动，仍unknown；同原生身份有冲突投影也unknown。相邻但不重叠的报告是不同记录。使用有界排序扫描而非全表两两比较。
- 报告非空来源/key、起止、数值type以及存在的JSON time与SQL time一致性被校验；同投影的额外详细指标差异不影响本字段计数，源自由文本/身份不导出。成功无报告为0，查询失败/坏数据/超限为unknown。

## 静态证据边界

定向 DEX 解析与可读 Java 控制流交叉核查：CaloriesBiz 的 groupedBySid/groupDataByGap/getCalories/groupByHalfHour/roundToInt，BigCaloriesDistinct 的时间键与热量比较，StrengthBiz 的 timestamp Map.put、List.size 及日报构造。

扫描到的 invoke candidates 可能包含操作数字节误匹配，不能视为完整反汇编；局部可读方法也不能证明整个类反编译成功。结论只涵盖所列路径，不外推服务器、固件或未检查的数据源版本。
