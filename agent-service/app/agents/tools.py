import json
from decimal import Decimal

from langchain.tools import tool
from app.agents.session import current_user_id
from app.agents.citations import record as record_citations
from app.agents.plan import record as record_products
from app.core.logging import get_logger
from app.infra.java_client import JavaServiceError, call_java

logger = get_logger(__name__)


@tool
async def query_candidate_products(
    categories: list[str],
    premium_min: Decimal | None = None,
    limit_per_category: int = 5
):
    """根据险种和保费条件查询可用于推荐的候选保险产品。

     当用户咨询具体保险产品或需要保险产品推荐时使用。

     Args:
         categories: 产品分类列表，可选值为 medical、critical_illness、life、accident。
         premium_min: 最低保费上限，可选参数，只返回最低保费小于该值的产品。
         limit_per_category: 每个险种最多返回的产品数量，可选参数，默认5。
     """
    # 1.组装参数，字段名对应 Java 侧的 ProductCandidateQueryDTO
    payload = {
        "categories": categories,
        "premiumMin": str(premium_min) if premium_min is not None else None,
        "limitPerCategory": limit_per_category,
    }
    try:
        # 2.调 Java 内部接口，业务数据不再由 Python 自己查库
        data = await call_java("POST", "/internal/v1/products/candidates", payload)
    except JavaServiceError as exc:
        # 3.失败时给模型一个可读的结果，让它能自然地告诉用户"暂时查不了"
        logger.error("查询候选产品失败", reason=str(exc))
        return {"error": "产品服务暂时不可用，请稍后再试"}

    # 4.登记本轮推荐的候选产品，供「方案确认」卡片使用
    if isinstance(data, list):
        record_products(data)
    return data


@tool
async def retrieve_insurance_clause(
        query: str,
        k: int,
        product_id: int
):
    """查询指定保险产品的条款内容。

    Args:
        query: 需要从保险条款中查询的问题。
        k: 返回的条款数量。
        product_id: 保险产品ID。

    返回 JSON 数组，每条结果带 source_id（S1、S2…）。
    回答时必须依据返回内容，并在引用的句子末尾用 [S1] 标注来源编号。
    """
    # 模型偶尔会凭空给 product_id（实测出现过 0），那样检索必然为空，
    # 它却会拿着空结果继续编答案。这里直接返回提示，让它在下一轮先查产品。
    if not product_id or product_id <= 0:
        return json.dumps(
            {"error": "product_id 无效，请先调用 query_candidate_products 查询该产品的 id"},
            ensure_ascii=False,
        )
    # 导入RAG Retriever
    from app.rag import retriever

    # 检索。把 query 打进日志：多轮对话里模型是否已经把追问改写成完整问题，
    # 只能靠这行日志判断（评测脚本只能测"检索本身"）
    logger.info("条款检索", query=query, product_id=product_id, k=k)
    chunks = await retriever.retrieve(query=query, k=k, product_id=product_id)

    # 登记为本轮引用来源（前端「引用来源」列表就来自这里）
    sources = record_citations(chunks)

    # 只把模型用得上的字段给它：UUID、score 这类内部字段不进上下文，省 token
    return json.dumps(
        [
            {
                "source_id": source["source_id"],
                "section_path": source["section_path"],
                "content": source["content"],
            }
            for source in sources
        ],
        ensure_ascii=False,
    )

@tool
async def query_my_claims():
    """查询当前用户自己的理赔记录（理赔单号、状态、申请金额、对应保单与产品）。

    用户问"我的理赔到哪一步了""审核通过了吗""赔了多少钱"时使用。
    只能查当前登录用户自己的记录，不需要也不应该向用户索要身份信息。
    返回空列表表示该用户还没提交过理赔。
    """
    user_id = current_user_id()
    if not user_id:
        # 不在对话链路里（比如脚本直接调工具），如实返回而不是瞎查
        return {"error": "当前会话缺少用户信息，无法查询理赔记录；请让用户到「我的理赔」页面查看"}

    try:
        data = await call_java("GET", f"/internal/v1/claims?userId={user_id}")
    except JavaServiceError as exc:
        logger.error("查询理赔记录失败", reason=str(exc))
        return {"error": "理赔服务暂时不可用，请稍后再试"}

    return {"claims": data or []}