# 0.2.0 扩展快照历史说明

本文记录早期快照版本的功能与验证范围，不代表当前周期协议。后续版本改为官方 API 和睡眠周期事实，参见[项目说明](<../README.md>)。

## 数据投影

- 天气、健康、屏幕各使用白名单字段，区分采集时间与源更新时间。
- 缺失字段显式为 null，真实零保留；跨日活动不能继续冒充当天数据。
- 各指标读取失败相互隔离，不因单个记录损坏而丢弃全部可用类别。
- 原始数据库、账号、凭据及完整自由 JSON 不属于公开输出。

## 测试范围

[协议测试](<../android/tests/ClientProtocolTest.java>)与[载荷测试](<../android/tests/ClientPayloadTest.java>)覆盖鉴权地址、UUID、时间、缺失及序列化；[健康源测试](<../android/tests/HealthSourceTest.java>)覆盖记录选择、单位、预算及异常隔离；[天气投影测试](<../android/tests/WeatherProjectionTest.java>)覆盖预报、预警与缺失；[触发测试](<../android/tests/TriggerStateTest.java>)覆盖严格时间边界和中断恢复。

历史插件的隔离 HTTP、SQLite 状态机与适配器测试保留在[Python 测试](<../tests/test_astrbot_core.py>)中，不表示当前 APK 仍依赖插件。

## 限制

- 替身响应、HTTP 成功及状态返回不等于真实生成、发送、已读或后台耐久保证。
- 数据源未运行或未同步时只能使用缓存并标记陈旧或缺失。
- 只读连接不等于多表事务快照；不同源版本可能改变结构。
- 未核实的单位、活动次数或传感器字段不得猜测。
- 早期自然日活动与当前睡眠周期不可混用，当前规则见[周期结构](<research/health-cycle-schema.md>)。
