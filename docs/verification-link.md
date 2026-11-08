# 0.7.0 三 APK / WebSocket 改造验证

## 已完成的本地验证

- `bash tools/test.sh`：全部通过（含 `LinkProtocol` 1057 项、`LinkServer` 27 项、`HookMatch` 12 项，以及原有 Java/Python 测试）。
- `bash tools/build.sh`：产出三个已签名 APK，且 `apksigner verify` 通过：
  - `dist/mi-companion-0.7.0.apk`（`cn.local.micompanion`，versionCode 12）
  - `dist/mi-companion-weather-0.7.0.apk`（`cn.local.micompanion.weather`，versionCode 1）
  - `dist/mi-companion-health-0.7.0.apk`（`cn.local.micompanion.health`，versionCode 1）
  - 校验和：`dist/SHA256SUMS-0.7.0.txt`
- APK 静态检查：
  - 主程序 manifest 不含 `xposedmodule`/`xposedscope`，不含导出的 `SnapshotProvider`，不含 `assets/xposed_init`。
  - 两个模块各含自己的 `xposedmodule` 元数据与 `assets/xposed_init`，作用域分别只有 `com.miui.weather2`、`com.mi.health`，且没有 launcher activity。

## 真机已确认

- 天气设置页真实类名 `com.miui.weather2.ActivitySet`；顶部新增独立按钮“数据连接设置”，点击打开单独的全屏配置页（含“← 返回”、端口、Token、保存并重连、状态）。
- 健康“关于”页真实类名 `com.xiaomi.fitness.about.AboutActivity`；内容已替换为同一套配置表单，标题栏“关于”保留。
- 主程序 `MonitorService` 正常运行，本机已监听 `127.0.0.1:17890`；settings.json 已生成（Token 加密保存）。
- 三个 APK 的 versionCode/versionName 与签名安装成功：主程序 12/0.7.0，两个模块 1/0.7.0。
- 主程序 `Root：已授权 · Shizuku：已授权`；天气、健康两路均曾实际连接（心跳 0–3 秒、RTT 1–3 ms）。
- 0.7.0 当时验证过每小时同步；该定时同步及其设置项已在后续版本取消，当前主程序不再按 30 分钟/10 分钟定时同步或定时拉起应用。
- 发送时间仍为连续息屏严格超过 3 小时取得资格，下一次解锁后连续使用严格超过 10 分钟才发送；手动打开天气后由天气模块推送快照；健康模块只在主程序点“同步数据库与请求”时手动传输。

## 仍需用户侧确认

1. 发送前拉起：模块断开时不会立即拉起；只在原自动发送触发时尝试拉起，连接成功后在 60 秒内继续采集。定时同步已取消，不会为了同步定时拉起。
2. 回退数据：模块无法拉起或 60 秒内未连接时，确认事件 `trigger.data_fallback=previous_event` 且数据来自上一次事件；无历史数据时为 `unavailable`（代码路径已实现，尚未构造真机故障验证）。
3. 错误 Token、改端口后的鉴权失败/重连表现；当前已实测错误 Token 显示“token 已过期”，普通掉线仅显示“未连接”。
4. 杀主程序后模块的退避重连（按需拉起只负责目标应用，不负责主程序自身）。
5. 旧 ContentProvider 外部访问已移除。

两个模块当前均已实际连接（心跳 0–2 秒、RTT 1–3 ms），健康模块的 `INTERNET` 权限已由实际连接证明。

## 真机验收步骤

1. 安装三个 0.7.0 APK；在 LSPosed 分别启用天气/健康模块，作用域只勾对应应用；强行停止并重开小米天气、小米运动健康。
2. 打开主程序，记下“数据连接”区块的端口（默认 17890）和 Token。
3. 小米天气 → 设置 → 顶部按钮“数据连接设置” → 进入单独配置页填端口/Token → 保存并重连 → 返回。
4. 小米运动健康 → 关于（已被替换为设置页）→ 填同一组端口/Token → 保存并重连。
5. 回主程序确认两路“已连接”，心跳时间在 0–5 秒；等待 60 秒后主程序“刷新本机诊断”能看到对应快照。
6. 改错 Token 验证“鉴权失败”；改端口验证重连；杀掉主程序验证模块退避重连。
7. 定时同步已取消，不再需要等待小时同步；停止两个模块后确认主程序只显示“未连接”，不会定时拉起。发送仍须满足原连续息屏 3 小时 + 使用 10 分钟条件。
8. Shizuku 适配：主程序点“授权 / 检查 Shizuku”并在 Shizuku 中允许；勾选“优先使用 Shizuku 拉起”后点“立即拉起天气和健康”验证 Shizuku 通道。

用 `logcat` 过滤 `MiCompanionWeather` / `MiCompanionHealth` 可看到实际 Activity 类名；若某个页面未命中，把类名补进 `InjectMatch` 的精确规则后再验收。

## 0.7.0 每日活动/睡眠/运动数据验证

- 真机健康模块重新发布后，主程序 `shared_prefs/snapshots.xml` 的 health 快照实际包含 `week_activity`、`today_workouts`、`sleep_week`，快照约 33 KiB，低于 256 KiB。
- 真机快照实测：
  - `week_activity`：2026-10-18～2026-10-24，7 天，`days_with_data=7`；按天输出，不输出周合计。每日为：18 日 4769 步/522 千卡/16 分钟；19 日 10122 步/889 千卡/53 分钟+运动 322 秒；20 日 10505 步/883 千卡/48 分钟；21 日 7333 步/774 千卡/30 分钟；22 日 8421 步/880 千卡/32 分钟；23 日 7721 步/653 千卡/38 分钟+运动 438 秒；24 日 5812 步/504 千卡/26 分钟+运动 188 秒。
  - `today_workouts`：2026-10-24 10:58:48（Asia/Shanghai）开始，户外跑步 221 米、188 秒、活动卡路里 12 千卡（总 16 千卡）、平均配速 850 秒/公里、平均/最大心率 125/158、286 步、平均/最大步频 111/204、平均步幅 82 厘米。
  - 当次睡眠：2026-10-23 23:35～2026-10-24 06:39，总睡眠 397 分钟、清醒 27、REM 137、浅睡 144、深睡 116；其余近 7 天只输出摘要。对应 10 月 23 日的日报睡眠段缺少完整分期字段，界面显示“分期时长：暂无数据”，没有把缺失填成 0。
  - 心率、压力、血氧没有删除；当前全周期实测范围为心率 52～160 次/分、压力 8～44、血氧 94～99%，分段仍逐项保留。
- IDA Pro 9.3 加载 `mihealth-sleep.dex`（`auto_analysis_ready=true`）后，用 `list_funcs`/`disasm` 核查 `SleepDayItemFragment.refreshView@VL`：它按 `AllDaySleepReport.time` 与本日零点的 `TimeDateUtil.changZeroOfTheDay` 比较，并分别保存当天/前一天报告；`AllDaySleepReport` 含 `time`、`dayReportList`、`sleepSegments` 及四类时长字段。结合库内 `daily_report.dataType='SLEEP'` 的 `segment_details`，确认“按本地日汇总、阶段明细在日报里”的口径，因此实现直接读取该日报并保留原始阶段，不自行用自然日重算睡眠。
