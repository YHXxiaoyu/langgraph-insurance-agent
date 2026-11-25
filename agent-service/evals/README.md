# RAG 检索评测集

用来回答一个问题：**改了切分、召回或重排之后，检索到底变好了还是变坏了。**

评测只覆盖检索（`ParentChunkRetriever`），不经过 Agent 和生成模型——生成质量受 prompt 和模型版本影响太大，不可复现；而检索是地基，检索不到，模型只能编。

## 数据集

| 项 | 说明 |
|---|---|
| 文件 | `rag_eval.jsonl`（一行一条，UTF-8） |
| 规模 | 60 条，12 个产品 × 各 5 条 |
| 抽样 | 从 564 个父块里按产品分层抽取，每产品最多每个章节出一条，保证覆盖不同章节 |
| 问题来源 | 由 DeepSeek 对父块**反向生成**（给定条款片段，写一个用户会怎么问），人工过一遍 |
| 标注 | `expect_path` 存完整章节路径，不是 UUID —— 重新灌库后依然能对上 |

字段：

```json
{"id": "q001", "product_id": 1, "query": "签收后15天内退保，钱能全退吗？",
 "expect_path": "6. 如何退保 > 6.1 犹豫期", "source_body": "…", "note": ""}
```

## 指标

- **recall@k**：前 k 个父块里包含期望块的比例
- **MRR**：第一个命中位置的倒数排名取平均（排第 1 = 1.0，排第 3 = 0.33，没命中 = 0）

## 怎么跑

在 `agent-service` 目录下：

```bash
uv run python scripts/eval_retrieval.py                  # baseline
uv run python scripts/eval_retrieval.py --fetch-k 20     # 扩大召回池
uv run python scripts/eval_retrieval.py --no-rerank      # 关掉重排
uv run python scripts/eval_retrieval.py --limit 5        # 只跑前 5 条试脚本
```

每次运行都会在 `evals/reports/` 下生成一份带参数的 markdown 报告。**参数必须和数字一起记**，否则以后没法比较。

## 基线（2026-11-23）

k=5，chunk_size=500 / overlap=50

| 配置 | recall@1 | recall@3 | recall@5 | MRR |
|---|---|---|---|---|
| baseline（fetch_k=10，rerank on） | **0.700** | **0.883** | **0.983** | **0.809** |
| fetch_k=20，rerank on | 0.683 | 0.833 | 0.950 | 0.787 |
| fetch_k=10，rerank off | 0.567 | 0.850 | 0.917 | 0.709 |

结论：

1. **rerank 是刚需**：关掉后 recall@1 掉 13.3 个点，MRR 掉 0.10。
2. **扩大 fetch_k 没有收益**（甚至略降）：说明当前 10 条候选已经够用，瓶颈不在召回池大小。
3. recall@5 已接近饱和（0.983），继续优化要看 recall@1/MRR，也就是"答案排得够不够靠前"。

## 多轮检索对照（2026-11-24）

`rag_eval_multiturn.jsonl`：12 组「上文 + 半句追问 + 期望章节」，用于衡量多轮指代对检索的影响。

| 配置 | recall@1 | recall@3 | recall@5 | MRR |
|---|---|---|---|---|
| 追问原话直接检索 | 0.167 | 0.250 | **0.333** | 0.215 |
| 上文 + 追问拼接（近似改写） | 0.583 | 0.750 | **0.833** | 0.656 |

跑法：

```bash
uv run python scripts/eval_retrieval.py --file evals/rag_eval_multiturn.jsonl
uv run python scripts/eval_retrieval.py --file evals/rag_eval_multiturn.jsonl --use-rewrite
```

结论：半句追问的召回只有单轮的约三分之一，必须补全成完整问题。线上实际采用**提示词约束**
（要求模型结合上文补全 query）+ **工具校验**（`product_id <= 0` 直接返回提示），
实测两轮对话的 query 已自动补全为「…重疾险犹豫期内退保和犹豫期后退保能退还多少保费」。
样本只有 12 条，一条约 8.3 个百分点，适合看趋势，不适合引用精确小数点。

## 已知偏差（面试会被问，主动讲）

- **反向生成的偏差**：问题是从条款片段倒推出来的，天然更贴近原文语义，会**高估**真实效果。真实用户的问法更跳跃、更口语。
- 缓解办法：从真实对话记录里捞 15~20 条问法做"校准集"，对比两组分数差距。目前尚未做。
- 样本量 60，单条问题的影响约为 1.7 个百分点，指标差异小于 2 个点时应视为噪声。
