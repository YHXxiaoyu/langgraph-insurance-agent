"""理赔材料清单生成。

把"这个产品理赔要准备什么"从写死的列表变成按条款生成：
先检索条款里"保险金申请 / 索赔材料"相关段落，再让模型整理成清单。
结果按产品缓存 6 小时 —— 条款不会天天变，没必要每开一次页面就打一次模型。
"""

import time

from langchain.messages import HumanMessage, SystemMessage
from pydantic import BaseModel, Field

from app.agents.models import build_fallback_model, build_primary_model
from app.core.logging import get_logger

logger = get_logger(__name__)

#: 缓存有效期。条款更新后最多 6 小时内还是旧清单，可以接受
CACHE_TTL_SECONDS = 6 * 3600

#: product_id -> (过期时间戳, 结果)
_cache: dict[int, tuple[float, dict]] = {}

SYSTEM_PROMPT = """你是保险理赔材料整理助手。
下面会给你某款保险产品条款中与"保险金申请""索赔材料"相关的段落，
请只依据这些条款，整理用户申请理赔时需要准备的材料清单。

要求：
1. 每条用简短的名词短语，例如"住院病历、出院小结"，不要写成长句。
2. 最多 8 条，按重要程度排序；条款没提到的不要编。
3. 条款里没有关于材料的规定时，items 返回空数组，并在 note 里说明。
4. note 用一句话说明依据（例如"依据条款第七章保险金申请"），不超过 40 字。
"""


class ClaimMaterials(BaseModel):
    """模型输出的结构化结果"""

    items: list[str] = Field(default_factory=list, description="需要准备的材料，每条一个短语")
    note: str = Field(default="", description="一句话说明清单依据")


async def generate_claim_materials(product_id: int) -> dict:
    """按产品生成材料清单。

    返回结构（与 Java 侧 ClaimMaterialsVO 对齐）：
    {product_id, items: [...], note, generated}
    generated=False 表示这次没生成出来（没有条款 / 模型不可用），
    调用方应当回落到按险种的静态清单，而不是让用户看到空列表。
    """
    cached = _cache.get(product_id)
    if cached and cached[0] > time.time():
        return cached[1]

    # 延迟导入：app.rag 会连 Milvus，放模块顶部会让这个模块无法被单独测试
    from app.rag import retriever

    try:
        chunks = await retriever.retrieve(
            query="申请理赔需要准备哪些材料 保险金申请 索赔资料",
            k=6,
            product_id=product_id,
        )
    except Exception as exc:
        logger.error("理赔材料检索失败", product_id=product_id, reason=str(exc))
        return _not_generated(product_id, "条款检索暂时不可用")

    if not chunks:
        logger.warning("条款里没有找到材料相关规定", product_id=product_id)
        return _not_generated(product_id, "条款里没有找到材料相关规定")

    context = "\n\n".join(f"[S{i + 1}] {chunk.content}" for i, chunk in enumerate(chunks))
    prompt = [
        SystemMessage(content=SYSTEM_PROMPT),
        HumanMessage(content=f"条款原文：\n{context}"),
    ]

    try:
        llm = build_primary_model(streaming=False).with_structured_output(ClaimMaterials)
        result = await llm.ainvoke(prompt)
    except Exception as exc:
        logger.warning("材料清单生成失败，切换备用模型", product_id=product_id, reason=str(exc))
        fallback = build_fallback_model(streaming=False)
        if fallback is None:
            return _not_generated(product_id, "模型服务暂时不可用")
        try:
            result = await fallback.with_structured_output(ClaimMaterials).ainvoke(prompt)
        except Exception as exc2:
            logger.error("备用模型也失败", product_id=product_id, reason=str(exc2))
            return _not_generated(product_id, "模型服务暂时不可用")

    items = [item.strip() for item in result.items if item and item.strip()][:8]
    if not items:
        return _not_generated(product_id, result.note or "条款里没有找到材料相关规定")

    payload = {
        "product_id": product_id,
        "items": items,
        "note": result.note or "依据条款整理",
        "generated": True,
    }
    _cache[product_id] = (time.time() + CACHE_TTL_SECONDS, payload)
    logger.info("理赔材料清单已生成", product_id=product_id, count=len(items))
    return payload


def _not_generated(product_id: int, note: str) -> dict:
    """失败结果不写缓存：下次请求还应该重试一次"""
    return {"product_id": product_id, "items": [], "note": note, "generated": False}