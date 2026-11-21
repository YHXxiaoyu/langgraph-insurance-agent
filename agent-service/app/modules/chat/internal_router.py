import hmac
from typing import Annotated

from fastapi import APIRouter, Depends, Header, Request
from fastapi.sse import EventSourceResponse

from app.core.config import settings
from app.core.exceptions import ApplicationError
from .schema import InternalChatRequest
from .service import ChatService

router = APIRouter(prefix="/internal/v1/agent", tags=["内部接口（Java 调用）"])


class InternalTokenInvalid(ApplicationError):
    """内部令牌不对"""

    status_code: int = 401
    code: str = "INTERNAL_TOKEN_INVALID"
    message: str = "内部令牌无效"


def verify_internal_token(
    x_internal_token: Annotated[str | None, Header(alias="X-Internal-Token")] = None,
):
    """
    写成依赖而不是写在处理函数里：
    鉴权必须在开始推流之前完成，否则 200 头已经发出去，再报错调用方也收不到。
    """
    expected = settings.security.internal_token
    # compare_digest 是常量时间比较，避免靠响应时间差逐位猜令牌
    if not x_internal_token or not hmac.compare_digest(x_internal_token, expected):
        raise InternalTokenInvalid()


@router.post("/chat", response_class=EventSourceResponse,
             dependencies=[Depends(verify_internal_token)])
async def internal_chat(request: InternalChatRequest, http_request: Request):
    # 不带 repository：会话归属由 Java 校验，Python 这里只跑 Agent
    service = ChatService(http_request.app.state.agent)
    async for sse in service.internal_chat_stream(request.thread_id, request.message):
        yield sse
