# 历史插件测试范围

本目录是早期独立插件方案，当前 APK 使用官方 API，不依赖此插件。

## 执行

在仓库根目录安装所需测试依赖后运行：

```sh
python3.12 -m unittest discover -s tests -p test_astrbot_core.py -v
python3.12 -m py_compile astrbot_plugin_mi_companion/core.py astrbot_plugin_mi_companion/server.py astrbot_plugin_mi_companion/main.py
python3.12 -m json.tool astrbot_plugin_mi_companion/_conf_schema.json
```

## 覆盖

- 协议鉴权、目标白名单、输入结构、Unicode、重复键、体积及有效期。
- SQLite 事务去重、内容冲突、并发处理、重启恢复、生成后发送。
- 模型错误、生成期间过期、发送返回失败、异常或中断不重发。
- HTTP 测试使用隔离回环服务；缺少依赖时相关测试显式跳过，不能据此声称全部已验证。

## 边界

隔离适配器不证明真实插件加载、模型输出、目标平台送达或已读。HTTPS 示例不是已部署服务。单进程单 worker，不允许多实例共用 SQLite；外部发送不能提供分布式 exactly-once 保证。

超过 24 小时的事件不能强制重发；结果未知需人工确认，必要时新建事件。终态快照删除但去重墓碑保留，未送达记录不静默清理。不要将数据库、缓存、凭据或依赖目录打包公开。
