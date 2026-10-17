# 小米天气 / 健康只读采集结构

## 0. 范围与证据边界

本文保留应用存储结构、查询方式和静态类/方法线索，不记录个人设备或运行环境。内部类、混淆名和数据库结构可能随版本变化，使用前须探测 schema；不能据此保证任意版本兼容。静态代码线索不等于 Hook 已在运行时验证，字段存在也不等于其单位、业务语义均已确认。

推荐在获授权的目标应用 Context 内以 **OPEN_READONLY** 打开数据库，读取有限窗口，仅输出归一化白名单字段。天气区分设备加密存储（DE），健康区分凭据加密存储（CE）；不需要更改源文件权限或主动联网刷新。

## 1. 天气

### 1.1 存储与只读连接

数据库模板路径：`/data/user_de/<userId>/com.miui.weather2/databases/weather.db`。优先由 Context 解析，不硬编码用户编号：

```java
Context de = appContext.createDeviceProtectedStorageContext();
File dbFile = de.getDatabasePath("weather.db");
SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
```

CE 区的定位/配置库不能仅凭位于目标包目录就视作天气业务库。

### 1.2 结构参考

以下结构用于 schema probe，不是跨版本承诺：

```sql
CREATE TABLE raw (
  insert_time VARCHAR(50), city_id VARCHAR(50) PRIMARY KEY,
  locale VARCHAR(50), data1 TEXT, minute_rain_data TEXT, typhoon_data TEXT
);
CREATE TABLE selectedcity (
  posID VARCHAR(50), flag INTEGER, position INTEGER, name VARCHAR(50),
  street_name VARCHAR(256), longtitude VARCHAR(50), latitude VARCHAR(50),
  belongings VARCHAR(50), extra VARCHAR(50), locale VARCHAR(50),
  PRIMARY KEY(posID,flag)
);
CREATE TABLE weatherappconfig (locateswitch INTEGER, unit INTEGER);
CREATE TABLE weather (
  publish_time VARCHAR(50), city_id VARCHAR(50), city_name VARCHAR(50),
  description VARCHAR(50), temperature INTEGER, temperature_range VARCHAR(50),
  aqilevel VARCHAR(50), locale VARCHAR(50), weather_type INTEGER,
  begins VARCHAR(50), ends VARCHAR(50), humidity VARCHAR(50),
  feel_temperature VARCHAR(50), ultraviolet VARCHAR(50),
  wind_direction VARCHAR(50), wind_power VARCHAR(50),
  next_hour_rain_probability VARCHAR(50), sunrise VARCHAR(50), sunset VARCHAR(50),
  wind VARCHAR(50), data1 TEXT, day INTEGER, ebbTide VARCHAR(50), _id VARCHAR(50),
  pressure VARCHAR(50), pressure_unit VARCHAR(50), pressures VARCHAR(50),
  risingTide VARCHAR(50), timestamp VARCHARA(50), tmphighs VARCHAR(50),
  tmplows VARCHAR(50), water VARCHAR(50), weathernamesfrom VARCHAR(50),
  weathernamesto VARCHAR(50), winds VARCHAR(50), forecast_type VARCHAR(50),
  PRIMARY KEY(city_id,day)
);
CREATE TABLE aqiinfo (
  _id VARCHAR(50), city_id VARCHAR(50) PRIMARY KEY, city VARCHAR(50),
  aqi VARCHAR(50), pm25 VARCHAR(50), pm10 VARCHAR(50), so2 VARCHAR(50),
  no2 VARCHAR(50), pub_time VARCHAR(50), src VARCHAR(50), spot VARCHAR(50),
  title TEXT, desc TEXT, co VARCHAR(50), o3 VARCHAR(50)
);
```

其他表线索包括 `alertinfo`、`background`、`information`、`alertfilter`。`alertinfo` 字段为 `_id,city_id,city,alert,pub_time,level,title,icon_url,detail,abnormal,holiday,type,defense,reported,seen`，主键 `(city_id,type)`。

### 1.3 查询与时间语义

默认选择首页排序首位城市，不擅自改为定位城市；标识只作同城绑定参数，不输出名称或坐标：

```sql
SELECT posID, flag, position FROM selectedcity ORDER BY position ASC LIMIT 1;
SELECT insert_time, locale, data1, minute_rain_data
FROM raw WHERE city_id = ? LIMIT 1;
SELECT publish_time, temperature, description, weather_type, humidity,
       feel_temperature, ultraviolet, wind_direction, wind_power,
       next_hour_rain_probability, sunrise, sunset, pressure, pressure_unit,
       tmphighs, tmplows, timestamp, day
FROM weather WHERE city_id = ? AND day = 1 LIMIT 1;
SELECT aqi, pm25, pm10, so2, no2, co, o3, pub_time
FROM aqiinfo WHERE city_id = ? LIMIT 1;
SELECT unit FROM weatherappconfig LIMIT 1;
```

优先解析 `raw.data1` 的显式单位和源时间。SQLite affinity 不保证实际 Java 类型，统一使用 `Cursor.getString()` 再安全解析。

静态路径 `W1.c.h` 先插入昨天，再加入 forecastDaily；`WeatherPersist.h` 在 `i6==1` 写实况描述，日期为 `forecastPubTime+(i6-1)*day`。因此该路径下 `day=0` 为昨天、`day=1` 才是源预报首日；不能把 day 当 Unix 日期或用昨天冒充实况。详见[天气扩展结构](<weather-expanded-schema.md>)。

### 1.4 JSON 白名单

| JSON 路径 | 类型 | 解释/边界 |
| --- | --- | --- |
| `current.temperature.value/unit` | string/string | 温度，使用显式单位 |
| `current.feelsLike.value/unit` | string/string | 体感，使用显式单位 |
| `current.humidity.value/unit` | string/string | 相对湿度 |
| `current.pressure.value/unit` | string/string | 气压 |
| `current.visibility.value/unit` | string/string | 能见度 |
| `current.wind.direction.value/unit` | string/string | 风向角 |
| `current.wind.speed.value/unit` | string/string | 风速；仅确认 km/h 时转 m/s 除以3.6 |
| `current.weather` | string | 天气码，不是自然语言 |
| `current.uvIndex` | string | 紫外线指数 |
| `current.pubTime` | string | 带偏移 ISO8601 观测源时间 |
| `updateTime` | integer | 缓存/数据更新时间，不冒充观测时间 |
| `aqi.aqi/pm25/pm10/so2/no2/co/o3` | string | 污染物浓度单位未由此结构确认，不猜统一单位 |
| `aqi.pubTime` | string | 空气质量源时间；SQL 时间缺失时不补造 |
| `forecastDaily.temperature.unit` | string | 预报温度单位 |
| `forecastDaily.temperature.value[]` | object[] | from/to 映射以静态 parser 为准，见扩展结构 |
| `forecastHourly.temperature.value[]` | integer[] | 配套 pubTime/unit；小时起点见显示规则 |
| `minutely.precipitation` | object | pubTime、probability[]、value[]；强度单位未确认 |

`raw.insert_time`、`weather.publish_time`、`weather.timestamp` 是缓存/预报相关时间字段，不能代替 `current.pubTime`。Preferences 的 `weather_success_update_time` 表示天气刷新，`foreground_weather_success_update_time` 表示前台刷新，`location_success_time` 仅定位成功。`weatherappconfig.unit` 枚举未完整追踪，优先显式 unit，避免猜测或二次转换。

CE 缓存模板 `/data/user/<userId>/com.miui.weather2/cache/cache_dir_string/city_data.0` 为 DiskLruCache 线索；只投影所需字段，不导出含城市/坐标的完整 JSON。缺少完整观测时间时优先数据库 raw。

### 1.5 Provider 与 Hook 线索

- `com.miui.weather2.providers.WeatherProvider` 的 manifest 属性不等于第三方可读。`query()` 调用 `Z1.R0.i0(Context,String)` 白名单检查，拒绝 `com.android.shell`/空调用者；不要绕过访问检查。
- URI 线索：`content://weather/raw`、`content://weather/selected_city`、`content://weather/weather`、`content://weather/daily_weather`、`content://weatherinfo/aqi`。
- 原始对象构造器：`com.miui.weather2.structures.WeatherData.<init>(String cityId,String locale,String rawJson,String minuteJson,long updateMs,String cityName,Context)`。第三参赋给 mRawData；可投影 `getRawData()` / `getUpdateTime()`，不能把完整 JSON 日志化。
- 网络响应入口：`B1.k.g(Context,CityData,String):WeatherData`，由 `f(Context,CityData)` 包装；返回对象不保证数据库已提交。
- 持久化入口：`Z1.j1.n(Context,WeatherData,boolean,boolean,boolean):boolean` 与 `o(Context,WeatherData,boolean,boolean,boolean,boolean):boolean`，依次写 weather、aqi、alert，第3参数 false 时调用 `r(Context,WeatherData)` 写 raw。after-hook 应去抖并验证实际源更新时间，内部异常可能被吞掉。
- `Z1.j1.r(Context,WeatherData):void` 是 private static raw 写入点；`q(Context,WeatherData)` 写 background，不是 raw。Hook 回调频率、线程和运行时可用性仍需独立验证。

## 2. 健康

### 2.1 目录与一致性

数据库模板为 `/data/user/<userId>/com.mi.health/databases/<accountId>/<region>/fitness_summary` 与 `fitness_data`，另有 `no_account`。由 `context.getDatabasePath("x").getParentFile()` 解析根目录，不硬编码账号或地区；多个账号不能自动合并。优先观察应用已有 Room 实例的打开路径，无法确认活跃账号时安全失败。

DB/WAL/SHM 必须视作一致状态。独立只读连接让 SQLite 正常处理 WAL；不使用 `immutable=1`、不 checkpoint、不改 journal_mode、不关闭原应用连接。需要多表一致快照时可使用显式只读 `BEGIN DEFERRED`/`COMMIT`，避免默认写锁；简单逐文件复制可能竞争。

### 2.2 日报结构与 SQL

```sql
CREATE TABLE daily_report (
  sid TEXT NOT NULL, dataType TEXT NOT NULL, updateTime INTEGER NOT NULL,
  timeInZero INTEGER NOT NULL, viewTag TEXT NOT NULL, value TEXT NOT NULL,
  isUpload INTEGER NOT NULL, isDeleted INTEGER NOT NULL,
  PRIMARY KEY(dataType,sid,timeInZero,viewTag)
);
```

dataType 线索：`STEP,CALORIE,VALID_STAND,MH_STRENGTH,HR,SPO2,STRESS,SLEEP,WEIGHT,HEARING_HEALTH,TRAINING_LOAD`；日视图使用 `viewTag='days'`，value 为 JSON 结构。

`timeInZero` 是应用日期标签，不能默认等于本地午夜真实 epoch。查询可扩大日期标签窗口，再按业务日期归一；缓存更新时间也不能当传感器采样时间。

```sql
SELECT dataType,timeInZero,updateTime,viewTag,value
FROM daily_report
WHERE isDeleted=0 AND viewTag='days'
  AND dataType IN ('STEP','CALORIE','VALID_STAND','MH_STRENGTH','HR','SPO2','STRESS','SLEEP','WEIGHT')
  AND timeInZero >= ? AND timeInZero < ?
ORDER BY timeInZero DESC,updateTime DESC
LIMIT 64;

SELECT timeInZero,updateTime,value
FROM daily_report
WHERE dataType=? AND viewTag='days' AND isDeleted=0
ORDER BY timeInZero DESC,updateTime DESC LIMIT 1;
```

最新快照可能陈旧，必须附日期和 stale 状态。不能跨 sid 对日报 SUM：sid 是来源/聚合键，JSON.sidList 可含多个源；复合主键允许多来源。按明确策略选择同日来源，不重复累计；不输出 sid/sidList/did。

### 2.3 JSON 投影与单位边界

| dataType | 白名单字段 | 单位/语义 |
| --- | --- | --- |
| STEP | `steps,distance,calories,minStartTime,maxEndTime` | steps 次；distance 米；STEP.calories 缩放未独立确认 |
| CALORIE | `calories` | 独立日报 kcal，静态资源依据见扩展结构 |
| VALID_STAND | `standCount` | 次，不保证每次严格等于达标一小时 |
| MH_STRENGTH | `duration,recordList[].minutes` | 分钟 |
| HR | `avgHr,minHr,maxHr,restHr,latestHrRecord.{bpm,time,type}` | bpm；样本 time 为秒 |
| SPO2 | `avgSpo2,minSpo2,maxSpo2,latestSpoRecord.{spo2,time}` | 百分比；无效值视缺失 |
| STRESS | `avgStress,minStress,maxStress,latestStressRecord.{stress,time}` | 分数，不是血压 |
| SLEEP | `total_duration,sleep_deep_duration,sleep_light_duration,sleep_awake_duration,sleep_score,avg_hr,avg_spo2,segment_details[]` | 阶段缺失不能由余数补齐；trace 不等于 REM |
| WEIGHT | `weightRecordList[].{time,weight}` | kg；静态转换依据见扩展结构 |

单位与枚举证据详见[健康扩展结构](<health-expanded-schema.md>)。日报 `updateTime` 表示聚合/缓存更新，不等于每项最新测量；`collectedAtMs` 另记，不覆盖源时间。

### 2.4 明细结构、时间与查询

```sql
CREATE TABLE step_record (
  key TEXT NOT NULL,sid TEXT NOT NULL,time INTEGER NOT NULL,value TEXT,
  zoneOffsetInSec INTEGER NOT NULL,zoneName TEXT,timeIn0Tz INTEGER NOT NULL,
  isUpload INTEGER NOT NULL,isDeleted INTEGER NOT NULL,
  endTime INTEGER NOT NULL DEFAULT 0,endTimeIn0Tz INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(key,sid,time)
);
```

同构表线索：`hr_record,spo2_record,stress_record,sleep_segment,calorie_record,stand_record,mh_strength_record,energy_record,v02max_record,blood_pressure_record,hearing_health_record,menstruation_record,pai_record,training_load_record,temperature_record,running_ability_record,vitality_record,blood_sugar_record`。

`time` 为真实 Unix 秒；`timeIn0Tz` 为墙上日期标签，不应直接当真实 epoch 或再加时区。查询本地自然日应以 `LocalDate.atStartOfDay(zone)` 到次日开始生成边界，不固定加86400，以兼容 DST。多来源规则详见[步数](<step-multisource-rules.md>)和[活动](<activity-multisource-rules.md>)，不要自行相加替代原生聚合。

```sql
SELECT time,endTime,zoneOffsetInSec,value FROM hr_record
WHERE isDeleted=0 AND key IN ('heart_rate','single_heart_rate')
  AND time >= ? AND time < ? ORDER BY time DESC LIMIT 1;
SELECT time,endTime,zoneOffsetInSec,value FROM spo2_record
WHERE isDeleted=0 AND key IN ('spo2','single_spo2')
  AND time >= ? AND time < ? ORDER BY time DESC LIMIT 1;
SELECT time,endTime,zoneOffsetInSec,value FROM stress_record
WHERE isDeleted=0 AND key IN ('stress','single_stress')
  AND time >= ? AND time < ? ORDER BY time DESC LIMIT 1;
-- 睡眠跨午夜：按交集选取，而非仅按开始日期。
SELECT time,endTime,zoneOffsetInSec,value FROM sleep_segment
WHERE isDeleted=0 AND key='sleep' AND endTime >= ? AND time < ?
ORDER BY endTime DESC LIMIT 4;
```

key 线索：step=`steps`；HR=`heart_rate,single_heart_rate,resting_heart_rate,high_heart_rate,low_heart_rate,abnormal_heart_beat`；SpO2=`spo2,single_spo2`；stress=`stress,single_stress`；sleep=`sleep`；weight=`weight`。

```text
step_record: {calories:float,distance:int,steps:int,time:int}
hr_record: {time:int,bpm:int,type:int}
spo2_record: {time:int,spo2:int}
stress_record: {time:int,stress:int}
sleep_segment: {bedtime:int,wake_up_time:int,duration:int,
  sleep_deep_duration:int,sleep_light_duration:int,sleep_awake_duration:int,
  sleep_trace_duration:int,awake_count:int,timezone:int,
  device_bedtime:int,device_wake_up_time:int,protoTime:int,
  items:[{start_time:int,end_time:int,state:int}]}
```

阶段时长求和不保证等于总时长，不用差值补齐。`weight_item_record` 另有 `zeroTimeIn0Tz,weight REAL,bmi REAL,bodyFatRate REAL,moistureRate REAL,boneMass REAL,basalMetabolism INTEGER,muscleMass REAL,proteinRate REAL,visceralFat REAL`；不因存在列就推定各字段单位均已核实。

### 2.5 Hook 与其他存储

`com.xiaomi.fitness.aggregation.health.dao.FitnessDailyReportDao` 接口：

```java
void insert(com.xiaomi.fitness.aggregation.health.entity.FitnessDailyReportEntity... entities);
FitnessDailyReportEntity queryDailyReportEntitySync(
    com.xiaomi.fit.fitness.export.data.annotation.HomeDataType dataType,
    long timeInSecond);
List<FitnessDailyReportEntity> queryDailyReportEntityListSync(
    HomeDataType dataType,long begin,long end);
```

接口 `@Insert(onConflict=1)` 不保证接口本身可 Hook。具体写后入口线索为 `FitnessDailyReportDaoProxy.insert(HomeDataType,DailyBasicReport[]):void`：public final 实例方法，静态 `INSTANCE`；生成 updateTime，同步 DAO.insert 后更新设备/红点。after-hook 去抖读取 summary，仍检查实际 updateTime；内部可能捕获约束错误。不要 Hook 只读查询反复触发自己，数组类型需由目标 ClassLoader 解析。

原始 Room 类为 `com.xiaomi.fit.fitness.persist.db.FitnessDatabase`；可观察已有 `SupportSQLiteDatabase.getPath()` 选择活跃账号/region，不调用迁移或写入 API。框架层观察应仅限目标进程和目标库，不 Hook 全局 SQL 或打印参数。

MMKV 与空 Preferences XML 并存时，不能据空 XML 推断没有配置。核心采集不应扫描或导出 MMKV 全部键值，也不应读取账号/密钥。`fitness_sync,fitness_user,device_db,device_record_db,sleep_trace_db`、RKStorage 等不能仅凭名称作为传感器历史源。

## 3. 集成与未核实项

1. attach 获得 Context 后异步只读采集，DE/CE 分开处理；用 `sqlite_master` 与 `PRAGMA table_info` 探测兼容性。
2. 持久化 Hook 成功后去抖，worker 读库，避免阻塞 UI/同步线程；区分当前无记录、库不存在和读取错误。
3. 只输出归一化指标、业务时间、采集时间；不输出精确位置、账号、来源标识、原 JSON 或源文件路径，不改源权限。
4. 尚需独立验证：动态 Hook 加载/回调、活跃账号选择、历史日期标签变化、未知单位与未来 schema 语义。睡眠枚举、已确认单位和天气 parser 映射以相应扩展/显示规则文档为准，不能用数量级猜测。
