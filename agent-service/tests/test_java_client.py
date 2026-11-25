"""java_client 单元测试：不联网、不起 Java。

用 httpx.MockTransport 拦掉真实请求，重点验证「R<T> 外壳怎么拆」和
「失败时抛什么」——这两点决定了 Python 侧拿到的是数据还是一句看不懂的错误。
"""

import httpx
import pytest
import structlog

from app.infra import java_client
from app.infra.java_client import JavaServiceError, call_java


@pytest.fixture
def mock_java(monkeypatch):
    """把懒加载单例换成 MockTransport 客户端。

    默认头要和 _client_instance() 保持一致，否则测不出「每请求加 trace 头」
    会不会把内部令牌头覆盖掉。
    """

    def install(handler):
        client = httpx.AsyncClient(
            transport=httpx.MockTransport(handler),
            base_url="http://java.test",
            headers={"X-Internal-Token": "test-token"},
        )
        monkeypatch.setattr(java_client, "_client", client)
        return client

    return install


async def test_unwraps_r_envelope(mock_java):
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["path"] = request.url.path
        seen["token"] = request.headers.get("X-Internal-Token")
        seen["body"] = request.content
        return httpx.Response(200, json={"code": 200, "msg": "OK", "data": [{"id": 1}]})

    mock_java(handler)

    data = await call_java(
        "POST", "/internal/v1/products/candidates", {"categories": ["medical"]})

    # 调用方拿到的应该只有 data，不带 code/msg 外壳
    assert data == [{"id": 1}]
    assert seen["path"] == "/internal/v1/products/candidates"
    assert seen["token"] == "test-token"
    assert b"medical" in seen["body"]


async def test_business_code_error_raises_with_msg(mock_java):
    mock_java(lambda request: httpx.Response(
        200, json={"code": 404, "msg": "产品不存在"}))

    with pytest.raises(JavaServiceError, match="产品不存在"):
        await call_java("GET", "/internal/v1/products/999")


async def test_non_2xx_raises_with_status(mock_java):
    mock_java(lambda request: httpx.Response(502, text="bad gateway"))

    with pytest.raises(JavaServiceError, match="返回 502"):
        await call_java("GET", "/internal/v1/products")


async def test_network_error_raises_friendly_message(mock_java):
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    mock_java(handler)

    with pytest.raises(JavaServiceError, match="Java 服务暂时不可用"):
        await call_java("GET", "/internal/v1/products")


async def test_trace_id_is_forwarded(mock_java):
    seen = {}
    mock_java(lambda request: (
        seen.update(trace=request.headers.get("X-Trace-Id"))
        or httpx.Response(200, json={"code": 200, "data": None})
    ))

    structlog.contextvars.bind_contextvars(trace_id="trace-abc")
    try:
        await call_java("GET", "/internal/v1/products")
    finally:
        structlog.contextvars.clear_contextvars()

    assert seen["trace"] == "trace-abc"
