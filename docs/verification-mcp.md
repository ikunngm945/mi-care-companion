# 0.8.0 MCP 真机验收

验收设备：Xiaomi 22127RK46C（Android 16，LSPosed/Shizuku 环境），日期 2026-10-31，时区 `Asia/Shanghai`。Token 已从记录中隐去。

## 端点与鉴权

- 主程序 MCP 监听 `*:17891`，路径 `/mcp`；本机使用 `127.0.0.1`，局域网使用手机地址（本次为 `192.168.110.9`）。
- 无 `Authorization: Bearer <Token>` 返回 `HTTP/1.1 401`；正确 Token 返回 JSON-RPC 结果。
- `initialize` 返回 `protocolVersion=2024-11-05`，`tools/list` 返回 10 个工具。

## 实测结果

### 睡眠

`sleep_detail(date=2026-10-31)` 返回一个睡眠段：

```text
2026-10-31 睡眠详细（Asia/Shanghai）
睡眠段 1：01:49～06:38（共4小时49分钟）
清醒 0分钟；快速眼动 1小时19分钟；浅睡 2小时6分钟；深睡 1小时24分钟；心率 54-89；血氧 94-99%
04:28-04:48  快速眼动  心率 55-68 次/分  血氧 97-99%
...
```

全部 18 个阶段段一次返回；工具没有按阶段段再发请求。`sleep_summary` 返回同一天的阶段合计和心率/血氧范围，不含阶段段数组。

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

- MCP 只读健康库，不提供写入工具；健康库仍只由健康模块进程打开。
- 睡眠按醒来日归档；`sleep_detail` 包含当天结束的所有睡眠段，跨午夜段保留前一晚的起止时间。
- 三项指标的 detail 固定 15 分钟聚合，不返回原始逐点序列；summary 的范围和平均值来自同一批日记录。运动 `exercise_detail` 是唯一返回逐秒序列的工具，数据来自健康应用私有记录文件。
- 健康应用未运行时，主程序尝试通过已有 Root/Shizuku 通道拉起，最多等 15 秒；失败明确返回“健康应用未连接”，不伪造健康数据。
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

2026-10-31 在 AstrBot 4.27.4 的「扩展 → MCP」中以名称 `mi_companion`、上述 URL/transport/Header 保存成功，接口返回 `connected=true`，工具列表为全部 10 个（`sleep_*`、`heart_rate_*`、`stress_*`、`spo2_*`、`exercise_records`、`exercise_detail`），`errlogs` 为空。

### AstrBot 兼容补丁

该 AstrBot 安装的 `astrbot/core/agent/mcp_client.py` 假设的是旧版 MCP SDK；对当前已安装的 `mcp` 包需要三处兼容处理，否则面板会报错或超时：

1. SSE/Streamable 的 `read_timeout` 不再传 `timedelta`，改为秒数 `float`。
2. 新版 `streamable_http_client` 返回 2 个流而不是 3 个，按 `streams[0]`、`streams[1]` 解包。
3. `mcp.Tool` 新版字段为 `input_schema`，构造 `MCPTool` 时优先读 `inputSchema`，不存在再读 `input_schema`。

这三处只改 AstrBot 本机安装，不改本仓库 APK；AstrBot 升级后可能被覆盖。
