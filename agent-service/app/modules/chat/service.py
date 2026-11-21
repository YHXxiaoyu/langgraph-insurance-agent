from uuid import UUID

from fastapi.sse import ServerSentEvent
from langchain.messages import HumanMessage
from langchain_core.messages import AIMessage
from langgraph.graph.state import CompiledStateGraph

from app.modules.chat_thread.repository import ChatThreadRepository
from app.modules.chat_thread.exceptions import ChatThreadNotFound
from .schema import ChatRequest


class ChatService:

    def __init__(self, agent: CompiledStateGraph, repository: ChatThreadRepository | None = None):
        self.agent = agent
        # 内部接口不需要查库，传 None 即可
        self.repository = repository

    async def chat_stream(self, user_id: int, request: ChatRequest):
        """公开接口：先校验会话归属，再流式输出"""

        # 1.校验，判断当前 thread_id 是否属于当前用户
        chat_thread = await self.repository.required_owner(user_id, request.thread_id)
        if not chat_thread:
            # 指定会话不属于当前用户
            raise ChatThreadNotFound()

        async for sse in self._stream(request.thread_id, request.message):
            yield sse

    async def internal_chat_stream(self, thread_id: UUID, message: str):
        """内部接口：归属校验已由 Java 完成，这里只负责跑 Agent"""
        async for sse in self._stream(thread_id, message):
            yield sse

    async def _stream(self, thread_id: UUID, message: str):
        """真正跑 Agent 的地方，公开接口和内部接口共用"""

        # 1.组织消息
        _input = {"messages": [HumanMessage(message)]}

        # 2.组织config，实现会话记忆
        config = {"configurable": {"thread_id": thread_id}}

        # 3.调用Agent
        stream = await self.agent.astream_events(
            _input,
            config,
            version="v3"
        )

        # 4.返回结果
        async for event in stream:
            method = event['method']
            # 判断事件类型，可以是messages、interrupts等等
            if method == 'messages':
                data = event['params']['data'][0]
                if isinstance(data, AIMessage):
                    yield ServerSentEvent(data=data.text, event="message")
                elif data.get('delta') and data['delta'].get('text'):
                    yield ServerSentEvent(data=data['delta'].get('text'), event="message")

        yield ServerSentEvent(data="END", event="done")
