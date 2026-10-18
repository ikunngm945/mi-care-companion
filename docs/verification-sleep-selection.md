# 0.6.5 已同步睡眠仍显示上一次：修复与验证

## 根因与改动

旧版周期查询额外要求 `isComplete=1`。源应用已保存可读取的新主睡眠时，该标志仍可能为0；旧条件会排除新记录，导致模块继续选择上一条主睡眠，周期起点也不推进。

0.6.5仅移除这个额外条件，继续要求未删除、`key='sleep'`、数据库结束时刻位于查询窗口，且通过JSON起止、源分钟时长、冲突与阶段边界校验。主睡眠仍按源 `duration >= 180` 分钟判定，查询14天、64条加sentinel；需要最近两次有效主睡眠，周期起点为前一次主睡眠结束，终点为实际采集时刻。午睡不切界，缺失不填0，不使用自然日日报替代。

实现见[HealthCycle](<../android/src/cn/local/micompanion/HealthCycle.java>)，总体边界见[周期结构](<research/health-cycle-schema.md>)。

## 原生静态证据

已通过命令行调用 IDA MCP，核对目标DEX的Dalvik指令；这是指令级核查，不冒称Hex-Rays反编译。以下方法链与本地静态阅读结果一致：

- `FitnessDataGetterImpl.getAllDailyRecord` → `DailyRecordDaoUtils.getAllDailyRecord` → `queryDailyRecord$default` → `queryDailyRecord` → `getSQLiteQuery`。普通读取传入的额外SQL条件为null；查询构造器包含时间、未删除、可选key/sid、排序与限额，没有隐含的 `isComplete=1` 条件。
- `ItemEntityUtil.convertEntityToItem`校验载荷时间并转换元数据，不再额外按该完整状态过滤。
- `SleepSegmentEntity.setFromLocalRecord`从 `DailyRecordItemModel.isCompleteSleep`复制标志，缺省为true；`MiParsedDataRecorder.recordSleepSegment`将 `AllDaySleepParseData.isSleepFinish`传给记录器。
- `DailyRecordDaoUtils.reprocessModelRecordIfNeed`在后续入库处理中查找并处理未完整睡眠片段。

因此，`isComplete`是源端睡眠完整状态，不是是否已同步或是否允许读取的标志。为0的载荷不应直接丢弃，也不能称为最终定稿；后续源端仍可能更新。此证据只支持上述读取与字段语义，不宣称覆盖任意源应用版本或全部显示合并策略。

## 回归与只读验证

[真实SQL集成测试](<../tests/test_health_cycle_sql.py>)使用合成SQLite，执行生产查询和周期逻辑，而不是忽略SQL条件的固定返回值：

1. 已结束、标志为0的新主睡眠应被选中；同一时期存在较晚短睡眠时，短睡眠不替代主睡眠。
2. 标志为1的主睡眠保持原行为。
3. 标志为0的短睡眠不推进周期。
4. 已删除记录不参与选择。
5. 未来结束的记录不充当已结束睡眠。
6. 非法源时长仍明确失败，不回退旧记录伪装成功。

修复前观察到新主睡眠被排除及非法载荷未进入校验的预期失败；修复后六项通过，完整[测试入口](<../tools/test.sh>)通过。测试连接使用只读SQLite并核对文件哈希不变。

另外以独立只读验证程序对同一运行时源库执行新旧选择逻辑，确认新版选中较新的主睡眠、保留全部来源阶段、推进周期起点且不改变采集终点。测量值、账号、路径和原始健康记录不写入本文，也不随源码发布。验证未调用模型或IM接口；覆盖安装后确认版本与安装包哈希一致，未清除应用数据。

## 升级与边界

使用原签名覆盖安装，更新后需重启小米运动健康源进程，使LSPosed加载新版Hook；保持原作用域，不清数据。后台存活与准点刷新不作保证，已有快照仍受新鲜度检查。

天气、活动累计、提示词、官方路由、自动触发及投递未知状态不重发规则不变。本次验证不能代替真实模型建议质量、机器人送达或医学准确性验证。
