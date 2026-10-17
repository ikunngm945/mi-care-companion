# 0.3.0 官方 HTTP 接口版

## 范围

直接使用 AstrBot 官方 HTTP API，不再依赖配套插件。任意地址 HTTP 需显式开启并显示明文风险；推荐 HTTPS。保留采集、连续息屏与解锁使用触发及手动测试。

## 接口契约

- [OfficialApi](<../android/src/cn/local/micompanion/OfficialApi.java>)使用 `/api/v1/im/bots` 和聊天会话等 GET 接口预检。列表存在仅表示配置可见，不证明平台在线。
- `POST /api/v1/chat` 每任务使用独立会话。SSE 必须有完整 end 和正常 EOF；推理内容、工具结果、不可信 complete 数据、错误或半帧不能作为正文发送。
- `POST /api/v1/im/messages` 只提交生成正文和目标 UMO。成功返回不等于平台送达或已读。
- 官方无 IM 幂等 key 或发送状态查询。生成前持久化 `generating`，发送前持久化 `sending`；中断或响应丢失时保守停止，结果未知不自动重发。
- 仅无模型副作用的接口预检可退避重试；24 小时有效期在预检和副作用屏障处复核。
- URL 不允许内嵌凭据、query、fragment 或重定向；旧插件事件不会自动转投官方接口。
- 客户端能检测工具并拒绝转发，但不能撤销服务端执行的工具；断流也不保证远端生成停止。
- 官方聊天及模型供应商可能保存提交内容，保留策略由部署者管理。

## 测试与限制

[OfficialApiTest](<../android/tests/OfficialApiTest.java>)验证鉴权、预检、请求正文、业务错误、重定向拒绝、SSE 完整性、工具过滤及 UTF-8 边界；[OfficialDeliveryTest](<../android/tests/OfficialDeliveryTest.java>)验证写前屏障、超时、有效期、进程中断与不重发；[ClientTransportTest](<../android/tests/ClientTransportTest.java>)验证持久化状态与调用顺序。

隔离 HTTP、UI 或接口预检不能证明真实模型输出、平台送达、已读或长期后台可靠性。配置档自动匹配及自定义提示词由后续版本扩展，见[配置与提示词](<verification-route-prompt.md>)。
