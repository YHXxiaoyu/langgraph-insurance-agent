"""调用 Java 内部接口的统一出口。

阶段 5 之后 Python 不再连接业务库，产品等业务数据全部通过 Java 的
/internal/v1/** 获取；两边用 X-Internal-Token 互相认证。
"""

from typing import Any

import httpx

from app.core.config import settings
from app.core.logging import get_logger

logger = get_logger(__name__)


class JavaServiceError(Exception):
    """调用 Java 内部接口失败"""


_client: httpx.AsyncClient | None = None


def _client_instance() -> httpx.AsyncClient:
    """懒加载单例：整个进程复用同一个连接池"""
    global _client
    if _client is None:
        _client = httpx.AsyncClient(
            base_url=settings.java.base_url,
            timeout=settings.java.timeout,
            headers={"X-Internal-Token": settings.security.internal_token},
        )
    return _client


async def close_client() -> None:
    """应用关闭时释放连接池"""
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None


async def call_java(method: str, path: str, payload: Any = None) -> Any:
    """调 Java 内部接口，并把统一返回体的壳拆掉。

    Java 返回 {"code":200,"msg":"OK","data":...}，这里只把 data 交给调用方；
    网络错误、非 200、业务码不是 200 一律抛 JavaServiceError。
    """
    try:
        response = await _client_instance().request(method, path, json=payload)
    except httpx.HTTPError as exc:
        logger.error("调用 Java 内部接口失败", path=path, reason=str(exc))
        raise JavaServiceError("Java 服务暂时不可用") from exc

    if not 200 <= response.status_code < 300:
        logger.error("Java 内部接口返回异常状态",
                     path=path, status=response.status_code,
                     body=response.text[:200])
        raise JavaServiceError(f"Java 内部接口返回 {response.status_code}")

    body = response.json()
    if body.get("code") != 200:
        raise JavaServiceError(body.get("msg") or "Java 内部接口业务失败")
    return body.get("data")