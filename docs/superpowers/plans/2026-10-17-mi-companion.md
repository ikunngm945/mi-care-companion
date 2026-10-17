# Mi Companion Implementation Plan

> **历史设计参考：** 本文保留最初的独立 AstrBot 插件事件协议和自然日采集方案，不代表最新实现。后续官方 HTTP 路由与提示词方案见[路由计划](<2026-10-17-auto-route-advice.md>)；天气固定事实见[天气计划](<2026-10-17-weather-facts.md>)；健康与屏幕的同区间周期模型见[睡眠周期计划](<2026-10-17-sleep-cycle.md>)。下列复选框是原始任务拆分，不表示当前完成状态。

**Goal:** 构建小米设备数据采集 APK，长息屏后解锁持续使用再上报 AstrBot，并支持无时间条件的立即测试按钮。

**Architecture:** 原生 Java APK 内含计时状态机、监控服务、采集与发送 UI；LSPosed 模块只在天气和健康作用域采集，使用经过调用者验证的 ContentProvider 将限定快照交给 APK。独立 AstrBot 插件通过专用 HTTP 协议接收、去重，调用 LLM 后向允许的 UMO 发送。

**Tech Stack:** Java 8 字节码、Android API 36（minSdk 28）、传统 Xposed API compile-only、Python 3.12、AstrBot 插件 API、aiohttp、SQLite、javac / D8 / aapt / apksigner。

**Spec:** [初始设计](<../specs/2026-10-17-mi-companion-design.md>)

## Global Constraints

- 阈值分别为 `10_800_000 ms` 和 `600_000 ms`，比较采用严格 `>`，不是 `>=`。
- 单次开机内用 `elapsedRealtime`，系统改时间不影响阈值。
- 中途息屏保留资格，但连续使用计时归零，不累计零碎使用。
- “立即测试发送”绕过监控启用、息屏、解锁、使用时长条件，不改变自动状态机；真实配置和网络仍必需。
- 缺失为 null，不填 0；源时间未知明确标注，不能把缓存时间当源更新时间。
- HTTP 2xx 接收不代表最终送达，`sent` 仅表示机器人适配器发送成功返回。
- 不读取联系人 / 消息 / 账号令牌，不改小米应用源数据或权限，不访问无关系统或用户数据。
- 用户自行填写 URL / API key / UMO，不使用开发工具绑定的凭据。
- 每步检查命令退出状态，保留文件变更和测试证据。

## 文件职责

- `android/src/cn/local/micompanion/TriggerState.java`：无 Android 依赖的严格计时状态机。
- `android/tests/TriggerStateTest.java`：可直接 javac/java 运行的状态机检查。
- `android/AndroidManifest.xml`、`android/res/values/strings.xml`、`android/assets/xposed_init`：安装、后台、Hook 作用域资源。
- `android/src/cn/local/micompanion/MonitorService.java`：广播、计时持久化、前台通知、自动事件调度。
- `android/src/cn/local/micompanion/UsageCollector.java`：UsageEvents 统计与权限状态。
- `android/src/cn/local/micompanion/HookEntry.java`：目标作用域初始化、只读提取和受限发布。
- `android/src/cn/local/micompanion/SnapshotProvider.java`：验证调用 UID 与内容大小并保存快照。
- `android/src/cn/local/micompanion/EventClient.java`：事件持久化、专用 API 校验 / 鉴权 / 提交 / 查询 / 重试。
- `android/src/cn/local/micompanion/SettingsStore.java`：Keystore 凭据及配置。
- `android/src/cn/local/micompanion/MainActivity.java`：中文设置 / 权限 / 无条件测试 / 诊断。
- `tools/build.sh`、`tools/test.sh`：固定输入路径、可重复构建和检查。
- `astrbot_plugin_mi_companion/main.py`、`core.py`、`metadata.yaml`、`_conf_schema.json`、`requirements.txt`：AstrBot 适配和可独立测试的事件处理核心。
- `tests/test_astrbot_core.py`：使用 mock LLM 和机器人验证真实处理顺序。
- `README.md`、`docs/verification.md`：用户操作与验证边界。

### Task 1: 计时状态机与最小可运行检查

**Files:** Create `android/src/cn/local/micompanion/TriggerState.java`, `android/tests/TriggerStateTest.java`, `tools/test.sh`。

**Interfaces:** Produces `public final class TriggerState` with `public void screenOff(long now)`, `public void screenOn(long now, boolean unlocked)`, `public void unlock(long now)`, `public boolean tick(long now, boolean interactive, boolean unlocked)`, `public void restart()`, `public String encode()`, `public static TriggerState decode(String encoded)`; `tick` 单次返回 true 表示创建一个自动事件，manual 不调用该状态机。

- [ ] **Step 1:** 写边界测试，包含实际阈值与 A 规则：
```java
TriggerState s = new TriggerState();
s.screenOff(0); s.screenOn(10_800_000L, true);
assert !s.tick(11_400_001L, true, true);
s.screenOff(20_000_000L); s.screenOn(30_800_001L, true);
assert !s.tick(31_400_001L, true, true);
s.screenOff(31_400_001L); s.screenOn(31_400_010L, true);
assert !s.tick(32_000_010L, true, true);
assert s.tick(32_000_011L, true, true);
assert !s.tick(32_000_012L, true, true);
```
- [ ] **Step 2:** `bash tools/test.sh`，首先确认缺少 TriggerState 编译失败。
- [ ] **Step 3:** 实现以 `-1` 表示没有起点（0 是有效单调时间）；重复屏幕广播不重置起点；锁定清除使用起点；`tick` 消耗一次资格，序列化含版本。
```java
if (armed && usingSince >= 0 && now - usingSince > USE_MS) {
    armed = false; usingSince = -1; return true;
}
```
- [ ] **Step 4:** 增加锁屏、重复广播、序列化、restart、连续多轮测试，运行 `bash tools/test.sh` 观察全部通过。

### Task 2: AstrBot 接收、LLM 与机器人闭环（可独立并行）

**Files:** Create `astrbot_plugin_mi_companion/{main.py,core.py,metadata.yaml,_conf_schema.json,requirements.txt}`, `tests/test_astrbot_core.py`。

**Interfaces:** Consumes 规范第 7 节 JSON；produces Python `class EventStore` with `accept(self, payload: dict) -> dict`, `get(self, event_id: str) -> dict | None`, `async process(self, event_id: str, llm, send) -> None`。`llm(umo: str, prompt: str)` 异步返回 str；`send(umo: str, text: str)` 异步成功返回。core 不导入 AstrBot，以便标准库 unittest 运行。

- [ ] **Step 1:** 写隔离测试：
```python
calls = []
async def llm(umo, prompt):
    calls.append('llm'); return '测试回复'
async def send(umo, text):
    self.assertEqual(text, '测试回复'); calls.append('send')
store.accept(payload)
await store.process(payload['event_id'], llm, send)
self.assertEqual(calls, ['llm', 'send'])
self.assertEqual(store.get(payload['event_id'])['status'], 'sent')
```
- [ ] **Step 2:** `python3.12 -m unittest discover -s tests -v`，确认 import core 尚不存在的失败。
- [ ] **Step 3:** 实现 SQLite 事务去重、字段检查和发送阶段持久化；服务重启时对发送中状态转 delivery_unknown，生成中状态可安全重试。`hmac.compare_digest` 验证 Bearer key，body 上限 128 KiB，UMO 必须在非空白名单。
- [ ] **Step 4:** 从 AstrBot 官方当前插件 API 核实 provider 获取、`context.send_message` 和插件初始化 / 终止生命周期，main 使用 aiohttp 本机监听，不 patch dashboard；接口与规范保持一致。
- [ ] **Step 5:** 测试重复 event_id 不重发、内容冲突拒绝、未知协议 / 空 key / 非白名单拒绝、LLM 失败不发送、机器人失败不自动重复、并发请求只执行一次、24h 过期事件拒绝；运行 unittest 和 py_compile。

### Task 3: 当前版本小米只读 Hook 与屏幕统计

**Files:** Create `HookEntry.java`, `SnapshotProvider.java`, `UsageCollector.java`, `android/assets/xposed_init`; research `docs/research/device-data-schema.md`。

**Interfaces:** Produces `public static org.json.JSONObject UsageCollector.collect(android.content.Context context)`、`public static org.json.JSONObject SnapshotProvider.read(android.content.Context context, String kind)`。Provider authority 固定 `cn.local.micompanion.snapshots`，kind 仅 weather / health。

- [ ] **Step 1:** 先完成目标应用只读 schema / 版本定位记录。只有经过真实源验证的 SQL / 字段才写提取器，禁止用关键字猜中一个数就当心率。
- [ ] **Step 2:** 针对定位结构写最小输入样例与错误类型测试：源不存在 -> missing；源有 0 步 -> ok / 0；旧时间 -> stale；无法解析 -> error；未知时间 -> null。
- [ ] **Step 3:** 用 Application attach Hook 获目标 Context，在目标 UID 内只读访问已验证存储；启动后异步限频刷新，发布只含白名单字段的结构，不暴露 SQL / 任意路径读接口。
```java
if (!"com.miui.weather2".equals(pkg) && !"com.mi.health".equals(pkg)) return;
```
- [ ] **Step 4:** Provider insert 验证 `Binder.getCallingUid()` 对应包与 kind，限制 128 KiB，外部 read 仅本 APK UID；模块不得读到配置。UsageEvents 自本地零点积分 SCREEN_INTERACTIVE / NON_INTERACTIVE 与 KEYGUARD_SHOWN / HIDDEN，未知起点不按全天亮屏计算；前台应用 top10 单列。
- [ ] **Step 5:** 手动诊断输出字段名、状态、时间口径，不把隐私数据写公开日志；实际 Hook / 源采集对照应用结果，记录不支持字段。

### Task 4: APK 界面、配置、自动监控与无条件测试

**Files:** Create `SettingsStore.java`, `EventClient.java`, `MonitorService.java`, `MainActivity.java`, `AndroidManifest.xml`, `android/res/values/strings.xml`, `tools/build.sh`。

**Interfaces:** Consumes TriggerState、UsageCollector、SnapshotProvider；produces `public static void EventClient.submit(android.content.Context context, boolean manual)` 与 `public static void EventClient.retry(android.content.Context context)`。manual 为 true 时 `trigger.type=manual_test`，不读取 / 更改 TriggerState。

- [ ] **Step 1:** 建立即时测试隔离检查：manual 路径不依赖 MonitorService，自动计时 encode 前后一致，缺数据仍构造事件；未配置则中文显示错误，不显示已发。
- [ ] **Step 2:** SettingsStore 使用 AndroidKeyStore AES/GCM 加密 key，私有 preferences 禁备份；URL 拒绝 userinfo / fragment / 非http(s)，HTTP 需显式允许；阻止重定向，证书校验保持默认。
- [ ] **Step 3:** MainActivity 用原生控件展示 URL、隐藏 key、UMO、HTTP 开关、保存 / 接口检查、权限入口、启停、立即测试、采集诊断。测试按钮旁说明真实发送；点击异步提交期间禁用，完成恢复，不要求二次确认。
- [ ] **Step 4:** EventClient SQLite 持久化 UUID 和固定快照，网络异常复用 event_id；用后台串行 executor 操作 HTTP，超时 15s / 30s；按 1、5、15、60 分钟退避，401/403暂停。先 capabilities 握手，再 POST，再 GET 状态；显示 accepted 与 sent 区别；保留上限和过期规则按 spec。
- [ ] **Step 5:** MonitorService 创建常驻通知、specialUse FGS 声明用途、屏幕动态 receiver；Handler 在亮屏期检查；重建时保守清除未证实使用计时 / 息屏间隔。每次转移持久化；停止不上传；开机恢复仅在已启用且系统许可时启动。
- [ ] **Step 6:** 准备 API 36 android.jar、D8、compile-only Xposed API 及与构建平台兼容的 aapt。构建脚本 `javac --release 8` -> D8 -> aapt -> zipalign -> apksigner，开发签名私钥不得纳入版本管理。`bash tools/build.sh` 产出 `dist/mi-companion.apk`。
- [ ] **Step 7:** 执行 `apksigner verify --verbose dist/mi-companion.apk`、`aapt dump badging dist/mi-companion.apk`，验证包名、权限、SDK、xposed_init。API36 运行时前台服务不以编译为通过证据。

### Task 5: 安装验证、审查与交付

**Files:** Create `README.md`, `docs/verification.md`; update source only for verified defects。

**Interfaces:** Consumes `dist/mi-companion.apk` 与插件源码；produces 最终 APK 和插件 zip、验证报告。

- [ ] **Step 1:** 告知将安装独立包 `cn.local.micompanion`，不覆盖任何小米应用；在用户授权的目标设备验证安装。启动设置页，检查 crash / 权限 / 服务日志，不打印凭据。
- [ ] **Step 2:** 授权动作需用户明确操作或授权；LSPosed 只开两个作用域，不静默替用户改框架数据库。记录安装成功与 Hook 实际启用分别是什么证据。
- [ ] **Step 3:** 测试未配置时按钮即时报告配置缺失，配置 mock AstrBot 时按钮不等计时即实际 POST；mock 的 status 不代表真实机器人送达。未配置真实服务并授权发送时，不使用其他工具绑定的服务替代。
- [ ] **Step 4:** 重跑 Java 状态机测试、Python 插件测试、干净构建、签名；请求代码审查（安全、并发、时间语义、无条件手动测试），根据证据修正。
- [ ] **Step 5:** 中文 README 写明安装、通知 / 使用权限、LSPosed、HyperOS 后台、插件配置、HTTPS反代、UMO `/sid`、测试按钮、故障与隐私；verification 列完整通过 / 未通过项。用户未配置服务或未启用作用域不宣称完整 E2E。
- [ ] **Step 6:** 交付构建产物、插件源码和验证说明；只有整体目标得到证据才宣称完成，否则明确剩余步骤。
