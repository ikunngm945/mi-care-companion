# 步数多来源聚合规则

## 原生静态路径

`StepBiz.splitDailyReport` 的多来源规则为：

1. `RepoUtils.groupedBySid` 按非空 sid 分来源，不在此按品牌优先。
2. 每来源独立 `groupDataByGap`，`RepositoryManager.GAP_X_MINUTE=1800` 秒；来源内调用 StepItem.add 累加。
3. 每来源生成半小时 StepItem，time 为槽起点。
4. `BigValueDistinct` 在相同槽时间比较 getPrimaryValue().intValue()，保留较大值；StepItem 的 primary value 是 steps。
5. 按展示粒度汇总并累计最终记录。

因此是来源内先累加、跨来源半小时取大，不是逐分钟 max、全周期选最大来源或直接跨来源相加。静态类及混淆名可能随源版本变化，不保证运行时兼容。

## 睡眠周期扩展

[HealthCycle.steps](<../../android/src/cn/local/micompanion/HealthCycle.java>)先裁切真实 `time ∈ (start,end]`，再按当地半小时累计并逐槽取大。首尾不完整槽仅统计周期内实测，不冒称原生自然日日报总数；DST 重复时段保留实际 offset。

同来源同真实时间相同值去重、冲突未知；坏来源、非整数、负数、溢出或查询超限未知。时间标签 timeIn0Tz 不当作真实采样 epoch。来源 ID 不进入模型事实。

## 证据边界

算法核查结合定向 DEX 与可读 Java 控制流，不能把扫描到的候选调用当作完整反汇编或把局部可读方法当作全类反编译成功。源类符号用于说明算法，不依赖临时输出路径。

[StepCycleTest](<../../android/tests/StepCycleTest.java>)覆盖多来源、乱序、冲突、边界、时区、DST、溢出与 sentinel。合成测试不验证所有源版本，不能用自然日日报代替周期实测。

热量使用独立的 BigCaloriesDistinct，不能取“步数赢家”的热量；详见[其他活动规则](<activity-multisource-rules.md>)。
