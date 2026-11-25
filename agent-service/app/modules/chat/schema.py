from typing import Any, Literal
from uuid import UUID

from pydantic import Field

from app.common.schemas import BaseSchema


class InternalChatRequest(BaseSchema):
    """Java 回调内部对话接口的入参。

    message 和 decision 二选一：
    - 新的一轮输入 → message
    - 用户在确认卡片上点了按钮 → decision（恢复被 interrupt 暂停的图）
    """
    user_id: int
    thread_id: UUID
    message: str | None = None
    decision: dict[str, Any] | None = None


class ChatMessageResponse(BaseSchema):
    """历史消息。

    additional_info 是助手回答附带的引用来源（前端按 type=clause_sources 渲染），
    用户消息恒为 None。
    """
    role: Literal["user", "assistant"]
    content: str
    additional_info: dict[str, Any] | None = None


class ChatHistoryResponse(BaseSchema):
    """会话历史响应。

    interrupt 不为空表示这个会话停在「等待确认」的状态，
    前端刷新后会重新弹出确认卡片。
    """
    thread_id: UUID
    messages: list[ChatMessageResponse]
    interrupt: Any | None = None