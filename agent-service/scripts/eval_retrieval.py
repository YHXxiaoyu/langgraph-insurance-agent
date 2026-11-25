"""RAG 检索离线评测：算 recall@k 和 MRR。

评测对象是检索本身（ParentChunkRetriever），不经过 Agent 和生成模型，
所以每次运行只花 embedding + rerank 的钱。

用法（在 agent-service 目录下）：

    uv run python scripts/eval_retrieval.py                  # baseline：k=5, fetch_k=10, 开 rerank
    uv run python scripts/eval_retrieval.py --fetch-k 20     # 对照实验：扩大候选池
    uv run python scripts/eval_retrieval.py --no-rerank      # 对照实验：关掉重排
    uv run python scripts/eval_retrieval.py --limit 5        # 只跑前 5 条，快速试脚本

产出：控制台汇总 + evals/reports/ 下的 markdown 报告。
"""

import argparse
import asyncio
import json
import sys
from datetime import datetime
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

from dotenv import load_dotenv
from sqlalchemy import select

load_dotenv()

# Windows 控制台默认 GBK，项目日志里有 emoji，输出到控制台会直接抛
# UnicodeEncodeError。报告文件本身用 UTF-8，这里只修标准输出。
for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="replace")

from app.core.logging import get_logger
from app.infra.database import AsyncSessionFactory, close_database
from app.rag import retriever
from app.rag.models import ParentChunk

logger = get_logger(__name__)

EVAL_FILE = PROJECT_ROOT / "evals" / "rag_eval.jsonl"
REPORT_DIR = PROJECT_ROOT / "evals" / "reports"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="RAG 检索离线评测")
    parser.add_argument("--k", type=int, default=5, help="返回几个父块（默认 5）")
    parser.add_argument("--fetch-k", type=int, default=10, help="重排前召回多少条子块（默认 10）")
    parser.add_argument("--no-rerank", action="store_true", help="关闭 rerank，用于对照实验")
    parser.add_argument("--limit", type=int, default=0, help="只跑前 N 条（0 表示全部）")
    parser.add_argument(
        "--file", default=str(EVAL_FILE),
        help="评测集路径，默认单轮 rag_eval.jsonl；多轮集用 evals/rag_eval_multiturn.jsonl",
    )
    parser.add_argument(
        "--use-rewrite", action="store_true",
        help="多轮集专用：用 context + follow_up 拼成完整问题再检索（对照组）",
    )
    return parser.parse_args()


def load_samples(args: argparse.Namespace) -> list[dict]:
    with open(args.file, encoding="utf-8") as f:
        samples = [json.loads(line) for line in f if line.strip()]
    return samples[:args.limit] if args.limit else samples


def query_of(sample: dict, use_rewrite: bool) -> str:
    """取这次检索实际用的 query。

    - 单轮集（有 query 字段）：直接用
    - 多轮集：默认只拿 follow_up（模拟"用户追问原话直接检索"）
    - 多轮集 + --use-rewrite：把上文和追问拼起来，近似"改写后"的对照组
    """
    if "query" in sample:
        return sample["query"]
    if use_rewrite:
        return f"{sample['context']} {sample['follow_up']}"
    return sample["follow_up"]


async def load_chunk_id_map() -> dict[tuple[int, str], str]:
    """(product_id, 章节路径) -> 父块 id。

    评测集里存的是章节路径而不是 UUID，重新灌库后依然能对上；
    前提是同一产品内章节路径唯一（已用 SQL 校验过 60/60）。
    """
    async with AsyncSessionFactory() as session:
        chunks = (await session.scalars(select(ParentChunk))).all()
    return {
        (chunk.product_id, " > ".join(chunk.section_path)): str(chunk.id)
        for chunk in chunks
    }


async def evaluate(args: argparse.Namespace) -> dict:
    samples = load_samples(args)
    id_map = await load_chunk_id_map()

    rows = []
    for sample in samples:
        expected_id = id_map.get((sample["product_id"], sample["expect_path"]))
        if expected_id is None:
            # 评测集和库对不上时不能静默跳过，否则分数会虚高
            logger.error("评测集路径在库里找不到", id=sample["id"], path=sample["expect_path"])
            rows.append({**sample, "rank": None, "error": "路径未找到"})
            continue

        try:
            parents = await retriever.retrieve(
                query=query_of(sample, args.use_rewrite),
                k=args.k,
                product_id=sample["product_id"],
                fetch_k=args.fetch_k,
                use_rerank=not args.no_rerank,
            )
        except Exception as exc:  # 单条失败不影响整体，但要在报告里记下来
            logger.error("检索失败", id=sample["id"], reason=str(exc))
            rows.append({**sample, "rank": None, "error": str(exc)})
            continue

        ids = [str(parent.id) for parent in parents]
        rank = ids.index(expected_id) + 1 if expected_id in ids else 0
        rows.append({**sample, "rank": rank})

    return summarize(rows, args)


def summarize(rows: list[dict], args: argparse.Namespace) -> dict:
    valid = [row for row in rows if row.get("rank") is not None]
    total = len(valid)

    def recall_at(n: int) -> float:
        if not total:
            return 0.0
        return sum(1 for row in valid if 0 < row["rank"] <= n) / total

    mrr = sum(1 / row["rank"] for row in valid if row["rank"] > 0) / total if total else 0.0

    # 按产品分组，避免"总分还行但某个产品全线崩"被平均数掩盖
    by_product: dict[int, list[int]] = {}
    for row in valid:
        by_product.setdefault(row["product_id"], []).append(row["rank"])

    return {
        "rows": rows,
        "total": total,
        "skipped": len(rows) - total,
        "recall@1": recall_at(1),
        "recall@3": recall_at(3),
        f"recall@{args.k}": recall_at(args.k),
        "mrr": mrr,
        "by_product": by_product,
        "params": {
            "k": args.k,
            "fetch_k": args.fetch_k,
            "rerank": not args.no_rerank,
            "file": Path(args.file).stem,
            "rewrite": args.use_rewrite,
        },
    }


def write_report(result: dict) -> Path:
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    params = result["params"]
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    name = (
        f"{stamp}-{params['file']}-k{params['k']}-fetch{params['fetch_k']}"
        f"-rerank{'on' if params['rerank'] else 'off'}"
        f"{'-rewrite' if params.get('rewrite') else ''}.md"
    )
    path = REPORT_DIR / name

    k = params["k"]
    recall_k = result[f"recall@{k}"]

    lines = [
        "# RAG 检索评测报告",
        "",
        f"- 时间：{datetime.now():%Y-%m-%d %H:%M:%S}",
        f"- 数据集：{params['file']}（改写={params.get('rewrite', False)}）",
        f"- 参数：k={k}，fetch_k={params['fetch_k']}，"
        f"rerank={'on' if params['rerank'] else 'off'}",
        f"- 样本：{result['total']} 条有效，{result['skipped']} 条跳过",
        "",
        "## 汇总",
        "",
        "| 指标 | 数值 |",
        "|---|---|",
        f"| recall@1 | {result['recall@1']:.3f} |",
        f"| recall@3 | {result['recall@3']:.3f} |",
        f"| recall@{k} | {recall_k:.3f} |",
        f"| MRR | {result['mrr']:.3f} |",
        "",
        "## 分产品",
        "",
        "| 产品 | 样本数 | 命中@k | MRR |",
        "|---|---|---|---|",
    ]
    for pid, ranks in sorted(result["by_product"].items()):
        hits = sum(1 for r in ranks if 0 < r <= params["k"])
        product_mrr = sum(1 / r for r in ranks if r > 0) / len(ranks)
        lines.append(f"| {pid} | {len(ranks)} | {hits}/{len(ranks)} | {product_mrr:.3f} |")

    lines += ["", "## 明细", "", "| id | 产品 | 排名 | 问题 | 期望章节 |", "|---|---|---|---|---|"]
    for row in result["rows"]:
        rank = row.get("rank")
        rank_text = "跳过" if rank is None else ("未命中" if rank == 0 else str(rank))
        lines.append(
            # 多轮集没有 query 字段，用追问本身展示
            f"| {row['id']} | {row['product_id']} | {rank_text} | "
            f"{row.get('query') or row.get('follow_up', '')} | {row['expect_path']} |"
        )

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


async def main() -> None:
    args = parse_args()
    result = await evaluate(args)
    path = write_report(result)

    print()
    print(f"样本 {result['total']} 条（跳过 {result['skipped']} 条）")
    print(f"recall@1 = {result['recall@1']:.3f}")
    print(f"recall@3 = {result['recall@3']:.3f}")
    print(f"recall@{args.k} = {result[f'recall@{args.k}']:.3f}")
    print(f"MRR      = {result['mrr']:.3f}")
    print(f"报告：{path}")

    await close_database()


if __name__ == "__main__":
    asyncio.run(main())
