# 安心保 · 智能保险顾问

面向保险业务的智能问答与产品推荐系统。项目正在从单体 FastAPI 服务**改造为双栈架构**：

- **Java（Spring Boot）** 负责业务：认证鉴权、产品查询、会话管理、事务
- **Python（FastAPI + LangGraph）** 只负责 Agent：意图路由、RAG 检索、大模型调用

> 改造计划与逐日进度见 [MIGRATION.md](MIGRATION.md)。双栈改造已完成，当前处于优化阶段
> （可信引用、方案人工确认、模型降级链、RAG 离线评测）。

## 为什么拆成两个服务

- **Agent 侧需要 LangGraph 的持久化能力**：官方 checkpointer 只提供 PostgreSQL 和 SQLite 实现，没有 MySQL 版，这是硬约束
- **业务侧用 MySQL**：国内 Java 后端的主流选择，事务、索引、MVCC 这些能力在业务系统里更常用
- **职责边界清晰**：需要事务和给后台管理用的归 Java，需要模型推理的归 Python。Python 的数据库账号对业务库没有任何权限，靠物理隔离而不是靠约定

## 核心特性

**Agent 侧**

- **多 Agent 编排**：意图识别节点根据上下文判定工作流，再用 `Command(goto=...)` 动态跳转，支持多轮对话中的工作流延续
- **父子块 RAG**：PDF 条款经 MinerU 解析 → 大模型矫正 Markdown → 按标题层级切父块、再递归切子块；检索命中子块后回表还原父块，保证送入模型的上下文完整
- **混合检索 + 重排**：Milvus 内置 BM25 稀疏向量与 DashScope `text-embedding-v4` 稠密向量双路召回，`gte-rerank-v2` 重排
- **流式输出**：SSE 逐字推送模型输出
- **可信引用**：检索结果编号 `S1/S2`…，回答末尾用 `[1][2]` 标注来源；引用随消息写入 checkpoint，刷新不丢
- **方案人工确认**：推荐完成后 `interrupt` 暂停图，前端弹方案卡，用户点「确认」才继续，不确认不落地
- **模型降级链**：主模型（DeepSeek）超时/报错自动切备用（DashScope qwen），两者都不可用则转人工

**业务侧（Java）**

- **JWT 鉴权**：Spring Security 6 + jjwt，无状态过滤链，token 解析后回库校验账号状态
- **越权防护**：会话查询把 `user_id` 写进 SQL 条件，而不是查出来再比对；「不存在」与「无权访问」统一返回 404
- **保险方案域**：Agent 确认的方案与明细落库，明细状态（未投保/已投保）与方案状态（未投保/投保中/已投保）分别流转
- **投保订单**：支付前先下单（幂等键防重复），支付成功在同一事务里出保单；价格只从方案明细读，前端传不了价
- **保单**：主键由方案项 id 派生（name-based UUID），同一方案项永远不会发出第二张保单
- **保费试算**：按年龄、性别、保额、社保计算年保费并落库，每一步系数与算式随结果返回，报价 24 小时有效；订单可引用试算结果作为最终金额（当前为演示费率 `demo_formula`，非精算结果）
- **双凭证隔离**：用户走 JWT 访问 `/api/v1/**`，服务间走 `X-Internal-Token` 访问 `/internal/**`，两套互不通用
- **接口文档**：Knife4j + springdoc 自动生成，`/doc.html` 可直接调试

## 技术栈

| 层次 | 技术 |
|---|---|
| 业务服务 | Java 21、Spring Boot 3.5.16、MyBatis-Plus 3.5.9、Spring Security 6、jjwt 0.12.6 |
| 业务库 | MySQL 8 |
| Agent 编排 | LangGraph、LangChain |
| Agent 服务 | FastAPI、SQLAlchemy 2.x（async）、psycopg3、Pydantic v2 |
| 大模型 | DeepSeek（对话）、DashScope（向量化 / 重排） |
| 向量库 | Milvus（standalone，含 BM25 中文分词） |
| Agent 库 | PostgreSQL 16（LangGraph Checkpointer） |
| 文档解析 | MinerU |
| 接口文档 | Knife4j 4.5.0（界面）+ springdoc-openapi 2.8.17 |
| 前端 | React 18、TypeScript、Ant Design 5、Vite |
| 部署 | Docker Compose、Nginx |

## 系统架构

目标形态（改造完成后的调用方向）：

```
                    浏览器（React + Ant Design）
                              │ JWT
                              ▼
                ┌──────────────────────────────┐
                │  Java  insurance-biz          │
                │  Spring Boot 3.5              │
                │  认证 / 产品 / 会话管理        │
                │  ChatController ──SSE 透传──┐ │
                └───────┬─────────────────────┘ │
                        │                        │
         内部接口回调     │                        ▼
    ┌───────────────────┘          ┌──────────────────────────┐
    │                              │  Python  agent-service    │
    │                              │  FastAPI + LangGraph      │
    │                              │  意图路由 / RAG / LLM     │
    │                              └───┬──────────────┬───────┘
    │                                  │              │
    ▼                                  ▼              ▼
┌──────────────┐            ┌──────────────┐  ┌──────────────┐
│ MySQL 8      │            │ PostgreSQL   │  │ Milvus       │
│ 业务数据      │            │ Checkpointer │  │ 父子块向量库  │
└──────────────┘            └──────────────┘  └──────────────┘
```

> 当前阶段：阶段 6 联调完成 + 优化阶段收尾 —— 前端只认 Java 的 8080，登录拿 JWT，
> 会话、历史、SSE 对话全部经 Java 中转；Java 通过 `X-Internal-Token` 调用 Python 的
> 内部接口，删除会话时顺带清理 Python 侧 checkpoint。
> 全链路带 `X-Trace-Id`，用户报错时页面给出的「错误编号」可直接在服务端日志里 grep 到。
> 详见 MIGRATION.md 与 [优化阶段笔记](insurance-biz/docs/stage-7-optimization-notes.md)。

## 目录结构

```
.
├── insurance-biz/                  # Java 业务服务
│   ├── src/main/java/com/insurance/biz/
│   │   ├── common/                 # 统一返回体 R、异常体系、全局异常处理
│   │   ├── config/                 # Security、JWT / 内部令牌配置、接口文档
│   │   ├── controller/             # 认证、产品、会话、内部接口
│   │   ├── domain/                 # dto（入参）/ po（表实体）/ vo（出参）
│   │   ├── enums/                  # 状态枚举
│   │   ├── mapper/                 # MyBatis-Plus Mapper
│   │   ├── security/               # JWT 工具、两个鉴权过滤器
│   │   ├── service/ + impl/        # 业务接口与实现
│   │   └── utils/                  # UserContext
│   ├── docs/apifox-test-plan.md    # Apifox 接口测试清单
│   ├── pom.xml
│   └── .env.example                # 数据库 / JWT / 内部令牌配置模板
├── agent-service/                  # Python Agent 服务
│   ├── app/
│   │   ├── agents/                 # 意图路由、闲聊、保险顾问、编排图
│   │   ├── modules/                # chat（内部对话 + 历史消息）
│   │   ├── rag/                    # 父子块切分、入库流水线、检索器
│   │   ├── infra/                  # 数据库连接、Checkpointer
│   │   └── main.py                 # FastAPI 入口（监听 8001）
│   ├── notebooks/                  # 课程实验笔记
│   ├── scripts/                    # 知识库灌入脚本
│   └── pyproject.toml              # uv 依赖管理
├── deploy/
│   ├── docker-compose.yml          # MySQL / PostgreSQL / etcd / MinIO / Milvus / Attu
│   ├── mysql/init/                 # 业务库建表与种子数据
│   ├── postgres/init/              # Agent 侧 schema 与 Checkpointer
│   └── milvus/user.yaml            # Milvus 重排模型配置（需填自己的 Key）
├── frontend/                       # React + Ant Design 前端
└── MIGRATION.md                    # 双栈改造计划与进度
```

## 快速开始

### 1. 准备环境变量

```bash
# Agent 服务
cd agent-service
cp .env.example .env
# 填入 DeepSeek / DashScope / MinerU 密钥

# Java 服务
cd ../insurance-biz
cp .env.example .env
# 填入数据库账号、JWT_SECRET（≥32 字节）、INTERNAL_TOKEN
```

> 两个 `.env` 都已被 `.gitignore` 排除，不会进版本库。
> `JWT_SECRET` 和 `INTERNAL_TOKEN` 可用 `openssl rand -base64 32` 生成。

### 2. 启动中间件

```bash
cd deploy
docker compose up -d
```

| 服务 | 端口 | 说明 |
|---|---|---|
| MySQL | 3306 | 业务库，首次启动自动执行 `mysql/init` 建表与灌数据 |
| PostgreSQL | 5432 | LangGraph Checkpointer 与 RAG 父块存储 |
| Milvus | 19530 | 向量数据库 |
| etcd / MinIO | 2379 / 9000、9001 | Milvus 的元数据与对象存储依赖 |
| Attu | 3000 | Milvus 可视化管理界面 |

### 3. 启动 Java 业务服务

需要 JDK 21+。

```bash
cd insurance-biz
mvn spring-boot:run            # 监听 8080
```

启动后浏览器打开 `http://localhost:8080/doc.html` 可直接查看和调试全部接口。

### 4. 启动 Python Agent 服务

```bash
cd agent-service
uv sync                      # 依赖中包含 ipykernel，notebook 可直接运行
uv run python -m app.main    # 监听 0.0.0.0:8001
```

Windows 下 `main.py` 已显式指定 `SelectorEventLoop`，因为 psycopg3 的异步模式不支持默认的 `ProactorEventLoop`。

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev                  # 默认 http://localhost:5173
```

### 6. 灌入知识库（可选）

条款 PDF 体积较大未随仓库提供，需自行放到 `agent-service/data/raw/kb/` 与 `data/raw/rate_tables/` 下，然后运行：

```bash
cd agent-service
uv run python scripts/ingest_rag.py
```

## 接口

### Java 业务服务（对外，需 JWT）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/register` | 注册，返回 token |
| POST | `/api/v1/auth/login` | 登录 |
| GET | `/api/v1/auth/me` | 当前用户 |
| GET | `/api/v1/products` | 在售产品列表 |
| POST | `/api/v1/chat-threads` | 新建会话 |
| GET | `/api/v1/chat-threads` | 会话列表 |
| GET | `/api/v1/chat-threads/{id}/messages` | 历史消息（校验归属后转调 Python） |
| PATCH | `/api/v1/chat-threads/{id}` | 重命名会话 |
| DELETE | `/api/v1/chat-threads/{id}` | 删除会话 |
| POST | `/api/v1/chat` | 对话（SSE 流式返回，Java 校验会话归属后透传给 Python） |

除注册和登录外，均需请求头 `Authorization: Bearer <token>`。

### Java 业务服务（内部，供 Python 回调）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/internal/v1/products/candidates` | 候选产品查询（推荐流程用） |
| GET | `/internal/v1/products` | 在售产品全量列表（知识库灌入脚本用） |
| GET | `/internal/v1/products/{id}` | 产品详情 |

需请求头 `X-Internal-Token`，不接受用户 JWT。

### Python Agent 服务

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/healthy` | 健康检查 |
| POST | `/internal/v1/agent/chat` | 对话（SSE，供 Java 调用，需 `X-Internal-Token`） |
| GET | `/internal/v1/agent/threads/{id}/messages` | 查询历史消息（从 checkpoint 读，供 Java 转发） |
| DELETE | `/internal/v1/agent/threads/{id}` | 会话删除后清理 checkpoint（供 Java 异步调用） |

> 阶段 5 之后 Python 不再提供任何公开接口，业务数据一律由 Java 提供；
> Python 需要产品数据时，通过 `app/infra/java_client.py` 回调 Java 的 `/internal/v1/**`。

## 开发进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | Java 骨架 + MySQL 数据层（16 张表） | ✅ |
| 2 | JWT 鉴权 | ✅ |
| 2.5 | 包结构对齐 `domain/{dto,po,vo}` 分层 | ✅ |
| 3 | 业务接口（会话 CRUD、内部接口、接口文档） | ✅ |
| 4 | AgentClient + SSE 透传 | ✅ |
| 5 | Python 瘦身（删除业务模块，改调内部接口） | ✅ |
| 6 | 端到端联调（前端切 Java + JWT） | ✅ |
| 7 | 业务域补齐（保险方案 / 保单 / 投保订单 + 我的保单/订单页） | ✅ |
| 8 | 保费试算（demo_formula，试算结果作为订单金额） | ✅ |
| 优化 | 单测 / traceId / 引用来源 / 方案确认 / 降级链 / RAG 评测 | ✅ |

## 说明

- 仓库内所有密钥、令牌均已移除或替换为占位符，运行前请自行配置 `.env`。
- 保险条款 PDF、MinerU 解析中间产物、前端构建产物、Maven `target/` 未纳入版本管理，详见 `.gitignore`。
