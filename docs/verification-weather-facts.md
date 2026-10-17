# 0.5.0 天气固定事实精简

## 实现

[WeatherText](<../android/src/cn/local/micompanion/WeatherText.java>)以固定算法把生成请求中的 `weather.fields` 转为 `weather.facts`，不调用 AI 预处理，不覆盖原始快照或本地任务数据。其他类别及包络保留原语义。

- 天气码按已核实映射显示，未知码保留，不猜晴天。
- 整数 UV 使用等级表；小数仅保留指数，不擅自取整。
- 保留已采集的预报、预警、生活指数、污染物和分钟序列，不截断事实。
- 逐小时主轴仅在温度预报起点与原 hour_index 可核对时构造；其他独立源时间不被擅自当作相同有效时点。
- 只有明确时区和日期才判断采集当天，不把首日一律称今天。
- 数值、单位、缺测与数组空位保留；未知采样间隔或单位不猜。
- 严格 JSON、有界解析及稳定转义避免宽松语法与跨平台编码差异。

算法依据见[天气显示规则](<research/weather-display-rules.md>)。

## 测试

[WeatherTextTest](<../android/tests/WeatherTextTest.java>)覆盖天气映射、UV、跨日小时缺口、独立源时间、完整数组、Unicode、稳定编码及未知字段；[RouteApiTest](<../android/tests/RouteApiTest.java>)覆盖格式器接入、原输入不变及每事件仅一次生成请求。

[WeatherPreview](<../android/tests/WeatherPreview.java>)只读输入并创建新输出，不联网、不写回输入。格式紧凑不构成 token 或模型费用减少的固定比例承诺。

## 边界

天气事实仍是不可信数据，AI 仅负责后续建议。隔离请求测试不代表真实生成、送达、已读或长期后台运行。预报、观测与缓存时间不得混用，缺失不补零，不借上一时段值补测量。
