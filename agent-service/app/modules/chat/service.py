from uuid import UUID

from fastapi.sse import ServerSentEvent
from langchain.messages import HumanMessage
from langchain_core.messages import AIMessage
from langgraph.graph.state import CompiledStateGraph

from app.modules.chat.schema import ChatHistoryResponse, ChatMessageResponse


class ChatService:
    def __init__(self, agent: CompiledStateGraph):
        self.agent = agent

    async def internal_chat_stream(self, thread_id: UUID, message: str):
        """内部接口：会话归属由 Java 校验，这里只负责跑 Agent"""
        async for sse in self._stream(thread_id, message):
            yield sse

    async def chat_history(self, thread_id: UUID) -> ChatHistoryResponse:
        """从 LangGraph checkpoint 里读历史消息，供 Java 转发给前端"""
        config = {"configurable": {"thread_id": str(thread_id)}}
        state_snapshot = await self.agent.aget_state(config)

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
    async def delete_history(self, thread_id: UUID) -> None:
        """会话被删除时清掉对应的 checkpoint（MIGRATION 设计点 ②）"""

        # 主数据（会话记录）在 Java 的 MySQL 里，这里只清 Agent 侧的附属数据。
        # Java 是先删主数据再异步调这里，所以清不掉也只是留下无主的对话数据，
        # 不影响用户已经看到的删除结果。
        checkpointer = self.agent.checkpointer
        if checkpointer is None:
            return
        await checkpointer.adelete_thread(str(thread_id))

    async def _stream(self, thread_id: UUID, message: str):
        """真正跑 Agent 的地方"""
        _input = {"messages": [HumanMessage(content=message)]}
        config = {"configurable": {"thread_id": thread_id}}
        stream = await self.agent.astream_events(
            _input,
            config,
            version="v3"
        )

        # 流式返回结果
        async for event in stream:
            method = event["method"]
            # 判断事件类型，可以是 messages、interrupts 等等
            if method == "messages":
                data = event["params"]["data"][0]
                if isinstance(data, AIMessage):
                    yield ServerSentEvent(data=data.text, event="message")
                elif data.get("delta") and data["delta"].get("text"):
                    yield ServerSentEvent(data=data["delta"].get("text"), event="message")

        yield ServerSentEvent(data="END", event="done")