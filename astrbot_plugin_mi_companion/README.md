# 小米设备关怀助手 AstrBot 配套插件

独立监听专用接口，不修改 AstrBot dashboard、不使用管理 API key。HTTP 2xx 仅表示接收；只有状态 `sent` 才表示适配器发送成功返回，**不保证用户已读**。

## 安装与配置

1. 运行环境 Python 3.12，AstrBot 当前插件 SDK。将此目录作为 `data/plugins/astrbot_plugin_mi_companion` 安装，目录根包含入口 [main.py](<main.py>) 和 [metadata.yaml](<metadata.yaml>)。安装插件依赖：
   ```sh
   python3.12 -m pip install -r data/plugins/astrbot_plugin_mi_companion/requirements.txt
   ```
2. 在 AstrBot 插件配置页填写以下内容，再启用/重载插件。默认密钥、目标均为空，此时插件会**拒绝启动**，不会放行未认证请求。
   - `api_key`：独立随机密钥，至少 32 字符；勿复用管理员密钥或模型 key。生成示例：`python3.12 -c 'import secrets; print(secrets.token_urlsafe(32))'`。插件配置页属于管理员可信边界，请保护配置文件及备份权限，不要截图或公开日志展示 key。
   - `listen_host`：默认 `127.0.0.1`，仅本机。
   - `listen_port`：默认 `8765`。冲突时改为空闲端口，同时调整反代。
   - `allowed_umos`：非空字符串列表。在实际目标机器人会话发送 `/sid`，将输出的完整 UMO 原样填入，例如 `mybot:FriendMessage:123456`。严格精确匹配；不能只填 QQ 号、平台名称或群号。
   - `provider_id`：留空使用该 UMO 在 AstrBot 已配置的默认文本模型；也可填已配置的 provider ID。手机不能覆盖模型或系统提示词。
3. 手机填写基地址 `https://你的域名/mi-companion`、上述独立 key、白名单中的 UMO。**不是** OpenAI API URL，也不是 AstrBot 管理 API 地址。所有 URL / key 均由用户自行配置，源码不含真实值。
4. 先完成接口检查，再主动点击手机“立即测试发送”。这将提交真实当前快照，最终调用已配置机器人发送消息；首次真实测试需用户授权并准备好目标。

插件 SQLite 位于 AstrBot `data/plugin_data/astrbot_plugin_mi_companion/events.sqlite3`（不是插件源码目录），目录权限 0700、数据库 0600。备份/移动应包括 SQLite WAL；不要让两个 AstrBot 实例共用此数据库。插件升级不要删除持久化数据库，否则去重历史会丢失。

## HTTPS 反向代理

本机监听只提供 HTTP，公网必须由可信 HTTPS 反向代理终止 TLS。以下 Nginx 片段置于已安装合法证书的 HTTPS server 内；证书路径和域名由部署者填写，不需要修改 AstrBot dashboard：

```nginx
location /mi-companion/ {
    client_max_body_size 128k;
    proxy_pass http://127.0.0.1:8765;
    proxy_set_header Host $host;
    proxy_set_header Authorization $http_authorization;
    proxy_set_header X-Forwarded-Proto https;
    proxy_connect_timeout 5s;
    proxy_read_timeout 30s;
    proxy_redirect off;
    access_log off;
}
```

`proxy_pass` 后不要加 `/`，保留 `/mi-companion/...` 原路径。只对外开放 443，不对外开放 8765；不要把密钥写在 URL/query 或反代日志里。手机保持默认 TLS 证书验证，不接受自签名绕过。局域网 HTTP 仅供用户明确开启后的可信网络调试，不能作为公网部署方案。建议反代配置基础限速和防火墙。插件无跨域 CORS 放行、无重定向、不记录原始健康 JSON 或密钥。

## 协议 v1

全部请求必须带 `Authorization: Bearer <插件独立key>`。

| 请求 | 语义 |
|---|---|
| `GET /mi-companion/capabilities` | 返回 `service: astrbot-mi-companion`、整数 `protocol_version: 1`、`max_body_bytes: 131072` |
| `POST /mi-companion/events` | 持久化接收，返回 `event_id/status/stage/error/created/updated`；新接收通常 202，已处理重复提交 200 |
| `GET /mi-companion/events/<event_id>` | 查询同样的状态对象；不会重新创建或发送事件；不存在为 404 |

`created`/`updated` 是服务器 Unix 秒数。状态接口不返回原始天气/健康快照。`status` 为 `accepted` / `processing` / `sent` / `failed` / `delivery_unknown`；`stage` 区分 queued/llm/send/done/expired/target。

完整请求示例（时间须替换为实际采集时间，示例不用于真实发送）：

```json
{
  "protocol_version": 1,
  "event_id": "a0cfe7d7-9d6d-43d9-af8a-5172c3ee3c7d",
  "trigger": {"type": "manual_test"},
  "collected_at": "2026-10-17T12:00:00+08:00",
  "timezone": "Asia/Shanghai",
  "umo": "mybot:FriendMessage:123456",
  "weather": {
    "status": "missing",
    "source_package": "com.miui.weather2",
    "collected_at": "2026-10-17T12:00:00+08:00",
    "source_updated_at": null,
    "fields": {},
    "reason": "尚未读取到天气；源时间未知"
  },
  "screen": {
    "status": "ok",
    "source_package": "android",
    "collected_at": "2026-10-17T12:00:00+08:00",
    "source_updated_at": null,
    "fields": {"screen_interactive_ms": 600001, "statistic_scope": "本地自然日截至采集时刻"},
    "reason": "源时间未知；时长为本地 UsageEvents 统计"
  },
  "health": {
    "status": "missing",
    "source_package": "com.mi.health",
    "collected_at": "2026-10-17T12:00:00+08:00",
    "source_updated_at": null,
    "fields": {"steps": null},
    "reason": "未读取到健康数据，不代表今日 0 步"
  }
}
```

严格校验：
- 顶层必须且只能有示例中的九个字段。`protocol_version` 必须是整数 1，不接受 true 或字符串。
- `trigger.type` 只接受 `manual_test` 或 `long_idle`；自动触发可以在 trigger 对象附加息屏及连续使用起止、时长，插件不把这些设备声明当独立验证的时间证据。
- `event_id` 允许 1–128 个 ASCII 字母/数字/下划线/连字符；UUID 可直接使用。相同 ID 的 JSON 结构化内容必须完全相同（键顺序可不同），否则 409。任何重试必须复用原 ID、原时间和原快照。
- `weather/screen/health` 各自必须且只能包含示例中的六个字段。`status` 为 `ok/missing/stale/error`，`fields` 为对象，`source_package` 为非空字符串，`reason` 为字符串或 null。缺失字段为 null，不造 0；源无更新时间用 null 并在 reason 明确说明。
- 所有时间为带时区 ISO8601（可用 `Z`）；timezone 为 IANA 名称。采集超过 24 小时返回 422，超前服务器超过 5 分钟返回 400。排队/生成期间过期也不发送。
- UTF-8 JSON 请求体最大 128 KiB（含 chunked 请求），不支持压缩体；拒绝重复 JSON key、NaN/Infinity、未知顶层字段、单文本超过 4096 字符、字段名超过 128 字符、单对象/数组超过 128 项、嵌套超过 12 层。
- 401：key 缺失/错误；403：非白名单 UMO；400：格式/版本错误；413：请求过大；422：过期；429：已有 100 条未送达记录；503：数据库不可用。401/403 应暂停自动重试；5xx/429 可退避后用原事件重试。

## 处理与恢复语义

1. SQLite 唯一主键 + 事务持久化去重；领取事件时原子地从 accepted 改 processing，重复请求/并发 process 不能重复发送。
2. 先调用 AstrBot 模型，固定中文 system prompt 明确快照不可信、缺失/过时需标明、静置不等于睡眠、不做健康诊断。**不传工具**、不发原始 JSON、不允许客户端修改 provider/system prompt。提示词防御不是对任意模型输出的形式化保证。
3. LLM 失败记 `failed / llm`，不发送。先将 `processing / send` 提交 SQLite，才执行 `context.send_message`。返回 False 记 `failed / send`（未找到平台）；异常/超时记 `delivery_unknown / send`，可能已送达。
4. 重启时，发送阶段中断变 `delivery_unknown`，不自动重发；生成阶段中断可恢复 accepted 安全重试。正常卸载先停止 worker 再关闭 DB。模型超时 120 秒，机器人超时 60 秒。
5. sent/failed/delivery_unknown 的详细快照会删除，仅保留哈希和状态作为去重墓碑。accepted 最多排队至 24 小时，100 条未送达记录后拒绝新事件，**不静默清除失败事件**。墓碑目前不自动过期（保留去重证据）；手机的 7 天/100 条保留策略由手机端实现。
6. 终态相同 ID 不会因 POST 自动重试。失败原因修复后，只有用户确认确需再发，才在手机创建新事件；delivery_unknown 必须先核对机器人端，避免重复。手工数据库清理前停用插件并备份，删除墓碑会丢失该 ID 的去重能力。没有分布式 exactly-once 承诺。

## 官方 API 核实与验证边界

协议参考以下官方源码：
- [Context](<https://github.com/AstrBotDevs/AstrBot/blob/master/astrbot/core/star/context.py>)：`await get_current_chat_provider_id(umo)`、`await llm_generate(chat_provider_id=..., prompt=..., system_prompt=...)`，以及 `await send_message(umo, MessageChain)` 的 **bool** 返回值。
- [Star 生命周期](<https://github.com/AstrBotDevs/AstrBot/blob/master/astrbot/core/star/base.py>)：`__init__(context)`、`async initialize()`、`async terminate()`。
- [StarTools](<https://github.com/AstrBotDevs/AstrBot/blob/master/astrbot/core/star/star_tools.py>)：`get_data_dir(plugin_name)`。
- [MessageChain 导出](<https://github.com/AstrBotDevs/AstrBot/blob/master/astrbot/api/event/__init__.py>) 和 [UMO 解析](<https://github.com/AstrBotDevs/AstrBot/blob/master/astrbot/core/platform/message_session.py>)：平台实例 ID、消息类型、session ID；session ID 可包含冒号。官方说明 qq_official 主动发送不支持此方法，平台本身限制仍适用。

上述链接指向官方 master，不能保证所有历史版本兼容；请使用提供这些异步 API 的 AstrBot 版本。适配器测试使用隔离替身；HTTP 测试使用回环服务，不验证真实模型或平台。

在项目根运行：
```sh
python3.12 -m unittest discover -s tests -p test_astrbot_core.py -v
# 标准库环境 core 可测；aiohttp 不存在时 HTTP/适配器测试明确 skip。
# 安装 requirements 的隔离环境可运行全部测试，例如：
build/test-venv/bin/python -m unittest discover -s tests -p test_astrbot_core.py -v
python3.12 -m py_compile astrbot_plugin_mi_companion/core.py astrbot_plugin_mi_companion/server.py astrbot_plugin_mi_companion/main.py
```

测试先观察 core 缺失导致 import 失败，再实现 14 项通过；HTTP 新增 5 项先因 server 缺失失败再实现；适配器新增测试先因 main 缺失失败再实现。后续回归复现并修正 malformed trigger 与 LLM 期间过期。最终结果见 [测试证据](<TEST_EVIDENCE.md>)。尚未验证：真实 AstrBot 安装/插件重载、真实模型、目标适配器、HTTPS 证书与反代、手机端到端送达、长时间运行/大量吞吐。单进程单 worker 是有意上限，不支持多实例共用 SQLite。
