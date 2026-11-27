"""本轮对话的用户身份。

工具跑在 Agent 子图里，拿不到主图的 state（user_id 在父图状态里）。
和 citations 一样：父任务在每轮开始时 set，子任务按上下文副本继承，工具里直接读。
注意方向 —— 父 → 子可以，子 → 父不行（那是之前踩过的坑）。
"""

from contextvars import ContextVar

_user_id: ContextVar[int | None] = ContextVar("current_user_id", default=None)


def reset(user_id: int | None) -> None:
    """每轮对话开始时记下当前用户"""
    _user_id.set(user_id)


def current_user_id() -> int | None:
    """工具里取当前用户；不在对话链路里时为 None"""
    return _user_id.get()