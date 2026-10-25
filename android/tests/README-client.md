# Android 客户端验证记录

## 范围

客户端验证覆盖设置页面、加密配置、事件提交及独立协议校验；不把隔离协议测试当作真实平台送达证明。

## 可重复命令

在仓库根目录运行（`json.jar` 必须位于 `android.jar` 之前，以免在 JVM 上执行 Android stub）：

```sh
mkdir -p build/client-compile build/client-tests
javac --release 8 -cp tools/deps/android.jar:tools/deps/xposed-api.jar -d build/client-compile android/src/cn/local/micompanion/*.java
javac --release 8 -cp tools/deps/json.jar:tools/deps/android.jar:build/client-compile -d build/client-tests android/src/cn/local/micompanion/ClientProtocol.java android/tests/ClientProtocolTest.java android/tests/ClientPayloadTest.java android/tests/ClientTransportTest.java
java -ea -cp build/client-tests:build/client-compile:tools/deps/json.jar:tools/deps/android.jar cn.local.micompanion.ClientProtocolTest
java -ea -cp build/client-tests:build/client-compile:tools/deps/json.jar:tools/deps/android.jar cn.local.micompanion.ClientPayloadTest
java -ea -cp build/client-tests:build/client-compile:tools/deps/json.jar:tools/deps/android.jar cn.local.micompanion.ClientTransportTest
```

## 已观察的红绿过程

1. `ClientProtocolTest` 先在无实现时以 `AssertionError: Missing production URI/protocol validation` 失败；实现后覆盖 HTTPS 默认、显式 HTTP、userinfo/query/fragment/path/端口拒绝、UUID 唯一、manual/long_idle 元数据、UTC ISO8601 与 IANA 时区。
2. 类别测试先因缺少 `category` 方法失败，再验证白名单六字段、缺失字段不伪造零和真实零步数保留。测试最初误将字段数写成七，按协议核对纠正为六。
3. 有效时间窗口测试先因缺少 `withinSubmissionWindow` 失败，覆盖 24 小时精确边界和设备时间异常。
4. `ClientPayloadTest` 在真实 `org.json` 上观察到 `JSONObject(Map)` 会丢弃 Java null，缺少必需 `source_updated_at`；修复为显式 JSON null。随后新增嵌套心率缺失测试再次观察失败，改为递归构建 JSON，既保留未知心率 null，也保留真实零步数。顶层协议共九字段，类别共六字段。
5. `ClientTransportTest` 使用真实本机 `HttpServer`，验证 Bearer、capabilities、POST manual trigger、GET processing、302 不跟随（重定向目标请求计数为零）、401 与未开启 HTTP 时拒绝。只使用回环地址和硬编码无效本地测试 key，不会联系任何真实机器人。此项是补充集成验证，不声称先于全部网络实现编写。

## 对外调用接口

- `EventClient.submit(Context, boolean manual)`：同步阻塞，仅后台线程调用；manual=true 不读取或修改 monitor/TriggerState，每次新 UUID；本地/网络失败通过 `status` 显示。
- `EventClient.submitAutomatic(Context, String eventId)`：固定 UUID 同步入队；已有 ID 使用原快照，网络失败保留原 ID；本地入队失败抛 `IllegalStateException`，服务仅在正常返回后清除 pending_auto。
- `EventClient.retry(Context)`：同步后台，monitor/enabled=false 时直接返回，不创建事件。
- `EventClient.status(Context)`、`timestamp(long)` / `timestamp()`、`check(Context)`、`retryLatest(Context)`、`clearHistory(Context)`、`collect(Context,String,long)`。
- `SettingsStore.load(Context)` 返回 Config（baseUrl/key/umo/allowHttp）；`save(Context,String url,String key,String umo,boolean allowHttp)`。配置/事件都在 noBackupFilesDir，key 用 AndroidKeyStore AES/GCM；不向日志写凭据。

## 尚需真机/真实服务验证

- Android Keystore、界面生命周期、权限与 SQLite 恢复需使用相应集成测试，不以 JVM 网络测试替代。
- 隔离服务的 accepted 或 processing 不等于机器人送达。
- 超过 24 小时的不可变旧事件不能再次提交；UI 引导创建新快照与新事件号。
- 未送达事件不静默删除；100 条时阻止新建并提示用户显式删除。本地历史清除有确认。自动仅清理超过七天的 sent 摘要；未处理记录保留以避免丢失。
