from decimal import Decimal

from langchain.tools import tool

from app.core.logging import get_logger
from app.infra.java_client import JavaServiceError, call_java
from app.rag.models import ParentChunk

logger = get_logger(__name__)


@tool
async def query_candidate_products(
    categories: list[str],
    premium_min: Decimal | None = None,
    limit_per_category: int = 5
):
    """
    根据险种和保费条件查询可用于推荐的候选保险产品。当用户咨询具体保险产品或需要保险产品推荐时使用。

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
        return await call_java("POST", "/internal/v1/products/candidates", payload)
    except JavaServiceError as exc:
        # 3.失败时给模型一个可读的结果，让它能自然地告诉用户"暂时查不了"
        logger.error("查询候选产品失败", reason=str(exc))
        return {"error": "产品服务暂时不可用，请稍后再试"}

@tool
async def retrieve_insurance_clause(
    query: str,
    k: int,
    product_id: int
):
    """查询指定保险产品的条款内容。

    Args:
        query: 需要从保险条款中查询的问题。
        k: 返回的文档的数量
        product_id: 保险产品ID。
    """

    # 1.导入Retriever
    from app.rag import retriever

    # 2.检索
    chunks: list[ParentChunk] = await retriever.retrieve(query, k, product_id)

    # 3.把查询到的ParentChunk变成字符串
    results = [
        f"section_path: {chunk.section_path}\n content:{chunk.content}"
        for chunk in chunks
    ]

    return results
