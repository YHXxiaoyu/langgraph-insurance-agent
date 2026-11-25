from langchain.chat_models import init_chat_model
from langchain_core.messages import AIMessage, SystemMessage
from langgraph.graph import StateGraph
from app.agents.models import build_chat_model_with_fallback
from app.core.logging import get_logger
from app.agents.schemas import InsuranceAgentState
from app.core.config import settings

logger = get_logger(__name__)

#1.定义State
class ChitchatState(InsuranceAgentState):
    chitchat_count: int #记录闲聊次数

SYSTEM_MESSAGE = """
你是保险智能客服，负责处理与保险业务无关的普通聊天。

要求：
1. 回复友好、自然，最多两句话。
2. 不假装有个人经历、情感、实时信息或外部能力。
3. 不回答医疗、法律、投资、政治等高风险专业建议。
4. 每次回复都自然引导用户回到保险产品、投保或理赔业务。
5. 超出能力范围时，如实说明无法提供。
""".strip()

MAX_CHITCHAT_COUNT = 3 #最大闲聊次数
#2.定义工作流
class ChitchatWorkflow:
    def __init__(self):
        # 主模型失败自动切备用（见 app/agents/models.py）
        self.model = build_chat_model_with_fallback()

    def chitchat(self, state: ChitchatState):
        """闲聊工作流"""
        #获取闲聊次数
        count = state.get("chitchat_count", 0)

        #取出上一轮的工作流，如果上一轮不是闲聊工作流，则重置闲聊次数
        if state.get("previous_workflow") != "chitchat":
            count = 0

        #判断是否超过闲聊次数限制
        if count >= MAX_CHITCHAT_COUNT:
            #超过闲聊次数限制，返回提示信息
            logger.info("闲聊次数已达上限")
            return {"messages": [AIMessage("我主要协助处理保险产品、投保和理赔咨询，请告诉我想办理或了解的事项。")]}

        #没超过闲聊次数限制，继续闲聊
        response = self.model.invoke([
            SystemMessage(SYSTEM_MESSAGE),
            *state['messages']
        ])

        #返回结果，更新Messages，更新闲聊次数
        return {
            "messages": [response],
            "chitchat_count": count+1
        }

    def build_chitchat_graph(self):
        """构建闲聊工作流Graph"""
        #创建builder
        builder = StateGraph(ChitchatState)
        #添加节点
        builder.add_node("chitchat", self.chitchat)
        #添加边
        builder.set_entry_point("chitchat") #START->chitchat
        builder.set_finish_point("chitchat") #chitchat->END
        #编译
        return builder.compile(checkpointer=True)

def init_chitchat_agent():
    return ChitchatWorkflow().build_chitchat_graph()