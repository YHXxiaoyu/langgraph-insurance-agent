"""本轮推荐出来的候选产品，用于「方案确认」卡片。

和 citations 一样：工具在子图里执行，没法直接写父图状态，
所以用 ContextVar 存一个可变列表，父任务的 prepare_plan 节点读它。
"""

from contextvars import ContextVar
from typing import Any

_candidates: ContextVar[list[dict[str, Any]] | None] = ContextVar("candidates", default=None)


def reset() -> None:
    """每轮对话开始时清空"""
    _candidates.set([])


def clear() -> None:
    """一轮结束后释放"""
    _candidates.set(None)


def current() -> list[dict[str, Any]]:
    return _candidates.get() or []


def record(products: list[dict[str, Any]]) -> None:
    """登记工具返回的候选产品（Java 内部接口的原样数据）"""
    store = _candidates.get()
    if store is None:
        store = []
        _candidates.set(store)

    for product in products:
        product_id = product.get("id")
        # 同一个产品可能被多次查询命中，按 id 去重
        if any(str(item["product_id"]) == str(product_id) for item in store):
            continue
        store.append({
            "product_id": product_id,
            "name": product.get("name"),
            "category": product.get("category"),
            "annual_premium_budget": product.get("min_premium"),
            "recommendation_reason": product.get("description") or "",
            "status": "uninsured",
        })


def build_interrupt_payload() -> dict[str, Any] | None:
    """整理成前端 PlanInterruptCard 认识的形状；没有候选产品返回 None"""
    items = current()
    if not items:
        return None

    return {
        "plan": {
            "title": "为你整理的推荐方案",
            "summary": f"共 {len(items)} 款产品，确认后可以继续办理投保。",
            "items": items,
        },
        "confirm_info": "请确认这份推荐方案；不合适可以继续调整。",
        "action": ["approve", "reject"],
    }