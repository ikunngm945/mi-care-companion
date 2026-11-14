# 今日屏幕使用时长 MCP 验收

日期：2026-11-13，时区：Asia/Shanghai。

## 接口

新增第三个独立工具 `screen_usage`，不需要参数。调用示例：

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"screen_usage","arguments":{}}}
```

省略 `arguments` 也可调用。工具只读手机当地今天零点到查询时刻的 UsageEvents，展示亮屏时长与解锁使用时长；复用现有跨夜状态和未结束会话积分。查询直接在主程序采集，不依赖健康模块或健康数据库。

需要系统“使用情况访问”权限；未授权、无可见事件或读取错误时明确返回不可用与 `isError=true`，不把缺失当作零。统计口径与系统数字健康可能不同。

## 验证

- 真实 HTTP 回归先确认工具列表缺项失败，再加入独立工具；随后确认调用失败，再实现返回与错误处理，最终 `McpServerTest OK`。
- HTTP 用例覆盖：无参数工具发现、今日时长、权限缺失不伪造零、已观测零可正常返回。
- `ScreenCollectionTest` 新增当地午夜、跨夜只取今天部分、当前会话计到查询时刻；19 项检查通过。
- 完整 Java 和 36 项 Python 回归通过，构建签名成功。日志：`build/screen-usage-20261113/tests.log`、`build.log`。
- 只更新手机主程序并启动后台服务，旧主程序备份：`build/screen-usage-20261113/original-main.apk`。
- 主程序 SHA-256：`81a82e04e0561bb61c6724532d22301e6b60778941be6ad7b03039dc257cba72`。

## 真机结果

真实 `tools/list` 返回 `health`、`weather`、`screen_usage`。2026-11-13 23:43 无参数调用成功，统计范围 00:00～23:43：

- 亮屏：5 小时 43 分 48 秒。
- 解锁使用：4 小时 31 分 18 秒。

原始 MCP 响应保存在 `build/screen-usage-20261113/mcp-live.json`，不包含鉴权 Token。客户端刷新或重连 MCP 后可获取新增工具。
