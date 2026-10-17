# 0.6.4 周期热量、中高强度与运动次数

保留既有睡眠周期 `(start,end]`、步数、睡眠阶段、心率等实测规则及官方投递行为，只修改[HealthCycle](<../android/src/cn/local/micompanion/HealthCycle.java>)的三个活动字段。算法依据及证据边界见[活动规则](<research/activity-multisource-rules.md>)。

## 实现

- **活动热量**：来源内按当地半小时累计，同槽跨来源取大。先裁切周期，使用 BigDecimal 保留小数，不照搬自然日日报每槽 roundToInt。未知来源、坏值、冲突或超限为未知。
- **中高强度**：按真实 timestamp 并集计样本分钟，不把来源相加、不擅自 floor 到整分钟，空数据仍未知。
- **运动次数**：保留起止与类型的精确投影去重以及完成时间归属。非重叠多来源可计数；跨来源非相同报告重叠、同原生身份冲突仍未知，不猜容差。原生 DB 身份为 sid/key/time，time 可保留 dbKeyTime，不强制等于 physical start。
- 精确投影去重是工程保守约定，不把原生仓储计报告误称为物理运动去重。

## 回归

[ActivityCycleTest](<../android/tests/ActivityCycleTest.java>)覆盖：

- 来源内累计后取大、各槽切换赢家、小数和输入顺序。
- 重复与冲突、首尾裁切、半小时端点、非整小时 offset、DST、零与缺失。
- 坏值、未知来源、时间不一致、行数 sentinel。
- 运动完成端点、跨周期运动、dbKeyTime、身份冲突、嵌套区间、同 end、多来源投影 provenance 与交叠未知。

本次记录的完整回归为 27 个 Java 测试入口及 30 个 Python 测试通过。隔离测试不替代真实生成、机器人送达或已读。屏幕起止必须与健康周期一致；类别历史字段陈旧不授权改写周期时间。

## 边界

没有证据的近似运动合并仍不实现；未扩展开放第三方 key，未修改其他指标或周期口径。签名私钥、真实连接设置、健康数据和构建缓存不进入源码交付。

[GitHub Release](<https://github.com/ikunngm945/mi-care-companion/releases/tag/v0.6.4>) 提供安装包与源码附件。
