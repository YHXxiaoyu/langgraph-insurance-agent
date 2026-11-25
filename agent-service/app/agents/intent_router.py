import re
from typing import Literal
from app.agents.models import build_fallback_model, build_primary_model
from langchain_core.messages import SystemMessage, HumanMessage
from app.core.logging import get_logger
from app.agents.schemas import RouteResult
logger = get_logger(__name__)
SYSTEM_PROMPT = """
你是保险商城智能客服的意图识别节点，请判断用户消息应该交给哪个工作流。

intent只能从以下选项中选择：
- recommendation_plan：保险产品咨询、产品条款、保险推荐、方案管理和投保咨询
- claim：报案、理赔责任、理赔材料、理赔流程和理赔进度
- human_handoff：投诉、高风险问题或用户明确要求人工服务
- chitchat：与保险业务无关的普通聊天
- fallback：无法判断用户意图
""".strip()

class IntentRouter:
    def __init__(self):
        # 主模型 + 备用模型各自绑定结构化输出
        # 这里不用 with_fallbacks：它内部重试时会另起一条流式链路，
        # 备用模型吐出的整段 {"intent": ...} 会绕过节点过滤，直接出现在用户气泡里。
        # 自己 try/except 切换，两次调用都在同一个普通 invoke 里，不会漏进流。
        self.primary = build_primary_model(streaming=False).with_structured_output(RouteResult)
        fallback = build_fallback_model(streaming=False)
        self.fallback = (
            fallback.with_structured_output(RouteResult) if fallback is not None else None
        )

    def route(
            self,
            query: str, # 用户输入的查询
            context: str  # 历史上下文信息（上一轮工作流的名字，上一轮AI回复的消息）
    )-> RouteResult:
        try:
        #1.寒暄识别（基于规则，正则表达式匹配）
            intent = match_social_intent(query)
            if intent:
                #2.如果是寒暄，直接返回固定回复
                return response_by_social_intent(intent)

            #2.如果不是寒暄，调用LLM模型进行意图识别
            prompt = [
                SystemMessage(content=SYSTEM_PROMPT),
                HumanMessage(content=f"历史上下文：{context}\n用户消息：{query}")
            ]
            try:
                result = self.primary.invoke(prompt)
            except Exception as exc:
                # 主模型超时/报错：换备用模型再试一次
                if self.fallback is None:
                    raise
                logger.warning("主模型调用失败，切换备用模型", reason=str(exc))
                result = self.fallback.invoke(prompt)
            # 必须显式返回：漏掉这行 route() 返回 None，调用方取 .intent 直接崩
            return result

        except Exception as exc:
            # 主备模型都不可用：转人工，不让用户对着空白气泡干等
            logger.error("意图识别失败，转人工", reason=str(exc))
            return RouteResult(intent="human_handoff", reason="模型服务不可用，转人工")


# 基于规则的识别是否为寒暄

# 寒暄类型枚举
SocialIntent = Literal["greeting", "thanks", "goodbye"]
# 寒暄的匹配规则
SOCIAL_PATTERNS = {
    "greeting": (
        r"(你|您)?好(呀|啊|哦)?",
        r"嗨",
        r"哈喽",
        r"hello",
        r"hi",
        r"在吗",
    ),
    "thanks": (
        r"谢谢(你|您)?",
        r"多谢(你|您)?",
        r"感谢(你|您)?",
        r"辛苦了",
    ),
    "goodbye": (
        r"再见",
        r"拜拜",
        r"先这样",
        r"没事了",
    ),
}
# 寒暄的固定回复
SOCIAL_RESPONSES = {
    "greeting": "您好，我是您的保险顾问，可以帮您咨询产品、推荐方案或办理理赔。",
    "thanks": "不客气，后续有保险问题可以随时问我。",
    "goodbye": "好的，后续需要帮助时随时联系我。",
}

def normalize_message(message: str) -> str:
    """格式化消息，去掉无效字符"""

    return re.sub(r"[\s，。！？、,.!?~～]", "", message).lower()


def match_social_intent(message: str) -> SocialIntent | None:
    """基于规则匹配的路由"""

    normalized = normalize_message(message)
    for intent, patterns in SOCIAL_PATTERNS.items():
        if any(re.fullmatch(pattern, normalized) for pattern in patterns):
            return intent
    return None

def response_by_social_intent(intent: SocialIntent):
    """根据规则识别出的意图返回固定回复"""

    return SOCIAL_RESPONSES.get(intent)

#创建IntentRouter实例
router = IntentRouter()