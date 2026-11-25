"""本轮推荐出来的候选产品 + 已落库的方案。

和 citations 一样：工具和确认节点都在图内部执行，没法直接把结果交给
最外层的 SSE 推送，所以用 ContextVar 暂存，ChatService 在流结束时读取。
"""

from contextvars import ContextVar
from decimal import Decimal, InvalidOperation
from typing import Any

_candidates: ContextVar[list[dict[str, Any]] | None] = ContextVar("candidates", default=None)

# 用户确认后，Java 返回的 PlanVO 原样暂存在这里。
# 用 [None] 这种可变容器而不是直接存 dict：LangGraph 的节点跑在子任务里，
# 子任务对 ContextVar 的 set 不会传回父上下文，只有"改共享对象"父上下文才看得到，
# 这和 citations 往同一个 list 里 append 是同一个道理。
_saved_plan: ContextVar[list[dict[str, Any] | None]] = ContextVar("saved_plan", default=[None])


def reset() -> None:
    """每轮对话开始时清空"""
    _candidates.set([])
    # 换一个全新的容器：之后子任务继承到的就是这个对象
    _saved_plan.set([None])


def clear() -> None:
    """一轮结束后释放"""
    _candidates.set(None)
    _saved_plan.set([None])


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


def build_create_payload(payload: dict[str, Any], user_id: int | None) -> dict[str, Any] | None:
    """把 interrupt 里的方案转成 Java 内部接口的入参。

    字段名必须和 Java 的 CreatePlanDTO 对齐（驼峰）；金额统一转成 Decimal
    再转 float，避免 285.3 这种小数直接做浮点累加产生 0.0000001 的尾巴。
    """
    plan = (payload or {}).get("plan") or {}
    items = plan.get("items") or []
    if not user_id or not items:
        return None

    dto_items: list[dict[str, Any]] = []
    total = Decimal("0")
    for index, item in enumerate(items, start=1):
        amount: Decimal | None = None
        raw_budget = item.get("annual_premium_budget")
        if raw_budget is not None:
            try:
                amount = Decimal(str(raw_budget))
                total += amount
            except InvalidOperation:
                amount = None

        dto_items.append({
            "productId": item.get("product_id"),
            "priority": index,
            "recommendationReason": item.get("recommendation_reason") or None,
            "annualPremiumBudget": float(amount) if amount is not None else None,
        })

    return {
        "userId": user_id,
        "planName": plan.get("title") or "推荐方案",
        "summary": plan.get("summary"),
        "annualPremiumBudget": float(total) if total > 0 else None,
        "insuredProfile": {},
        "items": dto_items,
    }


def record_saved(saved: dict[str, Any]) -> None:
    """登记 Java 落库返回的方案（带 plan_id、每条明细的 id）"""
    _saved_plan.get()[0] = saved


def current_saved() -> dict[str, Any] | None:
    return _saved_plan.get()[0]


def build_saved_payload(saved: dict[str, Any]) -> dict[str, Any]:
    """PlanVO → 前端 additional_info 里的 plan_items。

    前端 collectPlanIds 会从这些字段里收集 plan_id，再回头调
    GET /api/v1/insurance-plans/{id} 拉明细状态——「去投保」能不能点，
    就取决于这里有没有带上 plan_id 和明细 id。
    """
    items = [
        {
            "id": item.get("id"),
            "plan_id": item.get("plan_id"),
            "product_id": item.get("product_id"),
            "name": item.get("product_name"),
            "category": item.get("category"),
            "annual_premium_budget": item.get("annual_premium_budget"),
            "recommendation_reason": item.get("recommendation_reason"),
            "status": item.get("status"),
        }
        for item in (saved.get("items") or [])
    ]
    return {"plan_items": items}