# 小米天气显示规则：固定格式化可复用的证据

本文记录 `com.miui.weather2` 的静态显示算法与未核实边界。证据以类、方法和资源名描述，不依赖临时反编译文件；内部类/混淆名可能随版本变化，不作为运行时兼容性保证。

## 1. 天气编码不能直接当中文数组下标

- `HourlyData.getWeatherTypeNum(i)` 先调用 `WeatherType.convertV7WeatherTypeToV6WetherType(raw)`。
- `WeatherType.V7V6_MAP` 包含 raw3→显示8（阵雨）、raw7→显示11（小雨）、raw18→显示3（雾）、raw53→显示24（霾）。不可拿 raw3 直接查老数组得到雾。
- `WeatherData.getWeatherName` 按转换后的类型查 `R.array.weather_type`；晴类型单独取 `view_realtime_clear` 复数资源。
- 中文 `weather_type` 资源数组显示顺序为晴、多云、阴、雾、特大暴雨、大暴雨、暴雨、雷阵雨、阵雨、大雨、中雨、小雨等。
- 固定格式化应只翻译已核实编码，未知码写未知/原码；不照抄 `SparseIntArray.get` 未找到时默认0造成误报晴。

## 2. 逐小时时间起点

- `W1.c.i` 将 `forecastHourly.temperature.pubTime` 保存为 `HourlyData` 起始时间；各分项数组按相同索引加载，分别检查 status。
- `HourlyForecast.b` 对第 i 个预报使用 `pubTimeNum + i * 3600000`，同一项匹配温度、天气、AQI、风和降水概率；时间轴按 pubTime 时区显示。
- `HourlyData.getPubTimeNum` → `I0.F` → `I0.H` 解析完整源时间。
- 该静态算法补充了[天气扩展结构](<weather-expanded-schema.md>)中“小时起点未验证”的边界。原时间标签/单位仍需保留；异常起点、字段时间明显不一致时应标注，不以采集时刻代替源时间。
- `HourlyForecast` 还插入实况、日出日落和历史交接点，有些会借相邻值绘图；这是 UI 辅助，不应视作额外天气观测。

## 3. 温度、风与单位

- 天气名和风向/风级为确定性查表或区间转换。`WeatherData` 有角度转风向、km/h 转风级及单位展示转换；不能把未知输入单位直接当 km/h。
- 日预报 from 为最高温、to 为最低温；SQL day1 是源预报首日、day0 是昨天。格式化需按日期选当天，不盲目取数组0为今日。证据见[天气扩展结构](<weather-expanded-schema.md>)。

## 4. 紫外线与生活建议

- `RealTimeDetailCard.M/N` 对整数 UV 指数查固定数组 `{0,0,0,1,1,1,2,2,3,3,3,4}`，超过11取4；对应中文资源弱/中/强/很强/极强。因此有效整数 UV0–2弱、3–5中、6–7强、8–10很强、≥11极强。
- 负数/缺失源不能照抄 UI 内部默认0误报弱；小数取整行为未核实，先保留原指数。
- 区分数值 UV、原应用等级文案、生活卡片原建议；不重复包装同一事实，也不把不同日期或不同语义的同名指数误删。
- `information.data1.cards[].list[]` 的 `wtrTitle/wtrSummary/wtrExtra/wtrHeadData` 为生活卡片文案来源，白名单可直接保留，不必由模型重写。参见[天气扩展结构](<weather-expanded-schema.md>)与[WeatherProjection](<../../android/src/cn/local/micompanion/WeatherProjection.java>)。

## 固定格式化边界

输入已采集数据，输出按源语义排列的紧凑事实表；日期/来源更新时间统一说明，逐小时保留统一表头，预警和建议只去重复包装。不为压缩编造单位/时点，不把算法格式化与模型建议混为一体。健康时段/睡眠阶段需要相应源数据，不能只靠格式化由日报或最近记录恢复全部曲线。
