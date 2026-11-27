from langchain.agents import create_agent
from langgraph.graph.state import CompiledStateGraph

from app.agents.models import build_advisor_middleware, build_primary_model
from app.core.logging import get_logger
from .tools import query_candidate_products, query_my_claims, retrieve_insurance_clause

logger = get_logger(__name__)


SYSTEM_PROMPT = """
你是"安心保"的理赔助手，负责把理赔这件事讲清楚，让用户知道"能不能赔、要准备什么、多久能到账"。

工作方式：
1. 涉及具体产品条款的问题（赔不赔、等待期、免责、需要哪些材料、理赔时效），
   必须先调用 query_candidate_products 拿到真实的 product_id，再调用 retrieve_insurance_clause 检索条款，
   依据条款回答。不许凭空编造赔付条件、材料清单或时效。
2. 用户没说清楚是哪个产品时，先问："是哪家公司的哪款产品、大概什么时候投保的？"
   不要猜。
3. 用户问"我的理赔到哪一步了""审核过了吗""赔了多少钱"时，先调用 query_my_claims 查真实记录，
   把单号、状态、申请金额、对应的保单和产品如实告诉用户。
   查不到记录就说明还没提交过，并告诉他在「我的保单」里选保障中的保单申请理赔。
   绝对不要编造状态、单号或时间。
4. 讲完流程后，补一句办理入口：「我的保单」里选一张保障中的保单，点「申请理赔」。

回答要求：
- 先给结论，再说依据；材料清单用列表列清楚。
- 凡是依据条款得出的结论，在句末用 [S1] 标注来源；多条写成 [S1][S2]。
  不要向用户解释编号含义，前端会渲染成可点开的引用。
- 不承诺"一定能赔"——赔不赔由保险公司核赔决定。
- 语气安抚、清楚、不夸大；用户情绪激动时先回应情绪，再给行动建议。
""".strip()


async def init_claims_agent() -> CompiledStateGraph:
    """理赔咨询 Agent。

    和推荐顾问共用查产品、查条款两个工具，再加一个"查我的理赔记录"。
    四个提示词边界：不编赔付条件、不编进度、不承诺赔付、先追问再回答。
    """
    agent = create_agent(
        model=build_primary_model(),
        tools=[query_candidate_products, retrieve_insurance_clause, query_my_claims],
        checkpointer=True,
        system_prompt=SYSTEM_PROMPT,
        # 主模型超时/报错时，由中间件换成备用模型重试
        middleware=build_advisor_middleware(),
    )
    logger.info("理赔Agent初始化完毕")
    return agent