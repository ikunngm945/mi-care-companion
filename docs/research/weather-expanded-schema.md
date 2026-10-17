# 天气每日快照扩展：白名单、单位与结构

## 范围与证据

- 对象为 `com.miui.weather2` 的 DE SQLite 缓存。采集只读，不主动请求天气刷新，不上传私有数据库；内部类和 schema 不保证跨版本兼容。
- 静态线索包括 `W1.c`、`WeatherData`、`Z1.j1`（下文称 WeatherPersist）、`IndicesData`。字段结构与静态算法分别说明，不将其视为运行时 Hook 兼容性证明。
- 实现：[WeatherSource.java](<../../android/src/cn/local/micompanion/WeatherSource.java>)、[WeatherProjection.java](<../../android/src/cn/local/micompanion/WeatherProjection.java>)。

## 接口与协议

```java
WeatherSource.collect(Context context, JSONObject base) -> JSONObject
WeatherProjection.project(JSONObject raw, JSONObject cache) -> JSONObject fields
WeatherProjection.category(JSONObject base, JSONObject raw, JSONObject cache, long now) -> JSONObject
```

类别严格只有 `status, source_package, collected_at, source_updated_at, fields, reason` 六键。`base` 只读取合法的 `collected_at`，其他键不透传；缺失采集时间以当前 ISO8601 时间补齐。类别 `source_updated_at` 只使用 `current.pubTime`，不以缓存时间/预报时点冒充当前观测。超过六小时标 `stale`，不会因此删除未来预报；接收端可重新计算新鲜度。

`cache` 仅用于纯投影与适配，内部键为 `weather, aqi, alerts, minute, insert_time, information, information_insert_time`。所有输出重新构造；绝不转发任意 JSON 子对象。

## 只读 SQL、版本容错与城市边界

- `context.createDeviceProtectedStorageContext().getDatabasePath("weather.db")`；`OPEN_READONLY | NO_LOCALIZED_COLLATORS`。无 checkpoint、写事务、权限修改或源连接复用。
- `SELECT posID FROM selectedcity ORDER BY position ASC LIMIT 1`。标识只用于同城绑定参数，不进入输出。没有首页城市不猜其他城市，不聚合多个城市。本实现也不输出城市显示名。
- 对 `raw, weather, aqiinfo, alertinfo, information` 逐表 `PRAGMA table_info` 后，仅选择存在的白名单列，单表查询异常不阻断其他表；单单元格读取/解析失败不抹掉同类其他字段。
- 原 JSON 输入最大 1,048,576 字符、递归结构深度最大 32；不合法/过深的单个 JSON 回退为空对象，其余 SQL 来源继续。
- JSON/SQL 缺失用 `null` 或空数组。固定 `source_notes` 说明缺表/缺列，不输出异常消息、数据库路径或 SQL 绑定参数。
- 天气数值统一先 `getString()` 再做有限数值转换；不依赖 SQLite affinity。NaN/Infinity/不合理值为 null。

### 重要修正：SQL day=0 不是今天

旧结构说明中把 `weather.day=0` 当当前，需以本次静态证据修正：

1. `W1.c.h` 先将 `yesterday` 的高低温、日出日落等 append 到 ForecastData，再 append `forecastDaily`。
2. `WeatherPersist.h` 在 `i6 == 1` 写当前天气描述，`timestamp = forecastPubTime + (i6 - 1) * day`；`tmphighs/tmplows` 也按相同索引写入。
3. 因此此版本 `weather.day=0` 是昨天，`day=1` 才对应源预报首日/今天。新增 Source 只读取 `day=1`，绝不缺失时 fallback 到 0 当今天。
4. 若未来 schema 根本没有 day，仅保留同城单条独立当前/格式化字段，不导出无法确认日期的 SQL 高低温/日出日落。
5. `forecastDaily.value[0]` 没有上述 yesterday 前缀，仍为源日预报首日。

## fields 白名单与解释

| 输出 | 来源 / 语义 | 单位与时间 |
| --- | --- | --- |
| `description` | `weather.description`（day=1）；格式化 UI 描述 | 仅该表 publish_time 缓存时间，不代表观测时间 |
| `temperature, feelsLike, humidity, pressure, visibility` | `current.*.{value,unit}`；缺少对象可回退 SQL 格式化列 | 保留显式 unit；SQL 仅当字符串本身有单位后缀才识别，pressure 可用 pressure_unit；无则 null |
| `wind` | 当前 speed/direction；SQL wind/风向/风力作为格式化文本 | raw 显式 `km/h`、`°` 不转换；不猜 SQL 风力为 m/s |
| `weather_code, uv_index` | `current.weather/uvIndex`，UV 可 SQL 回退 | 天气码不是自然语言；UV 为指数 |
| `observed_at` | `current.pubTime` | 只接受带偏移 ISO8601，含毫秒也可 |
| `cache_updated_at_epoch_ms, formatted_cache_updated_at_epoch_ms` | raw.insert_time / weather.publish_time | 缓存更新时间独立保留，不代替 observed_at |
| `air_quality` | raw.aqi 优先，逐字段回退 aqiinfo | 包含 AQI、PM2.5/PM10/SO2/NO2/CO/O3、primary/suggest/污染物说明。污染物浓度 unit=null，每项带源时间或单位/时间未知说明 |
| `today` | 源 forecastDaily 第0项，不保证为采集当天 | 带 forecast_date/day_note；SQL备用高低温 unit=null，不从未验证unit枚举猜测 |
| `forecast_daily[]` | 温度高低、日出日落、天气昼夜码、AQI、降水概率、from/to风 | 最多15项；from=高温、to=低温，由 parser 明确调用 addTemperatureHigh/Low 证实；sunRiseSet.from/to分别日出/日落 |
| `forecast_hourly[]` | 温度、天气码、AQI、概率、风 | 最多24项；每项 hour_index 与各分项 pubTime。风 datetime 单独保留；不把温度pubTime套给其他数组，不盲目推算第i小时实际时点 |
| `hourly_description, hourly_precipitation_description` | forecastHourly.desc / precipitationProbability.desc | 原始 UI 降水提示文本，按逐小时预报时间上下文理解 |
| `precipitation` | minute_rain_data 优先，回退 raw.minutely | description/shortDescription/headDescription/subtitle/probabilityDescV2 与最多120个数值/概率。原数组序号；强度、概率单位、采样间隔未验证，unit/probability_unit=null，不猜 mm/h |
| `alerts[]` | raw.alerts 优先，回退 alertinfo | 最多8项，title/detail/type/level/pubTime/defenseText；不转发 locationKey/alertId/图标/链接。detail≤4096字符，防御建议最多4条。SQL pub_time可能不带时区，published_at_source原样保留并说明，不伪造epoch |
| `alerts[].ends_at` | 仅显式带偏移 endTime | 缺失null，不推测预警失效/解除时间 |
| `life_indices[]` | information生活卡片 + raw.indices | 合并最多12条；每条分开说明业务时间或缓存更新时间。详见下节 |

子对象存在 `status` 时仅 `0` 为本版本成功；缺少 status 允许部分/合成输入。失败子数组不显示其值，其他分项继续。

### 生活指数 UI 文案的真正缓存

仅 raw.indices 的 `type/value` 不足以还原生活建议文案。结构 `information(city_id, insert_time, data1)` 中：

```text
data1.cards[].list[] where type="6" and template="13":
  data.wtrIndexType
  data.wtrTitle
  data.wtrSummary
  data.wtrExtra
  data.wtrHeadData.{title,summary,detail[]}
```

已实见类型名：`clothing, uvIndex, sports, carWash, umbrella, gm`。只投影这些已识别生活卡片的标题、摘要、extra、head title/summary。没有导出广告、wtrLink、图片、统计键、cardId、actionUrl 等。`detail[]` 结构未证实，故不透传。并非每个已选城市都缓存了全部生活卡片；没有同城卡片绝不拿其他城市补齐。

`raw.indices.indices[]` 实见 `uvIndex, humidity, feelsLike, pressure, carWash, sports`，只保留白名单 type/value。体感字段明确标注“穿衣参考，不等于独立穿衣指数”；不将数值自行编造为“适合运动/应防晒”等等级文案。UI 建议来自 information 的原始白名单文本，缓存 insert_time 不等于独立指数发布时间。

## 边界与隐私

- 输出单个字符串≤4096字符；数组≤128（具体分项更小），实际构造深度不超过12。
- fields 使用56000 UTF-8字节总预算，分项再有界，预警与生活文案优先于大预报数组；六键包络/source_notes有余量，类别小于64KiB。极端长文本会截断/省略后续条目，不任意透传 JSON。
- 不输出 city_id/posID、经纬度、街道、账号/sid、alertId/locationKey、源URL、原JSON、品牌/广告/统计数据。自然语言预警/生活建议按用户要求保留，未做破坏正文的关键词脱敏。
- 测试使用手写合成记录，不将私有数据库放入仓库。

## 验证与局限

- [WeatherProjectionTest.java](../../android/tests/WeatherProjectionTest.java)：8组JVM检查，先观察缺少投影的失败后实现；新增日期递增、子status、同城卡片建议、预警结束时间、分钟降水单独可用、缺失base时间、超长组合预算均观察到断言失败后修复。
- [test_weather_source.py](../../tests/test_weather_source.py)：6项集成，用最窄Android平台测试适配器执行Java Source，所有查询交Python标准库真实SQLite `mode=ro` 执行；验证DB字节不变、缺表/缺列局部降级、day=1而不是昨天、过深raw回退、information仅同城。适配器不是Android运行时/LSPosed实测。
- JVM classpath 必须 `json.jar` 在 `android.jar` 前，避免调用Android stub的JSON实现；完整Java源码 `javac --release 8` 单独编译验证。
- 未验证：真机SQLite CursorWindow容量/并发写入时跨表一致性、LSPosed触发频率；未知单位/小时数组真实起点/分钟间隔、预警解除时间；未来版本schema语义变化。不启写事务，只读多次查询存在刷新竞态；每项独立时间保留用于识别。
