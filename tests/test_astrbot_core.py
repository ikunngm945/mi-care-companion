"""独立核心无需 AstrBot/aiohttp；HTTP 测试仅需插件 requirements。"""
import asyncio
import copy
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

from astrbot_plugin_mi_companion.core import EventStore, ProtocolError, authorize

NOW = 1800000000.0
UMO = "testbot:FriendMessage:123"


def payload(event_id="event-001"):
    data = {
        "protocol_version": 1, "event_id": event_id,
        "trigger": {"type": "manual_test"},
        "collected_at": "2027-01-15T08:00:00+00:00", "timezone": "Asia/Shanghai",
        "umo": UMO,
    }
    for kind, package in (("weather", "com.miui.weather2"), ("screen", "android"), ("health", "com.mi.health")):
        data[kind] = {"status": "missing", "source_package": package,
                      "collected_at": data["collected_at"], "source_updated_at": None,
                      "fields": {}, "reason": "尚未采集，源时间未知"}
    return data


class CoreTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = str(Path(self.tmp.name) / "events.sqlite3")
        self.store = EventStore(self.path, [UMO], now=lambda: NOW)
        self.addCleanup(self.store.close)
        self.calls = []

    async def llm(self, umo, prompt):
        self.assertEqual(umo, UMO)
        self.assertIn('"source_updated_at":null', prompt)
        self.assertIn("不可信", prompt)
        self.calls.append("llm")
        return "记得适当休息；部分数据缺失。"

    async def send(self, umo, text):
        self.assertEqual((umo, text), (UMO, "记得适当休息；部分数据缺失。"))
        self.calls.append("send")

    async def test_llm_finishes_before_sending_and_status_does_not_expose_health(self):
        self.assertEqual(self.store.accept(payload())["status"], "accepted")
        await self.store.process("event-001", self.llm, self.send)
        self.assertEqual(self.calls, ["llm", "send"])
        result = self.store.get("event-001")
        self.assertEqual(result["status"], "sent")
        self.assertNotIn("health", result)
        self.assertNotIn("payload", result)

    async def test_duplicate_survives_restart_without_resending(self):
        self.store.accept(payload())
        await self.store.process("event-001", self.llm, self.send)
        self.store.close()
        self.store = EventStore(self.path, [UMO], now=lambda: NOW)
        self.addCleanup(self.store.close)
        self.assertEqual(self.store.accept(payload())["status"], "sent")
        await self.store.process("event-001", self.llm, self.send)
        self.assertEqual(self.calls, ["llm", "send"])

    async def test_same_id_different_content_is_conflict(self):
        self.store.accept(payload())
        changed = payload()
        changed["health"]["reason"] = "内容不同"
        with self.assertRaises(ProtocolError) as cm:
            self.store.accept(changed)
        self.assertEqual(cm.exception.status, 409)

    async def test_concurrent_processing_runs_only_once(self):
        self.store.accept(payload())
        entered, release = asyncio.Event(), asyncio.Event()
        async def slow_llm(umo, prompt):
            entered.set()
            await release.wait()
            return await self.llm(umo, prompt)
        first = asyncio.create_task(self.store.process("event-001", slow_llm, self.send))
        await entered.wait()
        await self.store.process("event-001", slow_llm, self.send)
        release.set()
        await first
        self.assertEqual(self.calls, ["llm", "send"])

    async def test_llm_failure_never_sends_or_leaks_exception(self):
        self.store.accept(payload())
        async def broken(umo, prompt):
            raise RuntimeError("secret-provider-token")
        await self.store.process("event-001", broken, self.send)
        result = self.store.get("event-001")
        self.assertEqual((result["status"], result["stage"]), ("failed", "llm"))
        self.assertNotIn("secret", json.dumps(result))
        self.assertEqual(self.calls, [])

    async def test_send_failure_becomes_unknown_and_is_not_retried(self):
        self.store.accept(payload())
        async def broken(umo, text):
            self.calls.append("send")
            raise TimeoutError("远端可能已经收到了")
        await self.store.process("event-001", self.llm, broken)
        self.assertEqual(self.store.get("event-001")["status"], "delivery_unknown")
        await self.store.process("event-001", self.llm, broken)
        self.assertEqual(self.calls, ["llm", "send"])

    async def test_restart_during_send_is_unknown(self):
        self.store.accept(payload())
        async def crash(umo, text):
            raise asyncio.CancelledError()
        with self.assertRaises(asyncio.CancelledError):
            await self.store.process("event-001", self.llm, crash)
        self.store.close()
        self.store = EventStore(self.path, [UMO], now=lambda: NOW)
        self.addCleanup(self.store.close)
        self.assertEqual(self.store.get("event-001")["status"], "delivery_unknown")
        await self.store.process("event-001", self.llm, self.send)
        self.assertEqual(self.calls, ["llm"])

    async def test_restart_during_llm_can_retry_safely(self):
        self.store.accept(payload())
        async def crash(umo, prompt):
            raise asyncio.CancelledError()
        with self.assertRaises(asyncio.CancelledError):
            await self.store.process("event-001", crash, self.send)
        self.store.close()
        self.store = EventStore(self.path, [UMO], now=lambda: NOW)
        self.addCleanup(self.store.close)
        self.assertEqual(self.store.pending_ids(), ["event-001"])
        await self.store.process("event-001", self.llm, self.send)
        self.assertEqual(self.store.get("event-001")["status"], "sent")

    async def test_invalid_protocol_target_and_remote_overrides_rejected(self):
        for field, value, status in (("protocol_version", 2, 400), ("umo", "testbot:FriendMessage:456", 403),
                                     ("umo", "bad", 400), ("provider_id", "remote", 400),
                                     ("system_prompt", "override", 400)):
            with self.subTest(field=field, value=value):
                data = payload()
                data[field] = value
                with self.assertRaises(ProtocolError) as cm:
                    self.store.accept(data)
                self.assertEqual(cm.exception.status, status)

    async def test_invalid_timestamps_large_strings_and_nonfinite_values_rejected(self):
        cases = []
        for field, value in (("event_id", "x" * 129), ("collected_at", "bad"),
                             ("collected_at", "2027-01-15T08:00:00")):
            data = payload(); data[field] = value; cases.append(data)
        data = payload(); data["health"]["reason"] = "x" * 4097; cases.append(data)
        data = payload(); data["health"]["fields"]["heart_rate"] = float("nan"); cases.append(data)
        data = payload(); data["health"]["source_updated_at"] = "bad"; cases.append(data)
        for data in cases:
            with self.subTest(data=str(data)[:120]), self.assertRaises(ProtocolError):
                self.store.accept(data)

    async def test_24_hour_expiry_rejected_but_duplicate_is_still_queryable(self):
        data = payload()
        data["collected_at"] = "2027-01-14T07:59:59+00:00"
        with self.assertRaises(ProtocolError) as cm:
            self.store.accept(data)
        self.assertEqual(cm.exception.status, 422)
        self.store.accept(payload())
        self.store.now = lambda: NOW + 86401
        self.assertEqual(self.store.accept(payload())["status"], "accepted")
        await self.store.process("event-001", self.llm, self.send)
        self.assertEqual(self.store.get("event-001")["stage"], "expired")
        self.assertEqual(self.calls, [])

    async def test_unpaired_unicode_surrogates_are_protocol_error(self):
        data = payload()
        data["health"]["reason"] = "\ud800"
        with self.assertRaises(ProtocolError) as cm:
            self.store.accept(data)
        self.assertEqual(cm.exception.status, 400)

    async def test_malformed_trigger_returns_protocol_error_not_internal_error(self):
        data = payload()
        data["trigger"]["type"] = []
        with self.assertRaises(ProtocolError) as cm:
            self.store.accept(data)
        self.assertEqual(cm.exception.status, 400)

    async def test_expiry_during_llm_prevents_stale_send(self):
        self.store.accept(payload())
        async def delayed(umo, prompt):
            self.store.now = lambda: NOW + 86401
            return "生成完成时已过期"
        await self.store.process("event-001", delayed, self.send)
        self.assertEqual(self.store.get("event-001")["stage"], "expired")
        self.assertEqual(self.calls, [])

    async def test_platform_not_found_is_failed_not_sent(self):
        self.store.accept(payload())
        async def missing_platform(umo, text):
            return False
        await self.store.process("event-001", self.llm, missing_platform)
        self.assertEqual((self.store.get("event-001")["status"], self.store.get("event-001")["stage"]), ("failed", "send"))

    async def test_empty_allowlist_is_rejected(self):
        with self.assertRaises(ValueError):
            EventStore(":memory:", [])

    async def test_utf8_body_limit_even_without_http(self):
        data = payload()
        data["health"]["fields"] = {str(i): "中" * 2000 for i in range(30)}
        with self.assertRaises(ProtocolError) as cm:
            self.store.accept(data)
        self.assertEqual(cm.exception.status, 413)

    async def test_authentication_requires_nonempty_independent_bearer_key(self):
        for supplied, key in (("Bearer ", ""), ("Bearer wrong", "correct"), ("Basic correct", "correct"),
                              (None, "correct"), ("Bearer 中文", "correct")):
            with self.subTest(supplied=supplied), self.assertRaises(ProtocolError) as cm:
                authorize(supplied, key)
            self.assertEqual(cm.exception.status, 401)
        authorize("Bearer correct", "correct")


@unittest.skipUnless(importlib.util.find_spec("aiohttp"), "HTTP 测试需安装插件 requirements；标准库核心不受影响")
class HttpTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        from aiohttp.test_utils import TestClient, TestServer
        from astrbot_plugin_mi_companion.server import create_app
        self.tmp = tempfile.TemporaryDirectory()
        self.store = EventStore(str(Path(self.tmp.name) / "http.sqlite3"), [UMO], now=lambda: NOW)
        self.messages = []
        async def llm(umo, prompt):
            return "真实 HTTP 本地测试回复"
        async def send(umo, text):
            self.messages.append((umo, text))
        self.client = TestClient(TestServer(create_app(self.store, "test-only-key", llm, send)))
        await self.client.start_server()
        self.headers = {"Authorization": "Bearer test-only-key"}

    async def asyncTearDown(self):
        await self.client.close()
        self.store.close()
        self.tmp.cleanup()

    async def test_all_routes_require_bearer_authentication(self):
        for method, path in (("GET", "/mi-companion/capabilities"), ("POST", "/mi-companion/events"),
                             ("GET", "/mi-companion/events/event-001")):
            response = await self.client.request(method, path)
            self.assertEqual(response.status, 401)

    async def test_capabilities_post_poll_and_duplicate_use_real_http(self):
        response = await self.client.get("/mi-companion/capabilities", headers=self.headers)
        info = await response.json()
        self.assertEqual((info["service"], info["protocol_version"]), ("astrbot-mi-companion", 1))
        for _ in range(2):
            response = await self.client.post("/mi-companion/events", headers=self.headers, json=payload())
            self.assertIn(response.status, (200, 202))
            await response.read()
        response = await self.client.get("/mi-companion/events/event-001", headers=self.headers)
        self.assertEqual((await response.json())["status"], "sent")
        self.assertEqual(self.messages, [(UMO, "真实 HTTP 本地测试回复")])

    async def test_oversize_malformed_and_duplicate_json_keys_rejected(self):
        for raw, expected in ((b"x" * (128 * 1024 + 1), 413), (b"{", 400),
                               (b'{"protocol_version":1,"protocol_version":2}', 400)):
            response = await self.client.post("/mi-companion/events", headers=self.headers, data=raw)
            self.assertEqual(response.status, expected)

    async def test_unknown_event_is_404(self):
        response = await self.client.get("/mi-companion/events/not-found", headers=self.headers)
        self.assertEqual(response.status, 404)

    async def test_astrbot_adapter_uses_target_default_provider_and_checks_send_result(self):
        import importlib
        import sys
        import types
        from unittest.mock import patch
        api = types.ModuleType("astrbot.api")
        stars = types.ModuleType("astrbot.api.star")
        event = types.ModuleType("astrbot.api.event")
        class Star:
            def __init__(self, context):
                self.context = context
        stars.Star = Star
        stars.Context = object
        stars.StarTools = type("StarTools", (), {"get_data_dir": staticmethod(lambda name: Path(self.tmp.name))})
        api.AstrBotConfig = dict
        api.logger = __import__("logging").getLogger("test-mi")
        class MessageChain:
            def message(self, text):
                self.text = text
                return self
        event.MessageChain = MessageChain
        observed = []
        class Context:
            async def get_current_chat_provider_id(self, umo):
                observed.append(("provider", umo))
                return "target-default-model"
            async def llm_generate(self, **kwargs):
                observed.append(("llm", kwargs))
                return types.SimpleNamespace(completion_text="模型已生成")
            async def send_message(self, umo, chain):
                observed.append(("send", umo, chain.text))
                return False
        with patch.dict(sys.modules, {"astrbot": types.ModuleType("astrbot"), "astrbot.api": api,
                                      "astrbot.api.star": stars, "astrbot.api.event": event}):
            module = importlib.import_module("astrbot_plugin_mi_companion.main")
            plugin = module.MiCompanionPlugin(Context(), {"api_key": "x" * 40, "allowed_umos": [UMO]})
            text = await plugin.generate(UMO, "设备事实")
            self.assertEqual(text, "模型已生成")
            self.assertFalse(await plugin.send(UMO, text))
            self.assertEqual(observed[0], ("provider", UMO))
            self.assertEqual(observed[1][1]["chat_provider_id"], "target-default-model")
            self.assertIn("不可信", observed[1][1]["system_prompt"])
            self.assertNotIn("tools", observed[1][1])
            self.assertEqual(observed[2], ("send", UMO, "模型已生成"))
        sys.modules.pop("astrbot_plugin_mi_companion.main", None)

    async def test_content_encoding_is_not_accepted(self):
        response = await self.client.post("/mi-companion/events", headers={**self.headers, "Content-Encoding": "gzip"}, data=b"not-gzip")
        self.assertEqual(response.status, 415)


if __name__ == "__main__":
    unittest.main()
