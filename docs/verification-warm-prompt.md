# 0.6.2 温暖关怀提示词

默认建议按“称呼问候与当地时间 → 天气 → 运动健康 → 同周期屏幕时长”自然分段，语气温柔，不说教、责备或制造焦虑；不强制在正文输出生硬免责声明。

## 实现

- [AdvicePrompt.DEFAULT](<../android/src/cn/local/micompanion/AdvicePrompt.java>)调整措辞，不移除不可覆盖的事实、非诊断、长度与无工具调用要求。
- 时间来自快照顶层 `collected_at` 和 `timezone`，不把历史睡眠或预报时间当作现在；示例不是事实。
- 升级不覆盖已保存的自定义提示词。恢复默认只修改草稿，仍需保存。
- 不新增采集字段或依赖，不改变其他配置和投递状态机。

## 回归与边界

[OfficialApiTest](<../android/tests/OfficialApiTest.java>)覆盖请求中的分段指令、语气和时间来源；[AdvicePromptTest](<../android/tests/AdvicePromptTest.java>)覆盖固定安全约束、Unicode 和长度。UI 测试覆盖恢复草稿、超限及无效输入不联网。

模型仍可能偏离语气或格式指令；隔离测试不代表实际生成效果。问候时间依据采集时刻，不保证送达瞬间时钟一致。
