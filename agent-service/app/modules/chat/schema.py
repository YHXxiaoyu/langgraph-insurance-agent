from uuid import UUID

from pydantic import Field, BaseModel

class ChatRequest(BaseModel):
    """基础对话请求"""

    thread_id: UUID
    message: str = Field(min_length=1)


class InternalChatRequest(BaseModel):
    """Java 回调内部对话接口的入参。会话归属已由 Java 校验过，这里不再查库。"""

    user_id: int
    thread_id: UUID
    message: str = Field(min_length=1)
