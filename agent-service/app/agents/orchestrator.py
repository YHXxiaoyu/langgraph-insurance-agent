from langchain_core.messages import AIMessage, BaseMessage
from langgraph.constants import START, END
from langgraph.graph import StateGraph

from langgraph.types import Command, interrupt

from app.agents.plan import build_interrupt_payload
from app.agents.intent_router import router
from .schemas import InsuranceAgentState, Intent
from .chitchat import init_chitchat_agent
from .insurance_advisor import init_insurance_advisor
#1.定义节点

#1.1意图识别节点
def route_node(state: InsuranceAgentState)-> Command[Intent]:
    """路由节点"""
    #获取上一轮的路由结果
    previous_workflow = state.get("active_workflow","")
    #获取用户输入的消息
    messages = state['messages']
    previous_ai_message = find_previous_ai_message(messages)
    context = f"上一轮工作流：{previous_workflow}\n上一轮AI回复：{previous_ai_message}"
    #调用路由工具，完成意图识别
    route_result = router.route(messages[-1].text,context)  # 这里可以根据实际情况进行意图识别

    if isinstance(route_result,str):
        #当前识别出的是寒暄意图，直接返回固定回复
        return Command(
            update={
                "previous_workflow": previous_workflow,
                "active_workflow": "chitchat",
                "messages":[AIMessage(route_result)]
            },
            goto=END
        )

    return Command(
        update={
            "previous_workflow": previous_workflow,
            "active_workflow": route_result.intent
        },
        goto=route_result.intent
    )

def find_previous_ai_message(messages: list[BaseMessage]) -> str:
    """查找本轮用户消息之前最近一条可展示的AI回复"""

    for message in reversed(messages[:-1]):
        # 跳过非AIMessage
        if not isinstance(message, AIMessage):
            continue
        # 跳过工具消息
        if message.tool_calls:
            continue
        # 如果是正常AIMessage，直接返回
        if message.text:
            return message.text
    # 如果是空消息
    return ""
#1.2保险方案推荐咨询节点
# def recommendation_node(state: InsuranceAgentState):
#     """保险方案推荐咨询节点"""
#     return {"message":[AIMessage("根据您的需求，我为您推荐以下保险方案：...")]}


# 1.2 方案确认前置：把本轮候选产品整理成方案，写进状态
def prepare_plan_node(state: InsuranceAgentState):
    """整理推荐方案。

    不在这里 interrupt：中断后节点会从头重跑，判断依据必须放在状态里，
    否则重跑时 ContextVar 已清空，会误判成"没有方案"。
    """
    return {"pending_plan": build_interrupt_payload()}


# 1.3 方案确认：暂停等人确认
def confirm_plan_node(state: InsuranceAgentState):
    """推荐方案的⼈工确认。

    interrupt() 会暂停整个图；前端收到 event: interrupt 后展示卡片。
    用户点按钮后，Java 把 decision 带回来，图从这个节点恢复，interrupt() 直接返回 decision。
    注意：interrupt() 之前的代码会执行两次（首次与恢复），必须没有副作用。
    """
    payload = state.get("pending_plan")
    if not payload:
        # 这一轮没有推荐产品，不打扰用户
        return {}

    decision = interrupt(payload)
    action = (decision or {}).get("action")

    if action == "approve":
        return {"messages": [AIMessage("方案已确认。后续可以在「我的保单」里查看办理进度。")]}

    reason = (decision or {}).get("reject_reason")
    note = f"（原因：{reason}）" if reason else ""
    return {"messages": [AIMessage(f"好的，方案先不保存{note}。可以告诉我预算或偏好，我再帮你调整。")]}

#1.3理赔咨询节点
def claims_node(state: InsuranceAgentState):
    """理赔咨询节点"""
    return {"messages":[AIMessage("根据您的需求，我为您处理以下理赔事项：...")]}

#1.4闲聊节点
# def chitchat_node(state: InsuranceAgentState):
#     """闲聊节点"""
#     return {"message":[AIMessage("您好，有什么我可以帮您的吗？")]}

#1.5人工客服节点
def human_handoff_node(state: InsuranceAgentState):
    """人工客服节点"""
    return {"messages":[AIMessage("您好，正在为您转接人工客服...")]}

#1.6fallback
def fallback_node(state: InsuranceAgentState):
    """fallback节点"""
    return {"messages":[AIMessage("抱歉，我无法处理您的请求。")]}

#2.创建Graph，用Edge连接Node
async def init_insurance_agent(checkpointer):
    """初始化保险顾问Graph"""
    builder = StateGraph(InsuranceAgentState)

    # 添加节点
    builder.add_node("route", route_node)
    builder.add_node("recommendation_plan", await init_insurance_advisor())
    builder.add_node("prepare_plan", prepare_plan_node)
    builder.add_node("confirm_plan", confirm_plan_node)
    builder.add_node("claim", claims_node)
    builder.add_node("chitchat", init_chitchat_agent())
    builder.add_node("human_handoff", human_handoff_node)
    builder.add_node("fallback", fallback_node)

    # 添加边
    builder.add_edge(START, "route")
    # 推荐分支多两步：整理方案 → 等确认
    builder.add_edge("recommendation_plan", "prepare_plan")
    builder.add_edge("prepare_plan", "confirm_plan")
    builder.add_edge("confirm_plan", END)
    builder.add_edge("claim", END)
    builder.add_edge("chitchat", END)
    builder.add_edge("human_handoff", END)
    builder.add_edge("fallback", END)

    #编译Graph
    return builder.compile(checkpointer=checkpointer)

