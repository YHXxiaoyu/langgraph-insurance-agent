import hmac
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Request
from fastapi.sse import EventSourceResponse

from app.core.config import settings
from app.core.exceptions import ApplicationError
from app.modules.chat.schema import ChatHistoryResponse, InternalChatRequest
from app.modules.chat.service import ChatService

router = APIRouter(prefix="/internal/v1/agent", tags=["internal-agent"])


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
    鉴权必须在开始推流之前完成，否则 200 头已经发出去，再报错前端也收不到。
    """
    expected = settings.security.internal_token
    # compare_digest 是常量时间比较，避免靠响应时间差逐位猜令牌
    if not x_internal_token or not hmac.compare_digest(x_internal_token, expected):
        raise InternalTokenInvalid()


@router.post("/chat", response_class=EventSourceResponse, dependencies=[Depends(verify_internal_token)])
async def internal_chat(request: InternalChatRequest, http_request: Request):
    # 只跑 Agent：归属校验由 Java 完成，Python 不查业务库
    service = ChatService(http_request.app.state.agent)
    async for sse in service.internal_chat_stream(
            request.thread_id, request.message, request.decision):
        yield sse


@router.get("/threads/{thread_id}/messages", response_model=ChatHistoryResponse,
            dependencies=[Depends(verify_internal_token)])
async def chat_history(thread_id: UUID, http_request: Request):
    """历史消息：从 checkpoint 读，供 Java 转给前端（MIGRATION 里的方案 A）"""
    service = ChatService(http_request.app.state.agent)
    return await service.chat_history(thread_id)

@router.delete("/threads/{thread_id}", status_code=204,
               dependencies=[Depends(verify_internal_token)])
async def delete_thread(thread_id: UUID, http_request: Request) -> None:
    """会话删除后的 checkpoint 清理，由 Java 异步调用"""
    service = ChatService(http_request.app.state.agent)
    await service.delete_history(thread_id)