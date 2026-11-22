from typing import Literal
from uuid import UUID

from pydantic import BaseModel, Field


class InternalChatRequest(BaseModel):
    """Java 回调内部对话接口的入参。会话归属已由 Java 校验过，这里不再查库。"""

    user_id: int
    thread_id: UUID
    message: str = Field(min_length=1)


class ChatMessageResponse(BaseModel):
    """历史消息"""

    role: Literal["user", "assistant"]
    content: str


class ChatHistoryResponse(BaseModel):
    """会话历史响应"""

    thread_id: UUID
    messages: list[ChatMessageResponse]
