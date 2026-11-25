from typing import Any
from uuid import UUID

from fastapi.sse import ServerSentEvent
from langchain.messages import HumanMessage
from langchain_core.messages import AIMessage
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Command
from app.core.logging import get_logger
from app.agents.citations import clear as clear_citations
from app.agents.citations import current as current_citations
from app.agents.citations import reset as reset_citations
from app.agents.plan import clear as clear_plan_products
from app.agents.plan import reset as reset_plan_products
from app.modules.chat.schema import ChatHistoryResponse, ChatMessageResponse

logger = get_logger(__name__)


class ChatService:
    def __init__(self, agent: CompiledStateGraph):
        self.agent = agent

    async def internal_chat_stream(
        self,
        thread_id: UUID,
        message: str | None,
        decision: dict[str, Any] | None = None,
    ):
        """内部接口：会话归属由 Java 校验，这里只负责跑 Agent"""
        async for sse in self._stream(thread_id, message, decision):
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
                messages.append(ChatMessageResponse(
                    role=role,
                    content=message.text,
                    additional_info=message.additional_kwargs.get("additional_info"),
                ))

        return ChatHistoryResponse(
            thread_id=thread_id,
            messages=messages,
            # 会话停在「等确认」时，刷新页面也要能把卡片还回来
            interrupt=_pending_interrupt(state_snapshot),
        )

    async def delete_history(self, thread_id: UUID) -> None:
        """会话被删除时清掉对应的 checkpoint（MIGRATION 设计点 ②）"""
        checkpointer = self.agent.checkpointer
        if checkpointer is None:
            return
        await checkpointer.adelete_thread(str(thread_id))

    async def _stream(
        self,
        thread_id: UUID,
        message: str | None,
        decision: dict[str, Any] | None,
    ):
        """真正跑 Agent 的地方"""
        config = {"configurable": {"thread_id": thread_id}}

        reset_citations()
        reset_plan_products()

        # 带 decision = 用户在确认卡片上点了按钮，从断点恢复；否则是新的一轮输入
        _input = (
            Command(resume=decision)
            if decision
            else {"messages": [HumanMessage(content=message or "")]}
        )

        try:
            stream = await self.agent.astream_events(_input, config, version="v3")

            async for event in stream:
                method = event["method"]
                if method == "messages":
                    data = event["params"]["data"][0]
                    if isinstance(data, AIMessage):
                        # 结构化输出（意图识别用的 with_structured_output）解析出的
                        # 消息带 parsed 字段，它是给程序看的，绝不能出现在用户气泡里
                        if data.additional_kwargs.get("parsed") is not None:
                            continue
                        yield ServerSentEvent(data=data.text, event="message")
                    elif data.get("delta") and data["delta"].get("text"):
                        yield ServerSentEvent(data=data["delta"].get("text"), event="message")
        except Exception as exc:
            # 主备模型都失败了（或图本身抛错）：给前端一条明确的提示，
            # 而不是让连接直接断掉、气泡一直空着
            logger.error("Agent 执行失败", reason=str(exc))
            yield ServerSentEvent(
                data="模型服务暂时不可用，正在为您转接人工客服，请稍后再试。",
                event="error",
            )
            return
        finally:
            sources = current_citations()
            clear_citations()
            clear_plan_products()

        if sources:
            payload = {"type": "clause_sources", "sources": sources}
            yield ServerSentEvent(data=payload, event="additional_info")
            await self._persist_citations(thread_id, payload)

        # 流正常结束时，图可能停在等待确认的节点上，把 interrupt 推给前端
        snapshot = await self.agent.aget_state(config)
        pending = _pending_interrupt(snapshot)
        if pending is not None:
            yield ServerSentEvent(data=pending, event="interrupt")

        yield ServerSentEvent(data="END", event="done")

    async def _persist_citations(self, thread_id: UUID, payload: dict) -> None:
        """把引用来源写回本轮最后一条 AI 回复。

        不能依赖流事件里的 message id：推荐路径走 create_agent 子图，
        流出来的基本是 delta 字典，拿不到完整的 AIMessage。
        """
        config = {"configurable": {"thread_id": str(thread_id)}}
        snapshot = await self.agent.aget_state(config)
        messages = snapshot.values.get("messages", [])

        last_human_index = max(
            (index for index, msg in enumerate(messages) if isinstance(msg, HumanMessage)),
            default=-1,
        )
        for message in reversed(messages[last_human_index + 1:]):
            if isinstance(message, AIMessage) and message.text:
                updated = message.model_copy(update={
                    "additional_kwargs": {
                        **message.additional_kwargs,
                        "additional_info": payload,
                    },
                })
                await self.agent.aupdate_state(config, {"messages": [updated]})
                return


def _pending_interrupt(snapshot) -> Any | None:
    """取出当前挂起的 interrupt 值；没有则返回 None。

    LangGraph 把中断信息挂在 snapshot.tasks[*].interrupts 上。
    """
    for task in getattr(snapshot, "tasks", ()) or ():
        for item in getattr(task, "interrupts", ()) or ():
            return item.value
    return None