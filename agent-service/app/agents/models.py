"""模型工厂与降级链。

三个用模型的地方（意图路由、闲聊、保险顾问）都从这里取模型，
避免每处各自 init_chat_model 时漏掉降级逻辑。

降级策略：主模型（DeepSeek）超时或报错 → 备用模型（DashScope 的 qwen）→ 都失败则转人工。
- 普通调用：Runnable 级别的 with_fallbacks 就够
- create_agent 子图：它只接受 BaseChatModel，所以在 middleware 的
  wrap_model_call 钩子里换模型重试（框架内部会重新给新模型绑定工具）
"""

from langchain.agents.middleware import AgentMiddleware
from langchain.chat_models import init_chat_model
from langchain_openai import ChatOpenAI

from app.core.config import settings
from app.core.logging import get_logger

logger = get_logger(__name__)


def build_primary_model(streaming: bool = True):
    """主模型。timeout 必须设：不设的话网络卡住会把整条链路拖死"""
    return init_chat_model(
        settings.llm.chat_model,
        api_key=settings.llm.api_key,
        timeout=settings.llm.timeout,
        max_retries=0,  # 不在主模型上重试，直接交给备用模型
        disable_streaming=not streaming,  # False 交给流式；意图识别这类不需要边生成边给用户看的场景要关掉
        extra_body={"thinking": {"type": "disabled"}},
    )


def build_fallback_model(streaming: bool = True):
    """备用模型：DashScope 的 OpenAI 兼容接口。没配 key 就返回 None，等于不降级"""
    if not settings.llm.fallback_api_key:
        return None
    return ChatOpenAI(
        model=settings.llm.fallback_model,
        api_key=settings.llm.fallback_api_key,
        base_url=settings.llm.fallback_base_url,
        timeout=settings.llm.timeout,
        disable_streaming=not streaming,
    )


def build_chat_model_with_fallback():
    """给普通节点用：返回 Runnable，主模型失败自动切备用"""
    primary = build_primary_model()
    fallback = build_fallback_model()
    if fallback is None:
        return primary
    return primary.with_fallbacks([fallback])


class FallbackModelMiddleware(AgentMiddleware):
    """create_agent 的降级中间件。

    主模型抛异常（含超时）时，把 request.model 换成备用模型再执行一次；
    备用也失败就让异常继续往上走，由 ChatService 兜底成 error 事件。
    """

    def __init__(self, fallback) -> None:
        self._fallback = fallback

    def wrap_model_call(self, request, handler):
        try:
            return handler(request)
        except Exception as exc:
            logger.warning("主模型调用失败，切换备用模型", reason=str(exc))
            return handler(request.override(model=self._fallback))

    async def awrap_model_call(self, request, handler):
        try:
            return await handler(request)
        except Exception as exc:
            logger.warning("主模型调用失败，切换备用模型", reason=str(exc))
            return await handler(request.override(model=self._fallback))


def build_advisor_middleware():
    """保险顾问子图用的中间件列表；没配备用模型就返回空列表"""
    fallback = build_fallback_model()
    return [FallbackModelMiddleware(fallback)] if fallback else []