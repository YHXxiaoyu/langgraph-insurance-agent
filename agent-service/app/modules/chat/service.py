from uuid import UUID

from fastapi.sse import ServerSentEvent
from langchain.messages import HumanMessage
from langchain_core.messages import AIMessage
from langgraph.graph.state import CompiledStateGraph

from .schema import ChatHistoryResponse, ChatMessageResponse


class ChatService:

    def __init__(self, agent: CompiledStateGraph):
        self.agent = agent

    async def internal_chat_stream(self, thread_id: UUID, message: str):
        """内部接口：归属校验已由 Java 完成，这里只负责跑 Agent"""
        async for sse in self._stream(thread_id, message):
            yield sse

    async def chat_history(self, thread_id: UUID) -> ChatHistoryResponse:
        """从 LangGraph checkpoint 里读历史消息，供 Java 转发给前端"""

        # 1.读取该会话的最终状态
        config = {"configurable": {"thread_id": str(thread_id)}}
        state_snapshot = await self.agent.aget_state(config)

        # 2.只保留能展示的 user / assistant 文本消息
        messages: list[ChatMessageResponse] = []
        for message in state_snapshot.values.get("messages", []):
            if isinstance(message, HumanMessage):
                role = "user"
            elif isinstance(message, AIMessage):
                role = "assistant"
            else:
                continue
            if message.text:
                messages.append(ChatMessageResponse(role=role, content=message.text))

        return ChatHistoryResponse(thread_id=thread_id, messages=messages)

    async def _stream(self, thread_id: UUID, message: str):
        """真正跑 Agent 的地方"""

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
