# 健康与屏幕睡眠周期设计

## 目标

以前一次主睡眠结束至 Hook 实际采集时刻为统一冻结区间，使用固定算法输出活动总数、全周期实测半小时范围及当前主睡眠全部阶段。不使用 AI 预处理，不回退自然日。

## 架构

- [HealthCycle](<../../../android/src/cn/local/micompanion/HealthCycle.java>)使用独立只读查询生成有界周期白名单。
- [EventClient](<../../../android/src/cn/local/micompanion/EventClient.java>)先冻结健康，再按同一起止采集屏幕。
- [SnapshotFreshness](<../../../android/src/cn/local/micompanion/SnapshotFreshness.java>)验证周期新鲜度，不补造终点。
- [HealthText](<../../../android/src/cn/local/micompanion/HealthText.java>)只改生成请求的内存视图，原始快照不变；天气固定格式器仍先执行。

## 数据约束

- 主睡眠按源 duration >= 180 分钟选择最近两次有效、已完成、结束不同的记录；午睡不切界。
- 样本与相邻睡眠阶段采用 `(start,end]`，缺测不沿用前段，不从日报恢复曲线。
- 多来源活动使用当前[周期结构](<../../research/health-cycle-schema.md>)中的规则；不得用早期多 sid 一律未知的方案覆盖后续修复。
- 屏幕完全共用健康区间，未知初态不补造，事件缺失可能低估。
- 源库只读，不迁移、checkpoint、改权限或强制联网刷新；账号歧义失败。
- 各查询有 LIMIT 与 sentinel；阶段、周期和事件有 UTF-8 预算，超限失败而非截断事实。
- 不输出账号、来源身份、坐标、轨迹、原始自由 JSON 或凭据。

## 验证目标

1. 周期选择、睡眠重复与冲突、端点、DST、阶段缺测及查询限额。
2. 屏幕积分、相同起止、缓存超时、未来与非法时间、不补造终点。
3. 固定格式、全部有效分段、缺失、原输入不变、单次生成请求。
4. 升级及权限验证须使用独立授权流程；构建成功不代替运行时兼容性。
5. 隔离 HTTP 不代替真实生成、平台发送、已读或长期后台保活。

[测试脚本](<../../../tools/test.sh>)提供回归入口；[功能说明](<../../verification-sleep-cycle.md>)记录协议及边界，不公开个人设备验收日志。
