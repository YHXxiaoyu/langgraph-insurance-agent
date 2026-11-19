# 安心保 · LangGraph 多 Agent 智能保险顾问

基于 **LangGraph** 构建的保险业务智能问答系统。后端 FastAPI 提供流式对话与会话管理接口，Agent 层通过意图路由把用户问题分发给「产品推荐 / 理赔咨询 / 人工转接 / 闲聊」等不同工作流；知识问答基于 **Milvus 父子块 + 稠密向量与 BM25 混合检索 + 重排** 的 RAG 链路；前端用 React + Ant Design 实现聊天界面。

## 核心特性

- **多 Agent 编排**：意图识别节点根据上下文判定工作流，再用 `Command(goto=...)` 动态跳转，支持多轮对话中的工作流延续
- **父子块 RAG**：PDF 条款经 MinerU 解析 → 大模型矫正 Markdown → 按标题层级切父块、再递归切子块；检索命中子块后回表还原父块，保证送入模型的上下文完整
- **混合检索 + 重排**：Milvus 内置 BM25 稀疏向量与 DashScope `text-embedding-v4` 稠密向量双路召回，`gte-rerank-v2` 重排
- **会话持久化**：LangGraph Checkpointer 落库 PostgreSQL，支持多轮记忆、历史消息查询、会话重命名与删除
- **流式输出**：SSE 逐字推送模型输出

## 技术栈

| 层次 | 技术 |
|---|---|
| Agent 编排 | LangGraph、LangChain、`create_agent` |
| 大模型 | DeepSeek（对话）、DashScope（向量化 / 重排） |
| 向量库 | Milvus 3.0（standalone，含 BM25 中文分词） |
| 后端 | FastAPI、SQLAlchemy 2.x（async）、psycopg3、Pydantic v2 |
| 数据库 | PostgreSQL 16 |
| 文档解析 | MinerU |
| 前端 | React 18、TypeScript、Ant Design 5、Vite |
| 部署 | Docker Compose、Nginx |

## 系统架构

```
                     ┌──────────────────────────────┐
  浏览器 (React) ───► │  FastAPI  /api/v1/chat  (SSE) │
                     └───────────────┬──────────────┘
                                     │
                     ┌───────────────▼──────────────┐
                     │   LangGraph Orchestrator      │
                     │  ┌──────────────────────────┐ │
                     │  │ 意图路由 IntentRouter     │ │
                     │  └───┬──────┬──────┬────┬────┘ │
                     │      │      │      │    │      │
                     │   闲聊  产品推荐  理赔  人工转接 │
                     └──────┬───────┬───────────────┘
                            │       │
                            │  ┌────▼─────────────┐
                            │  │ RAG Retriever     │
                            │  │ 混合检索 + 重排    │
                            │  └────┬─────────────┘
                            │       │
                     ┌──────▼───────▼───────┐   ┌──────────────┐
                     │ PostgreSQL           │   │ Milvus       │
                     │ 业务表 + Checkpointer │   │ 父子块向量库  │
                     └──────────────────────┘   └──────────────┘
```

## 目录结构

```
hm-insurance/
├── agent-service/              # 后端服务
│   ├── app/
│   │   ├── agents/             # 意图路由、闲聊、保险顾问、编排图
│   │   ├── modules/            # chat / chat_thread / product 三个业务模块
│   │   ├── rag/                # 父子块切分、入库流水线、检索器
│   │   ├── infra/              # 数据库连接、Checkpointer
│   │   └── main.py             # FastAPI 入口
│   ├── notebooks/              # 课程实验笔记（day03 ~ day10）
│   ├── scripts/                # 知识库灌入脚本
│   ├── pyproject.toml          # uv 依赖管理
│   └── .env.example            # 环境变量模板
├── deploy/
│   ├── docker-compose.yml      # PostgreSQL / etcd / MinIO / Milvus / Attu
│   ├── postgres/init/          # 建表与初始化数据
│   └── milvus/user.yaml        # Milvus 重排模型配置
└── frontend/                   # React + Ant Design 前端
```

## 快速开始

### 1. 准备环境变量

```bash
cd agent-service
cp .env.example .env
# 编辑 .env，填入你自己的 DeepSeek / DashScope / MinerU 密钥
```

> `deploy/milvus/user.yaml` 里的 `apikey` 也需要填成你自己的 DashScope Key，否则重排功能不可用。

### 2. 启动中间件

```bash
cd deploy
docker compose up -d
```

启动的服务与端口：

| 服务 | 端口 | 说明 |
|---|---|---|
| PostgreSQL | 5432 | 业务库 + 会话检查点，首次启动自动执行 `postgres/init` 建表 |
| Milvus | 19530 | 向量数据库 |
| etcd / MinIO | 2379 / 9000、9001 | Milvus 的元数据与对象存储依赖 |
| Attu | 3000 | Milvus 可视化管理界面 |

### 3. 安装依赖并启动后端

```bash
cd agent-service
uv sync                      # 依赖中包含 ipykernel，notebook 可直接运行
uv run python -m app.main    # 服务监听 0.0.0.0:8001
```

Windows 下 `main.py` 已显式指定 `SelectorEventLoop`，因为 psycopg3 的异步模式不支持默认的 `ProactorEventLoop`。

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev                  # 默认 http://localhost:5173
```

### 5. 灌入知识库（可选）

条款 PDF 体积较大未随仓库提供，需自行放到 `agent-service/data/raw/kb/` 与 `data/raw/rate_tables/` 下，然后运行：

```bash
cd agent-service
uv run python scripts/ingest_rag.py
```

## 主要接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 健康检查 |
| GET | `/api/v1/products` | 保险产品列表 |
| POST | `/api/v1/chat` | 对话（SSE 流式返回） |
| POST | `/api/v1/chat-threads` | 新建会话 |
| GET | `/api/v1/chat-threads` | 会话列表 |
| PATCH | `/api/v1/chat-threads/{thread_id}` | 重命名会话 |
| DELETE | `/api/v1/chat-threads/{thread_id}` | 删除会话 |
| GET | `/api/v1/chat-threads/{thread_id}/messages` | 查询历史消息 |

所有业务接口通过请求头 `x-user-id` 传递用户身份。

## 说明

- 仓库内所有密钥、令牌均已移除或替换为占位符，运行前请自行配置 `.env`。
- 保险条款 PDF、MinerU 解析中间产物、前端构建产物未纳入版本管理，详见 `.gitignore`。
