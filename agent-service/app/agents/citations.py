"""本轮对话检索到的条款来源。

工具在 Agent 子图里执行，没法直接写父图状态；这里用 ContextVar 存一个**可变列表**：
父任务创建列表对象，子任务按引用继承（contextvars 复制的是引用不是副本），
工具往里 append，流结束后父任务就能读到本轮的全部来源。
"""

from contextvars import ContextVar
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # 只为类型标注，避免 import 时把 app.rag 拉起来（会连 Milvus）
    from app.rag.models import ParentChunk

_citations: ContextVar[list[dict[str, Any]] | None] = ContextVar("citations", default=None)


def reset() -> None:
    """每轮对话开始时清空"""
    _citations.set([])


def clear() -> None:
    """一轮结束后释放，避免残留到下一次请求"""
    _citations.set(None)


def current() -> list[dict[str, Any]]:
    """本轮已经登记的全部来源"""
    return _citations.get() or []


def record(chunks: "list[ParentChunk]") -> list[dict[str, Any]]:
    """把本次检索到的父块登记为引用来源，返回带 source_id 的结构。

    source_id 用 S1、S2 这样的短编号而不是 UUID：前端靠它在回答文本里找标记，
    UUID 又长又难被模型准确复述。
    """
    store = _citations.get()
    if store is None:
        # 不在对话链路里（例如脚本直接调工具），照常返回，只是没人收集
        store = []
        _citations.set(store)

    start = len(store)
    sources = [_to_source(chunk, start + i + 1) for i, chunk in enumerate(chunks)]
    store.extend(sources)
    return sources


def _to_source(chunk: "ParentChunk", number: int) -> dict[str, Any]:
    path = list(chunk.section_path or [])
    return {
        "source_id": f"S{number}",
        "clause_name": chunk.clause_name,
        "document_name": chunk.clause_name,
        "section_path": path,
        "clause_no": _clause_no(path),
        "content": chunk.content,
        "score": None,
    }


def _clause_no(path: list[str]) -> str | None:
    """章节路径最后一级如果是「第X条 标题」，单独取出来给前端显示"""
    if not path:
        return None
    last = path[-1]
    return last if last.startswith("第") and "条" in last else None