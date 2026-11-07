# 0.8.0 MCP 真机验收

验收设备：Xiaomi 22127RK46C（Android 16，LSPosed/Shizuku 环境），日期 2026-10-31，时区 `Asia/Shanghai`。Token 已从记录中隐去。

## 端点与鉴权

- 主程序 MCP 监听 `*:17891`，路径 `/mcp`；本机使用 `127.0.0.1`，局域网使用手机地址（本次为 `192.168.110.9`）。
- 无 `Authorization: Bearer <Token>` 返回 `HTTP/1.1 401`；正确 Token 返回 JSON-RPC 结果。
- `initialize` 返回 `protocolVersion=2024-11-05`，`tools/list` 返回一个 `health` 工具；`action` 选择查询类型。

## 实测结果

### 睡眠

`sleep_detail(date=2026-10-31)` 把每个睡眠阶段转折单独作为一段：

```text
2026-10-31 睡眠详细（Asia/Shanghai）
睡眠段 1：01:49～02:07（浅睡，18分钟）
开始：2026-10-31T01:49:00+08:00；结束：2026-10-31T02:07:00+08:00
心率：55-68 次/分；采样 15；范围 01:49～02:07；最低 02:03；最高 01:52
血氧：97-99%；采样 9；范围 01:49～02:07；最低 02:01；最高 01:58
...
```

按时间顺序一次返回当天的全部阶段段；每段包含起止时间、阶段、时长、心率/血氧范围、采样数和最低/最高出现时间。`start_time`/`end_time`（`HH:mm`）可只查当天某个时段，跨午夜睡眠仍保留前一晚的起止时间。`sleep_summary` 返回同一天的阶段合计和心率/血氧范围，不含阶段段数组；传 `start_date`/`end_date` 时按天返回 `days`。

### 三项指标

`heart_rate_detail(date=2026-10-31)`：

```text
范围 54-118 次/分；平均 76.2 次/分；采样 946 个
08:00-08:15  84-98 次/分
08:15-08:30  82-98 次/分
...
```

`stress_detail` 与 `spo2_detail` 同样按 15 分钟返回时间范围和范围内最小值-最大值；当天没有记录时返回 `status=missing`，不补 0。

### 运动

`exercise_records(start_date=2026-10-27, end_date=2026-10-27)` 返回最近一次户外跑步：

```text
ID 1793054395:1793054876:1
时长 8分1秒；距离 960米；活动 50千卡；总消耗 61千卡
配速 平均 8'21"/km，最快 4'45"/km
步频 平均 148，最高 174
步幅 平均 88 cm
心率 平均 149，最高 161
心率区段：热身 0秒；燃脂 54秒；有氧 408秒；无氧 0秒；极限 0秒
```

`exercise_detail(id=1793054395:1793054876:1)` 读取私有 `sport/p32/1793054395_22_record`，一次返回 481 条 1 秒间隔明细，首尾示例：

```text
t,心率,步频,步幅,配速,距离,卡路里,步数
0,-,-,-,-,0,0,0
...
19,133,166,104,5'45"/km,10,0,1
...
46,134,164,127,4'48"/km,28,0,3
...
480,155,161,86,8'37"/km,7,0,1
```

最大步幅 127 cm 来自该记录文件，不是 `sport_report`；数据库没有 `max_stride` 字段。明细按文件原始 1 秒采样返回，不做 15 分钟聚合。

### 局域网

从另一台同网设备直接请求 `http://192.168.110.9:17891/mcp`，正确 Token 下 `ping` 返回 `{"result":{}}`；无需经过手机本机端口转发。

## 边界

- MCP 只读主程序保存的健康数据库副本，不提供写入工具；首次连接或手动点“同步数据库与请求”时由健康模块复制。
- 睡眠按醒来日归档；`sleep_detail` 包含当天结束的所有睡眠段，跨午夜段保留前一晚的起止时间。
- 三项指标的 detail 固定 15 分钟聚合，不返回原始逐点序列；summary 的范围和平均值来自同一批日记录。运动 `exercise_detail` 是唯一返回逐秒序列的工具，数据来自健康应用私有记录文件。
- MCP 查询不自动拉起健康应用；本地副本已存在时，健康应用未运行也能查询。副本不存在时明确返回“健康数据库尚未同步”，不伪造健康数据。
- 当前 MCP 使用 HTTP + Bearer Token，适合本机/可信局域网；公网请使用 HTTPS 反向代理并限制访问来源。

## AstrBot 接入示例

```json
{
  "url": "http://127.0.0.1:17891/mcp",
  "transport": "streamable_http",
  "headers": {"Authorization": "Bearer <Token>"},
  "timeout": 30
}
```

AstrBot 的 `_quick_test_mcp_connection` 会先发 `initialize` 并检查 HTTP 200；实测本服务返回 200 和 `protocolVersion=2024-11-05`。随后标准 Streamable HTTP 客户端发 `tools/list`/`tools/call`，不需要额外 SSE 通道。

2026-10-31 在 AstrBot 4.27.4 的「扩展 → MCP」中以名称 `mi_companion`、上述 URL/transport/Header 保存成功，接口返回 `connected=true`，工具列表为一个 `health` 工具（`action` 覆盖睡眠、心率、压力、血氧、运动摘要和运动详情），`errlogs` 为空。

### AstrBot 兼容补丁

该 AstrBot 安装的 `astrbot/core/agent/mcp_client.py` 假设的是旧版 MCP SDK；对当前已安装的 `mcp` 包需要三处兼容处理，否则面板会报错或超时：

1. SSE/Streamable 的 `read_timeout` 不再传 `timedelta`，改为秒数 `float`。
2. 新版 `streamable_http_client` 返回 2 个流而不是 3 个，按 `streams[0]`、`streams[1]` 解包。
3. `mcp.Tool` 新版字段为 `input_schema`，构造 `MCPTool` 时优先读 `inputSchema`，不存在再读 `input_schema`。

这三处只改 AstrBot 本机安装，不改本仓库 APK；AstrBot 升级后可能被覆盖。

## 2026-11-07 数据库副本验收

- 健康模块首次连接后，主程序 `files/health/fitness_data.db` 为 118 MiB，`files/health/fitness` 下 306 个运动详情文件。
- 点“同步数据库与请求”后副本重新生成；MCP `health` 的 `sleep_summary`、`exercise_records`、`exercise_detail` 均从副本返回，逐秒明细 418 条。
- `tools/list` 只返回一个 `health` 工具；`health` 的 `action` 覆盖原有 10 类查询。
