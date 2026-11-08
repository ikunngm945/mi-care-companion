# 小米关怀助手 0.7.0：三 APK 拆分与 WebSocket 通道设计

## 1. 目标与已确认决定

把当前“单 APK 同时是主程序与 LSPosed 模块”的结构拆成三个 APK：

| APK | 包名 | LSPosed | 作用域 | 职责 |
|---|---|---|---|---|
| 主程序 | `cn.local.micompanion` | 否 | 无 | 设置 UI、AstrBot 调用、触发逻辑、WebSocket 服务端、token 生成 |
| 天气模块 | `cn.local.micompanion.weather` | 是 | `com.miui.weather2` | 天气只读采集、WebSocket 客户端、天气设置页顶部入口 |
| 健康模块 | `cn.local.micompanion.health` | 是 | `com.mi.health` | 健康只读采集、WebSocket 客户端、健康“关于”页替换 |

已确认：

- 三个 APK，同一签名；主程序保留原包名，升级后原有 AstrBot 配置和 Keystore 继续有效。
- 主程序用常驻前台服务运行 WebSocket 服务端（方案 A）。监控开关只控制“是否触发发送”，不影响数据接收。
- 两个模块共用一个 16 位 token；token 由主程序随机生成，字符集为大小写字母与数字。
- 服务端只监听 `127.0.0.1`。
- 服务端每 5 秒发一次心跳（WebSocket ping），10 秒收不到 pong 判定断开；客户端自动重连。

## 2. 非目标

- 不做局域网监听、TLS、远程控制或反向命令通道。
- 不做端口/token 自动发现、二维码配对或跨设备同步。
- 不改变 AstrBot 官方 API 调用方式、触发时间规则、提示词和发送语义。
- 不迁移旧模块的配置（旧版没有端口/token 概念）。
- 不引入第三方 WebSocket 库；协议按 RFC 6455 所需子集自行实现。
- 不重新设计主程序整体 UI，只增加“数据连接”区块。

## 3. 架构与数据流

```
com.miui.weather2 进程                      主程序进程
┌───────────────────────┐                 ┌────────────────────────────┐
│ WeatherHook           │   ws://127.0.0.1 │ MonitorService (前台)      │
│  WeatherSource.collect│ ───────────────▶│  LinkServer               │
│  LinkClient           │   token + hello │   SnapshotStore           │
└───────────────────────┘   + snapshot    │   EventClient / AstrBot   │
                                          │   MainActivity 状态展示    │
┌───────────────────────┐                 │                            │
│ HealthHook            │ ───────────────▶│                            │
│  HealthSource.collect │                 │                            │
│  LinkClient           │                 └────────────────────────────┘
└───────────────────────┘
```

- 采集节奏沿用现状：天气实例化后 1.5 秒去抖 + 每 60 秒；健康每 60 秒。采集完成后若连接存在就发送最新快照，否则只保留在内存，连上后立即补发。不排队历史快照。
- 主程序校验并落盘后，沿用现有 `SnapshotFreshness`、120 秒 stale、24 小时过期和投递规则。
- 模块只在目标应用进程存活时工作，与现状一致；不做独立常驻进程。

## 4. 包、组件与构建产物

### 4.1 主程序

- `MainActivity`：新增“数据连接”区块（端口、token、复制、重新生成、两路状态）。
- `MonitorService`：改为常驻服务，同时持有监控状态机与 `LinkServer`；`monitor.enabled=false` 时仍运行服务、仍接收数据，只是不触发。
- `LinkServer`：`ServerSocket` 绑定回环地址，接受连接、鉴权、心跳、校验与落盘。
- `SnapshotStore`：由原 `SnapshotProvider` 的校验/刷新逻辑改造，进程内读写给 `EventClient`。
- 移除 `SnapshotProvider`（含 manifest `provider`）与全部 Xposed 入口、`xposed_init`。
- 新增配置：`service_enabled` 写入 `monitor` prefs；`link_port` 与 token 写入 `SettingsStore` 的 no-backup 文件（token 加密，端口明文）。

### 4.2 天气模块

- `cn.local.micompanion.weather.WeatherHook`：仅处理 `com.miui.weather2` 主进程；保留 `Application.attach` 与 `WeatherData` 构造 hook。
- `cn.local.micompanion.weather.WeatherSettingsInjector`：在天气设置页内容顶部插入入口行。
- 复用 `cn.local.micompanion` 包内的 `WeatherSource`、`UsageCollector.base/iso` 等采集代码；随模块 APK 一起编译，不依赖主程序。
- 模块配置写入 `com.miui.weather2` 私有 prefs（`mi_companion_link`）。

### 4.3 健康模块

- `cn.local.micompanion.health.HealthHook`：仅处理 `com.mi.health` 主进程；保留 `SQLiteDatabase.openDatabase` 路径 hook、60 秒采集。
- `cn.local.micompanion.health.HealthAboutInjector`：把“关于”页内容整体替换为连接设置表单。
- 模块配置写入 `com.mi.health` 私有 prefs（`mi_companion_link`）。

### 4.4 共享注入代码

`cn.local.micompanion.hook`（编入两个模块，不编入主程序）：

- `LinkClient`：连接、握手、鉴权、hello、发送最新快照、pong、超时与退避重连。
- `HookConfig`：端口/token 读取、校验、保存；默认端口 `17890`。
- `LinkStatus`：连接状态、最近心跳、最近错误、鉴权失败次数（静态 + 持久化供设置页显示）。
- `SettingsForm`：程序化构建的端口/token 表单与状态视图，不依赖模块资源。

### 4.5 构建产物

- `dist/mi-companion-0.7.0.apk`（主程序，versionCode 12）
- `dist/mi-companion-weather-0.7.0.apk`（versionCode 1）
- `dist/mi-companion-health-0.7.0.apk`（versionCode 1）
- `dist/SHA256SUMS-0.7.0.txt`
- 两个模块各带自己的 `AndroidManifest.xml`、`assets/xposed_init`、`res/values/arrays.xml`（作用域）和 `res/values/strings.xml`（标签/描述）。

## 5. WebSocket 协议

### 5.1 握手

客户端发起：

```
GET / HTTP/1.1
Host: 127.0.0.1:<port>
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: <16 字节随机数的 Base64>
Sec-WebSocket-Version: 13
Authorization: Bearer <16 位 token>
X-Mi-Companion-Kind: weather|health
X-Mi-Companion-Version: 0.7.0
```

服务端校验顺序：请求行与 `Host` 回环地址 → `Upgrade/Connection/Sec-WebSocket-Version` → token（常量时间比较，长度必须为 16）→ `kind` 合法 → 返回 `101` 与 `Sec-WebSocket-Accept`。

失败返回 `401`（token 不匹配/缺失）、`400`（请求非法）、`503`（连接数超过上限）后关闭。响应不透露有效 token 或配置。

### 5.2 首帧与快照

连接建立后 5 秒内，客户端必须发送：

```json
{"type":"hello","kind":"weather","version":"0.7.0","protocol":1}
```

`kind` 必须与握手头一致，否则关闭。之后客户端发送：

```json
{"type":"snapshot","snapshot":{...}}
```

`snapshot` 沿用当前 6 字段结构（`status`、`source_package`、`source_updated_at`、`collected_at`、`reason`、`fields`）。服务端校验：

- 序列化后不超过 256 KiB（UTF-8）；
- `status ∈ {ok, missing, stale, error}`；
- `source_package` 与 kind 对应；
- 顶层恰好 6 个键，`fields` 为对象；
- 必需键存在且类型合法。

通过后写入主程序 `snapshots` prefs，并回：

```json
{"type":"ack","storedAtMs":1234567890}
```

失败回 `{"type":"error","code":"snapshot","reason":"..."}`，连接保留，但连续 3 次失败关闭。

### 5.3 心跳

- 服务端每 5000 ms 发送 ping，payload 为 8 字节大端 `SystemClock.elapsedRealtime()`。
- 客户端 WebSocket 层必须回显同一 payload 的 pong。
- 服务端每 1000 ms 检查：距最近 pong 超过 10000 ms 则 `close(1001)`，状态记为“心跳超时”。
- 客户端读取超时 12 秒（覆盖两次 ping）；超时即关闭并重连。
- 状态页显示最近心跳时间与 RTT（pong 时间 − payload 时间）。

### 5.4 帧规则与限制

- 只接受文本帧；二进制、分片和保留 opcode 返回 `1003` 并关闭。
- 客户端帧必须带 mask；服务端帧不带 mask。
- 单帧上限 327680 字节；握手头上限 8192 字节；握手超时 5 秒。
- 关闭码：正常 `1000`；协议错误 `1002`；不支持 `1003`；鉴权失败 `1008`；替换旧连接 `1000` + reason `replaced`。
- 同一 `kind` 只保留一条活动连接；新连接到达时先关闭旧连接。活动连接总数上限 4，握手超时 5 秒。

### 5.5 重连

- 客户端断线后按 1、2、4、8、16、30 秒退避（±20% 抖动），连上后重置。
- 连接成功后先发 hello，再发内存中的最新快照。
- 主程序未运行、端口错误、token 错误都只影响对应模块；错误显示在模块设置页，不阻塞目标应用。

## 6. token 与端口

- token：`SecureRandom` 从 62 个字符中生成 16 位，保证至少各含一个小写、大写、数字；只在主程序生成与展示。
- 主程序存储：加入现有 `SettingsStore` 的 no-backup 文件，用同一 Android Keystore AES/GCM 加密。
- 模块存储：目标应用私有 prefs 明文（与目标应用同沙箱；不引入第二套 Keystore 逻辑）。文档说明 token 只用于本机回环鉴权。
- 重新生成：二次确认后生成新 token，以 `1008` 关闭现有连接；模块需重新填写，否则显示“鉴权失败”。
- 端口：默认 `17890`，范围 `1024–65535`；主程序保存时尝试重新绑定，失败则保留旧端口并显示“端口被占用”。
- token 与端口都不得写入日志；错误信息只写“token 不匹配”，不写具体值。

## 7. 注入 UI

### 7.1 天气设置页顶部入口

- 在 `com.miui.weather2` 的设置 Activity 内容视图顶部插入一行：标题“小米关怀助手 · 数据连接”，副标题显示“已连接 / 未配置 / 连接失败”。
- 点击打开全屏宽度的设置对话框：端口输入（数字）、token 输入（16 位）、保存并重连、当前状态、最近心跳、最近错误、使用说明。
- 保存时校验端口范围与 token 格式，写入目标应用私有 prefs，并让 `LinkClient` 立即重连；校验失败不保存。
- 入口行使用目标应用主题的系统颜色与 `selectableItemBackground`，高度 56dp，左右 16dp，随浅色/深色模式切换。

### 7.2 健康“关于”页

- 识别到“关于”Activity 后，用同款表单替换其内容视图；顶部标题栏保持原样。
- 表单内容：端口、token、保存并重连、状态、最近心跳、最近错误、说明。
- 未配置时端口预填 `17890`，token 为空；已配置时回填。
- 替换只发生一次/每个 Activity 实例，避免重复包裹。

### 7.3 页面识别

初版规则（设备核对后固化）：

- 天气：Activity 类名属于 `com.miui.weather2`，简单类名包含 `Setting`（不区分大小写）。
- 健康：Activity 类名属于 `com.mi.health`，简单类名包含 `About`（不区分大小写），或 `getTitle()` 等于“关于”。

注入点：hook `Activity.onCreate` 与 `onResume` 之后，延迟一帧检查 `android.R.id.content` 的首个子 View，未注入过才处理。命中与未命中都写一条 Xposed 日志（只写类名，不写数据），用于设备核对。

风险：真实类名未核对前可能命中错误页面。设备恢复后先只记录类名，人工打开两个页面确认，再收紧为精确类名并验收。

## 8. 主程序运行与状态

- `service_enabled` 默认为 false；用户首次打开主程序时置 true 并启动 `MonitorService`（Android 12+ 前台启动在用户可见时进行）。
- 开机广播：只要 `service_enabled=true` 就启动服务；`monitor.enabled` 只决定是否运行触发状态机。
- 通知：一条常驻通知，文案随监控状态变化（“数据接收中”/“监控中”），点击进入主程序。
- “停止监控”只关闭触发，不停止服务；“停止后台服务”关闭 `service_enabled` 并停止服务、断开连接（新增按钮）。
- 服务创建时启动 `LinkServer`；端口变更时先关闭旧 `ServerSocket` 再绑定新端口。
- 主程序 UI 每秒刷新一次两路状态：已连接/未连接/从未连接、最近心跳、RTT、最近落盘时间、鉴权失败次数、最近错误。
- 端口绑定失败不崩溃：状态写入 prefs，通知与 UI 显示原因，等待用户改端口。

## 9. 快照与新鲜度

- `SnapshotStore.read(context, kind)` 替代 `SnapshotProvider.read`：同进程读取 prefs，天气在读取时刷新 `collected_at`（与现状一致），再调用 `SnapshotFreshness.refresh`。
- 保留 `refreshSnapshot` 与 `validateSize` 的现有语义，测试相应改名。
- 旧版本写入的 `snapshots` prefs 保留可读；按现有 120 秒/6 小时/24 小时规则自然过期，不迁移、不补造。
- 模块不排队历史：断线期间只保留最新一份内存快照，重连后立即发送。

## 10. 安全边界

- 只绑定 `127.0.0.1`，不监听 `0.0.0.0`/`::`。
- token 常量时间比较；长度错误直接拒绝。
- 删除导出的 `SnapshotProvider`，外部应用不再能写入或探测快照。
- 服务端忽略所有未知 `type`；不执行任何来自 socket 的代码或命令。
- 限制帧、握手、连接数、快照大小；握手与读循环都有超时。
- 日志不包含 token、快照正文、健康数据。
- 模块不申请额外权限；socket 使用目标应用自身的 `INTERNET` 权限（需在设备上确认小米健康具备；若缺失，本方案在健康侧不可行，需停下重新设计）。

## 11. 错误处理与恢复

| 场景 | 行为 |
|---|---|
| 主程序未运行/端口不通 | 模块退避重连；设置页显示“无法连接主程序” |
| token 不匹配 | 服务端 401；模块显示“Token 不匹配”，主程序记录失败 kind 与次数 |
| 端口被占用 | 主程序保留旧端口并显示错误；模块继续连旧端口 |
| 目标应用被杀 | 连接断开，主程序标记未连接；目标应用重启后模块重连并补发 |
| 主程序被杀 | 模块退避重连；服务重启后恢复接收 |
| 快照结构非法 | 回 error，不落盘；连续 3 次关闭连接 |
| 心跳超时 | 关闭并标记；客户端重连 |
| 设备时间跳变 | 心跳用 `elapsedRealtime`；快照新鲜度仍用系统时间，沿用现有规则 |
| 旧连接残留 | 同类新连接替换旧连接 |

## 12. 版本与升级

- 主程序 `0.7.0` / versionCode 12；两个模块 `0.7.0` / versionCode 1。
- 主程序升级后不再出现在 LSPosed 模块列表；用户需安装两个模块 APK，在 LSPosed 中分别启用并只勾选对应作用域。
- 旧导出的 `content://cn.local.micompanion.snapshots` 消失，外部读取会失败（预期）。
- 旧快照按第 9 节规则自然过期；主程序配置、事件历史、Keystore 不变。
- README 更新安装步骤（三个 APK、两个作用域、token/端口填写）和协议说明。

## 13. 测试与验收

### 13.1 Java 单元测试（无真机）

- `HandshakeTest`：RFC 6455 示例 key 的 `Sec-WebSocket-Accept`；缺失/错误头拒绝。
- `FrameTest`：客户端 mask 编解码、126/127 长度、文本/二进制/分片/超大帧、关闭帧。
- `TokenTest`：16 位、字符集、三类字符齐全、批量生成不重复。
- `AuthTest`：正确/错误/缺失 token，kind 不匹配，重复 kind 替换。
- `LinkServerTest`：回环临时端口，真实 socket 完成握手、hello、快照、ack；10 秒无 pong 关闭；非法快照不落盘。
- `LinkClientTest`：重连退避序列、连上先发 hello 再发最新快照、读取超时。
- `HookMatchTest`：天气/健康 Activity 类名匹配与不匹配。
- `SnapshotStoreTest`：沿用原 `SnapshotProviderBudgetTest` 与 `SnapshotFreshnessTest` 的边界。

### 13.2 真机验收

- 安装三个 APK；LSPosed 分别启用天气/健康模块，作用域只勾对应应用；重启两个目标应用。
- 天气设置页顶部出现入口；保存端口/token 后状态显示已连接。
- 健康“关于”页被替换；保存后状态显示已连接。
- 主程序显示两路已连接，心跳间隔约 5 秒，RTT 可见。
- 主程序收到快照后，“立即测试发送”使用新数据；断线时不伪造数据。
- 错误 token：模块显示鉴权失败，主程序可见失败计数。
- 改端口：主程序重新监听，模块改端口后恢复。
- 杀主程序/杀目标应用：重连与补发符合预期。
- 外部应用访问旧 `content://` 失败。

### 13.3 构建

- `bash tools/test.sh` 全部通过。
- `bash tools/build.sh` 产出三个已签名 APK 与校验和。
- 三个 APK 使用同一签名；模块无启动图标。

## 14. 待设备确认项（阻塞验收，不阻塞编码）

1. 天气设置页与健康“关于”页的真实 Activity 类名（SSH `127.0.0.1:<调试端口>` 当前不可达）。
2. 小米健康是否持有 `INTERNET` 权限（决定健康模块能否建 socket）。
3. 两个目标应用在 HyperOS 上是否允许注入线程长期持有回环连接（被杀后重连即可）。
4. 天气设置页内容根布局类型（ScrollView/RecyclerView/Compose），验证顶部插入不遮挡、不破坏滚动。

## 15. 代码与构建变更清单

- 新增：`LinkServer`、`SnapshotStore`、`hook/LinkClient`、`hook/HookConfig`、`hook/LinkStatus`、`hook/SettingsForm`、`weather/WeatherHook`、`weather/WeatherSettingsInjector`、`health/HealthHook`、`health/HealthAboutInjector`。
- 删除：`HookEntry`（主程序侧）、`SnapshotProvider`（含 manifest provider）、主程序 `assets/xposed_init`。
- 修改：`MainActivity`（连接区块）、`MonitorService`（常驻 + LinkServer）、`SettingsStore`（端口/token）、`EventClient`（改读 `SnapshotStore`）、`tools/build.sh`、`tools/test.sh`、`README.md`。
- 新增 manifest/assets：`android/module-weather/`、`android/module-health/` 各自的 manifest、`xposed_init`、`res/`。
- 源码布局保持现有 `android/src/cn/local/micompanion/` 为“主程序 + 采集核心”，新增 `android/hook-common/cn/local/micompanion/hook/`、`android/module-weather/cn/local/micompanion/weather/`、`android/module-health/cn/local/micompanion/health/`。构建脚本按目标列出源码：主程序不编入 hook；模块编入采集核心、hook-common 与自己的入口。
- 测试：新增第 13.1 节用例；改造 `SnapshotProviderBudgetTest`、`SnapshotFreshnessTest`、`HealthSourceTest` 中对 `SnapshotProvider` 的引用。
