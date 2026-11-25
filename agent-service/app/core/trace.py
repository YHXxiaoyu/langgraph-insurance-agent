"""跨服务链路追踪：从 X-Trace-Id 取（没有就生成），绑定到 structlog 上下文。

Java 在请求入口生成 traceId 并透传过来，本服务所有日志自动带上它；
回调 Java 内部接口时再原样发回去，形成闭环。

用纯 ASGI 中间件而不是 BaseHTTPMiddleware：后者会把请求处理放到另一个 task 里，
contextvars 不跟着走，SSE 流式响应期间日志就会丢 trace_id。
"""

import uuid

import structlog
from starlette.types import ASGIApp, Receive, Scope, Send

HEADER = "x-trace-id"          # ASGI 里的 header 名一律小写
HEADER_CANONICAL = "X-Trace-Id"  # 回给上游时用规范写法


class TraceIdMiddleware:
    """给每个 HTTP 请求绑定一个 trace_id，并在响应头里回写。"""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        trace_id = ""
        for name, value in scope.get("headers", []):
            if name == HEADER.encode():
                trace_id = value.decode("latin-1")
                break
        if not trace_id:
            trace_id = uuid.uuid4().hex[:16]

        # 先清掉可能残留的上下文，再绑定本次请求的
        structlog.contextvars.clear_contextvars()
        structlog.contextvars.bind_contextvars(trace_id=trace_id)

        async def send_with_header(message: dict) -> None:
            if message["type"] == "http.response.start":
                headers = list(message.get("headers", []))
                headers.append((HEADER.encode(), trace_id.encode("latin-1")))
                message = {**message, "headers": headers}
            await send(message)

        try:
            await self.app(scope, receive, send_with_header)
        finally:
            structlog.contextvars.clear_contextvars()


def current_trace_id() -> str | None:
    """取当前请求的 trace_id；不在请求上下文里时返回 None。"""
    return structlog.contextvars.get_contextvars().get("trace_id")