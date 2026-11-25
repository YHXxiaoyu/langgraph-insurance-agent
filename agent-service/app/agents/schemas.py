from dataclasses import dataclass
from typing import Literal

from langgraph.graph import MessagesState
from pydantic import BaseModel, Field

# 路由意图常量
Intent = Literal[
    "chitchat",
    "recommendation_plan",
    "claim",
    "human_handoff",
    "fallback",
]


class RouteResult(BaseModel):
    """意图识别结果"""

    intent: Intent = Field(description="本轮消息应该进入的工作流")
    reason: str = Field(description="判断该意图的理由")


class InsuranceAgentState(MessagesState):
    """保险顾问主Graph状态"""

    previous_workflow: Intent
    active_workflow: Intent
    # 待确认的方案。放在 state 里而不是 ContextVar：
    # interrupt 恢复时节点会从头重跑，ContextVar 那时已经是空的
    pending_plan: dict | None
    # 本轮对话的归属用户。内部接口没有登录态，Java 把 userId 透传进来；
    # 方案落库、后续发保单都要用它
    user_id: int | None