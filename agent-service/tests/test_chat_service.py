"""ChatService 单元测试：用假 Agent 替代 LangGraph，不连数据库、不调模型。

覆盖三块最容易出回归的逻辑：
1. 历史消息的过滤（工具消息不入库、空回复不返回）
2. 流事件 → SSE 的映射（文本增量、引用事件、确认中断）
3. 带 decision 的请求走 Command(resume=...)，而不是当成新消息
"""

from types import SimpleNamespace
from uuid import uuid4

from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langgraph.types import Command

from app.agents import citations
from app.modules.chat.service import ChatService


class FakeSnapshot:
    """够用的 StateSnapshot：只要 values 和 tasks"""

    def __init__(self, messages, tasks=()):
        self.values = {"messages": messages}
        self.tasks = list(tasks)


class FakeTask:
    def __init__(self, interrupt_value):
        self.interrupts = [SimpleNamespace(value=interrupt_value)]


class FakeAgent:
    def __init__(self, events=(), snapshot=None):
        self._events = list(events)
        self.snapshot = snapshot or FakeSnapshot([])
        self.received_input = None
        self.updates = []
        self.checkpointer = None

    async def astream_events(self, graph_input, config, version="v3"):
        self.received_input = graph_input

        async def gen():
            for event in self._events:
                yield event

        return gen()

    async def aget_state(self, config):
        return self.snapshot

    async def aupdate_state(self, config, values):
        self.updates.append(values)


async def collect(service: ChatService, thread_id, message=None, decision=None):
    return [sse async for sse in service._stream(thread_id, message, decision)]


# ---------------- 历史消息 ----------------

async def test_history_skips_tool_and_empty_messages():
    info = {"type": "clause_sources", "sources": [{"source_id": "S1"}]}
    snapshot = FakeSnapshot([
        HumanMessage(content="问"),
        AIMessage(content="答", additional_kwargs={"additional_info": info}),
        ToolMessage(content="工具输出", tool_call_id="call-1"),
        AIMessage(content=""),
    ])

    result = await ChatService(FakeAgent(snapshot=snapshot)).chat_history(uuid4())

    assert [m.role for m in result.messages] == ["user", "assistant"]
    assert result.messages[1].additional_info == info
    assert result.interrupt is None


async def test_history_returns_pending_interrupt():
    interrupt_value = {"plan": {"items": [1, 2]}, "confirm_info": "请确认"}
    snapshot = FakeSnapshot(
        [HumanMessage(content="问")],
        tasks=[FakeTask(interrupt_value)],
    )

    result = await ChatService(FakeAgent(snapshot=snapshot)).chat_history(uuid4())

    assert result.interrupt == interrupt_value


# ---------------- 流式输出 ----------------

async def test_stream_maps_text_deltas_and_ends_with_done():
    events = [
        {"method": "messages", "params": {"data": [AIMessage(content="你好")]}},
        {"method": "messages", "params": {"data": [{"delta": {"text": "世界"}}]}},
        # 没有 text 的 delta 不应产生事件
        {"method": "messages", "params": {"data": [{"delta": {}}]}},
        {"method": "values", "params": {}},
    ]

    out = await collect(ChatService(FakeAgent(events=events)), uuid4(), "hi")

    assert [s.data for s in out if s.event == "message"] == ["你好", "世界"]
    assert out[-1].event == "done"


class FakeChunk:
    """替身：citations.record 只读这几个属性"""

    clause_name = "测试条款.pdf"
    section_path = ["1 总则", "1.1 等待期"]
    content = "等待期为 90 天"


class CitationAgent(FakeAgent):
    """模拟工具在流式过程中登记了一条引用"""

    async def astream_events(self, graph_input, config, version="v3"):
        self.received_input = graph_input

        async def gen():
            yield {"method": "messages", "params": {"data": [AIMessage(content="回答")]}}
            citations.record([FakeChunk()])

        return gen()


async def test_stream_emits_citations_and_persists_them():
    snapshot = FakeSnapshot([HumanMessage(content="问"), AIMessage(content="回答")])
    agent = CitationAgent(snapshot=snapshot)

    out = await collect(ChatService(agent), uuid4(), "问")

    info = next(s for s in out if s.event == "additional_info").data
    assert info["type"] == "clause_sources"
    assert info["sources"][0]["source_id"] == "S1"
    assert info["sources"][0]["section_path"] == ["1 总则", "1.1 等待期"]

    # 写回 checkpoint：同一条消息的 additional_kwargs 里要有引用
    saved = agent.updates[0]["messages"][0]
    assert saved.additional_kwargs["additional_info"]["sources"][0]["source_id"] == "S1"


async def test_stream_emits_interrupt_when_graph_pauses():
    snapshot = FakeSnapshot(
        [HumanMessage(content="问")],
        tasks=[FakeTask({"plan": {"title": "方案"}})],
    )

    out = await collect(ChatService(FakeAgent(snapshot=snapshot)), uuid4(), "问")

    assert [s.data for s in out if s.event == "interrupt"] == [{"plan": {"title": "方案"}}]
    assert out[-1].event == "done"


# ---------------- 恢复 / 删除 ----------------

async def test_decision_resumes_instead_of_new_message():
    agent = FakeAgent(snapshot=FakeSnapshot([]))

    await collect(ChatService(agent), uuid4(), None, {"action": "approve"})

    assert isinstance(agent.received_input, Command)


async def test_new_message_is_plain_input():
    agent = FakeAgent(snapshot=FakeSnapshot([]))

    await collect(ChatService(agent), uuid4(), "你好", None)

    assert isinstance(agent.received_input, dict)
    assert agent.received_input["messages"][0].text == "你好"


async def test_delete_history_without_checkpointer_is_noop():
    # 没有 checkpointer 时不应该抛异常
    await ChatService(FakeAgent()).delete_history(uuid4())
