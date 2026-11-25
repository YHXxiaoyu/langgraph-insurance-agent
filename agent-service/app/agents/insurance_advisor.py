from langchain.agents import create_agent
from langgraph.graph.state import CompiledStateGraph

from app.agents.models import build_advisor_middleware, build_primary_model
from app.core.logging import get_logger
from .tools import query_candidate_products, retrieve_insurance_clause

logger = get_logger(__name__)


SYSTEM_PROMPT = """
你是“安心保”的智能保险顾问。
你需要使用专业、准确、容易理解的语言回答用户的保险问题。
当信息不足时，应先向用户追问，不要编造保险产品或保障内容。
当用户查询保险条款问题时，必须调用工具查询条款，然后根据条款内容回答，不能自己编造。

调用 retrieve_insurance_clause 前，必须先调用 query_candidate_products 拿到真实的 product_id，
不要凭空填写（填 0 会查不到任何条款）。

query 参数必须是一句能独立看懂的完整问题，包含产品名称和具体事项。
多轮对话中用户只说了半句（例如「那退保呢」「要等多久」）时，
必须结合上文补全（例如「复星联合医联有盟重大疾病保险的犹豫期是多久」），
不要直接把半句话当作 query。

调用 retrieve_insurance_clause 后，返回的每条结果都带 source_id（形如 S1、S2）。
凡是依据某条条款得出的结论，必须在句子末尾用 [S1] 标注来源；引用多条时写成 [S1][S2]。
不要向用户解释编号的含义，前端会把编号渲染成可点开的条款引用。
""".strip()


async def init_insurance_advisor() -> CompiledStateGraph:
    agent = create_agent(
        model=build_primary_model(),
        tools=[query_candidate_products, retrieve_insurance_clause],
        checkpointer=True,
        system_prompt=SYSTEM_PROMPT,
        # 主模型超时/报错时，由中间件换成备用模型重试
        middleware=build_advisor_middleware(),
    )
    logger.info("保险公司Agent初始化完毕")
    return agent