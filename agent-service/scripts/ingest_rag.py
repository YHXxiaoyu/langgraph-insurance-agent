import asyncio
import sys
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

from langchain_milvus import BM25BuiltInFunction, Milvus
from app.core.config import settings
from app.core.logging import get_logger
from app.infra.database import close_database
from app.infra.java_client import call_java, close_client
from app.rag.pipeline import ProductRef, RAGPipeline
from langchain_community.embeddings import DashScopeEmbeddings
from dotenv import load_dotenv
load_dotenv()

logger = get_logger(__name__)


def create_vector_store() -> Milvus:
    embeddings = DashScopeEmbeddings(
        model="text-embedding-v4",
    )
    return Milvus(
        embedding_function=embeddings,
        collection_name="insurance_collection",
        builtin_function=BM25BuiltInFunction(
            analyzer_params={"type": "chinese"}
        ),
        vector_field=["dense", "sparse"],
        connection_args={"uri": settings.rag.milvus_url},
        auto_id=True,
    )


async def find_all_products() -> list[ProductRef]:
    """产品清单改由 Java 内部接口提供，Python 不再查业务库"""
    rows = await call_java("GET", "/internal/v1/products")
    return [
        ProductRef(id=row["id"], name=row["name"], clause_name=row["clauseName"])
        for row in rows
    ]


async def main() -> None:
    vector_store = create_vector_store()
    pipeline = RAGPipeline(vector_store)

    try:
        products = await find_all_products()
        logger.info("开始构建RAG知识库", product_count=len(products))

        for index, product in enumerate(products, start=1):
            logger.info(
                "开始处理保险产品",
                progress=f"{index}/{len(products)}",
                product_id=product.id,
                product_name=product.name,
            )
            await pipeline.ingest(product)

        logger.info("RAG知识库构建完成", product_count=len(products))
    finally:
        pipeline.close()
        vector_store.client.close()
        await close_client()
        await close_database()


if __name__ == "__main__":
    asyncio.run(main())