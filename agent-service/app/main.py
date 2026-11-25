from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI, Request
from starlette.responses import JSONResponse
from app.core.trace import TraceIdMiddleware
from app.core.config import settings
from app.core.exceptions import ApplicationError
from app.core.logging import configure_logging, get_logger

# 配置日志
configure_logging(settings.logging.level)
# 获取日志记录器
logger = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("应用启动中")
    from app.infra.database import check_database, close_database
    from app.infra.checkpointer import init_checkpointer, close_checkpointer
    from app.infra.java_client import close_client
    from app.agents.orchestrator import init_insurance_agent
    from app.rag import close_rag
    try:
        #初始化数据库连接池
        await check_database()
        #初始化checkpointer
        checkpointer = await init_checkpointer()
        #初始化Agent,并将其保存在FastAPI的全局state中，一切进入FastAPI的请求的将共享state
        app.state.agent = await init_insurance_agent(checkpointer)
        yield
    finally:
        logger.info("应用关闭中")
        close_rag()
        #释放调用 Java 的 HTTP 连接池
        await close_client()
        await close_database()
        await close_checkpointer()


# 创建 FastAPI 应用
app = FastAPI(
    title=settings.app.name,
    debug=settings.app.debug,
    lifespan=lifespan
)

# 阶段 5 之后 Python 只暴露内部接口，公开的 /api/v1/** 全部交给 Java
from app.modules.chat.internal_router import router as internal_agent_router
app.include_router(internal_agent_router)
# 链路追踪：必须在最外层，后面的路由、异常处理才能拿到 trace_id
app.add_middleware(TraceIdMiddleware)

@app.get("/healthy", summary="健康检测接口")
async def healthy():
    logger.info("健康检测接口被调用")
    return {"status": "ok"}


# 统一处理业务异常
@app.exception_handler(ApplicationError)
def handle_exception(request: Request, exc: ApplicationError):
    logger.error(f"处理异常, 请求路径: {request.url}, 异常信息: {exc}")
    return JSONResponse(
        status_code=exc.status_code,
        content={
            "code": exc.code,
            "message": exc.message
        }
    )


if __name__ == "__main__":
    import sys
    uvicorn.run(
        "app.main:app",
        host=settings.app.host,
        port=settings.app.port,
        reload=False,
        loop="asyncio:SelectorEventLoop" if sys.platform == "win32" else "auto",
    )