"""Mi Companion 独立 AstrBot 插件入口。"""
import asyncio
import os

from aiohttp import web
from astrbot.api import AstrBotConfig, logger
from astrbot.api.event import MessageChain
from astrbot.api.star import Context, Star, StarTools

from .core import EventStore, SYSTEM_PROMPT
from .server import create_app


class MiCompanionPlugin(Star):
    def __init__(self, context: Context, config: AstrBotConfig):
        super().__init__(context)
        self.config = config
        self.runner = None
        self.store = None

    async def generate(self, umo, prompt):
        provider_id = self.config.get("provider_id", "").strip()
        if not provider_id:
            provider_id = await self.context.get_current_chat_provider_id(umo)
        async with asyncio.timeout(120):
            response = await self.context.llm_generate(
                chat_provider_id=provider_id, prompt=prompt, system_prompt=SYSTEM_PROMPT,
            )
        return response.completion_text

    async def send(self, umo, text):
        async with asyncio.timeout(60):
            # False 是未找到平台，不可当作发送成功；异常由核心保守标记未知。
            return await self.context.send_message(umo, MessageChain().message(text))

    async def initialize(self):
        key = self.config.get("api_key", "")
        if not isinstance(key, str) or len(key) < 32 or key != key.strip():
            raise ValueError("请先设置至少 32 字符的独立随机 API key（首尾不可有空格），再启用插件。")
        host = self.config.get("listen_host", "127.0.0.1")
        port = self.config.get("listen_port", 8765)
        if not isinstance(host, str) or not host or type(port) is not int or not 1 <= port <= 65535:
            raise ValueError("监听地址或端口无效，端口必须在 1–65535 之间。")
        if host not in {"127.0.0.1", "::1", "localhost"}:
            logger.warning("Mi Companion 正在非回环地址监听；请使用防火墙和 HTTPS 反向代理保护，不要直接暴露 HTTP。")
        data_dir = StarTools.get_data_dir("astrbot_plugin_mi_companion")
        os.chmod(data_dir, 0o700)
        db_path = data_dir / "events.sqlite3"
        try:
            self.store = EventStore(str(db_path), self.config.get("allowed_umos", []))
            os.chmod(db_path, 0o600)
            app = create_app(self.store, key, self.generate, self.send)
            self.runner = web.AppRunner(app, access_log=None, auto_decompress=False,
                                        shutdown_timeout=5)
            await self.runner.setup()
            await web.TCPSite(self.runner, host, port).start()
        except BaseException:
            await self.terminate()
            raise
        logger.info("Mi Companion 专用接口已启动；2xx 仅表示接收，送达请查询事件状态。")

    async def terminate(self):
        if self.runner is not None:
            await self.runner.cleanup()
            self.runner = None
        if self.store is not None:
            self.store.close()
            self.store = None
