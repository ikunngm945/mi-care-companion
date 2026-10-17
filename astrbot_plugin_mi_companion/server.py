"""独立 aiohttp 监听器，不注册或修改 AstrBot dashboard。"""
import asyncio
import json
import logging
import sqlite3

from aiohttp import web

from .core import MAX_BODY, ProtocolError, authorize

log = logging.getLogger(__name__)


def unique_object(pairs):
    data = {}
    for key, value in pairs:
        if key in data:
            raise ProtocolError(400, "JSON 含重复字段，请修正请求。")
        data[key] = value
    return data


def create_app(store, api_key, llm, send):
    if not isinstance(api_key, str) or not api_key.strip():
        raise ValueError("请先在插件配置中填写独立随机 API key，服务不会以空密钥启动。")
    wake = asyncio.Event()

    @web.middleware
    async def guard(request, handler):
        try:
            authorize(request.headers.get("Authorization"), api_key)
            if request.headers.get("Content-Encoding", "identity").lower() != "identity":
                raise ProtocolError(415, "不支持压缩请求，请发送未压缩 JSON。")
            response = await handler(request)
        except ProtocolError as exc:
            response = web.json_response({"error": str(exc)}, status=exc.status)
        except web.HTTPRequestEntityTooLarge:
            response = web.json_response({"error": "请求体超过 128 KiB。"}, status=413)
        except sqlite3.Error:
            log.error("Mi Companion 数据库操作失败，请检查磁盘、权限和数据库完整性。")
            response = web.json_response({"error": "事件数据库暂不可用，请稍后使用同一事件号重试。"}, status=503)
        response.headers["Cache-Control"] = "no-store"
        if response.status == 401:
            response.headers["WWW-Authenticate"] = "Bearer"
        return response

    async def capabilities(request):
        return web.json_response({"service": "astrbot-mi-companion", "protocol_version": 1,
                                  "max_body_bytes": MAX_BODY})

    async def accept(request):
        if request.content_length is not None and request.content_length > MAX_BODY:
            raise ProtocolError(413, "请求体超过 128 KiB。")
        # 迭代读取也限制无 Content-Length 的 chunked 请求，避免先无界缓冲。
        raw = bytearray()
        async for chunk in request.content.iter_chunked(16384):
            raw.extend(chunk)
            if len(raw) > MAX_BODY:
                raise ProtocolError(413, "请求体超过 128 KiB。")
        try:
            data = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object,
                              parse_constant=lambda _: (_ for _ in ()).throw(ProtocolError(400, "JSON 数值无效。")))
        except (ValueError, UnicodeError, RecursionError) as exc:
            if isinstance(exc, ProtocolError):
                raise
            raise ProtocolError(400, "请求不是有效的 UTF-8 JSON。") from None
        result = store.accept(data)
        wake.set()
        return web.json_response(result, status=202 if result["status"] == "accepted" else 200)

    async def status(request):
        result = store.get(request.match_info["event_id"])
        if result is None:
            raise ProtocolError(404, "事件不存在，请确认事件号。")
        return web.json_response(result)

    async def worker():
        # ponytail: 单 worker 有界串行处理；需要多实例扩展时使用独立队列和租约。
        while True:
            await wake.wait()
            wake.clear()
            for event_id in store.pending_ids():
                try:
                    await store.process(event_id, llm, send)
                except sqlite3.Error:
                    log.error("Mi Companion 状态持久化失败；请修复存储后重启插件恢复。")

    async def lifecycle(app):
        task = asyncio.create_task(worker(), name="mi-companion-worker")
        wake.set()  # 恢复已接收/生成阶段中断的事件。
        yield
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)

    app = web.Application(middlewares=[guard], client_max_size=MAX_BODY,
                          handler_args={"auto_decompress": False})
    app.router.add_get("/mi-companion/capabilities", capabilities)
    app.router.add_post("/mi-companion/events", accept)
    app.router.add_get("/mi-companion/events/{event_id}", status)
    app.cleanup_ctx.append(lifecycle)
    return app
