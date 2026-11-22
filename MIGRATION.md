# 双栈改造计划

把当前的单体 FastAPI 服务拆成两个：**Java 负责业务，Python 负责 Agent**。

- 改造周期：约 3 周
- 改造原则：**增量拆分，每一步都能跑通，随时可回退**

---

## 目标架构

```
浏览器
  │ JWT
  ▼
┌──────────────────────────────┐
│  Java  Spring Boot 3.5       │  ← 唯一对外入口
│  ├ AuthController            │
│  ├ ProductController         │
│  ├ ChatThreadController      │
│  └ ChatController ───────────┼── SSE 透传 ──┐
│                              │              │
│  AgentClient (WebClient)     │              │
│  内部接口 /internal/v1/**    │◄─────────────┤ 反向回调（查产品）
└──────────┬───────────────────┘              │
           │                                  ▼
           │                      ┌───────────────────────┐
           │                      │ Python FastAPI         │
           │                      │  LangGraph + RAG       │
           │                      │  无业务表访问权限       │
           │                      └──────────┬────────────┘
           │                                 │
      ┌────▼─────────┐             ┌─────────▼───────────┐
      │ MySQL 8      │             │ Milvus + PostgreSQL  │
      │ insurance_biz│             │ schema: agent        │
      │ products     │             │ parent_chunks        │
      │ chat_threads │             │ schema: checkpoint   │
      │ users        │             │ (LangGraph 硬性要求) │
      └──────────────┘             └──────────────────────┘
```

---

## 技术选型

| 项 | 选择 | 说明 |
|---|---|---|
| JDK | 21 LTS | Spring Boot 3.x 最低要求 17，选 21 |
| Spring Boot | **3.5.16** | 不追 4.x：4.0 起带 Spring Framework 7，MyBatis-Plus 3.5.9 不兼容，启动会报 `Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required` |
| ORM | MyBatis-Plus | 国内 Java 岗事实标准 |
| 鉴权 | Spring Security 6 + JWT | 面试高频，值得深挖 |
| 业务库 | **MySQL 8**（新建容器） | 国内 Java 岗事实标准，服务边界物理隔离 |
| Agent 库 | PostgreSQL 16（复用现有） | LangGraph checkpointer 官方只支持 PG / SQLite |
| 内部通信 | HTTP + `X-Internal-Token` | 起步够用，不引入 MQ |

### 为什么拆成两个数据库

不是随意选型，两边都有硬理由：

- **Agent 侧必须 PostgreSQL**：LangGraph 的持久化只有 `langgraph-checkpoint-postgres`
  和 SQLite 两种官方实现，**没有 MySQL 版**。这是硬约束。
- **业务侧用 MySQL**：国内 Java 招聘的事实标准，索引 / 事务 / MVCC 这些面试点
  都在 MySQL 语境下问；且已有 hmall 项目的 MySQL 经验可以复用。
- **物理隔离**：Python 的数据库账号对业务库没有任何权限，
  比靠 schema 约定可靠得多。

**代价**：多维护一套数据库；`parent_chunks.product_id` 与 `products.id`
只能软关联（跨库没有外键）。这两点都可接受。

---

## 前置环境

- [x] JDK 21 已就绪：`C:\Users\小鱼\.jdks\ms-21.0.12.1`（Microsoft OpenJDK 21.0.12.1 LTS）
  - **不要改动系统 `JAVA_HOME`**（仍是 JDK 8），在 IDEA 里给 `insurance-biz` 单独指定 SDK
- [ ] Maven `settings.xml` 增加阿里云镜像
- [ ] 确认能连上中间件：`192.168.100.128:5432`（Postgres）、`:19530`（Milvus）
- [ ] 在 `deploy/docker-compose.yml` 增加 MySQL 8 容器
- [ ] 确认现有 JDK 8 项目不受影响

---

## 阶段 1｜Java 骨架 + 数据层（2–3 天）

### 任务

- [x] `deploy/docker-compose.yml` 增加 MySQL 8 容器（端口 3306，库名 `insurance_biz`）
- [x] 建表脚本 `deploy/mysql/init/01-schema.sql`（**全部 16 张表**）
- [x] 种子数据 `deploy/mysql/init/02-seed.sql`（产品 / 费率 / 试算 / 保单 / 理赔）
- [x] 新建 `insurance-biz/`，Maven 单模块起步（先别拆多模块）
- [x] Spring Boot 连接 MySQL —— `GET /api/v1/products` 返回 12 条种子数据
- [x] Mapper + 基础查询（`ProductMapper extends BaseMapper<Product>`）
- [ ] 实体类：`Product` 已完成，`ChatThread`、`User` 待补

> **业务模型共 16 张表**，源自早期的 Java 业务服务
> （`deploy/postgres/init/01-init-biz-schema.sql` 文件头注明
> "Extracted from the former Java business-service Flyway migrations"）：
>
> | 分组 | 表 |
> |---|---|
> | 基础 | `app_metadata`、`users`、`products` |
> | 费率 | `rate_table_files`、`rate_plans`、`rate_table_items` |
> | 报价 | `premium_quotes`、`product_pricing_schemas`、`insurer_api_adapters`、`product_pricing_bindings` |
> | 交易 | `orders`、`policies`、`claims` |
> | 方案 | `insurance_plans`、`insurance_plan_items` |
> | 会话 | `chat_threads`（原属 agent schema，迁入业务库） |
>
> 表已全部建好，但 Java 侧**不必一次实现全部实体**。
> 阶段 1 只需 `Product` / `ChatThread` / `User` 三个，
> 其余等业务功能推进时再写。

### 字段对译注意事项

以 `deploy/postgres/init` 里的 DDL 为蓝本逐条翻译，重点看这几处：

| PostgreSQL | MySQL | 说明 |
|---|---|---|
| `text[]`（`highlights`） | `JSON` | MySQL 无数组类型，配 `JacksonTypeHandler` 映射 `List<String>` |
| `UUID`（`chat_threads.id`） | `CHAR(36)` | 由 Java 侧生成 UUID 字符串 |
| `numeric(14,2)` | `DECIMAL(14,2)` | 金额字段，**别用 float** |
| `bigint` | `BIGINT` | 主键与 `user_id` |
| `text` | `TEXT` | 长文本 |
| `timestamptz` | `DATETIME` | 注意统一时区（建议存 UTC 或统一 `Asia/Shanghai`） |
| `server_default='新会话'` | `DEFAULT '新会话'` | MySQL 支持中文默认值，注意字符集 `utf8mb4` |

### 验收标准

- 能通过 HTTP Client / Postman 查出产品列表
- 数据与 PostgreSQL 里的原数据一致

---

## 阶段 2｜鉴权（1 天）

### 任务

- [x] 注册 / 登录接口（`POST /api/v1/auth/register`、`/login`，外加验收用的 `/me`）
- [x] JWT 签发与校验（jjwt 0.12.6，HS256，密钥来自 `JWT_SECRET`）
- [x] `JwtAuthenticationFilter` 把 `userId` 注入 `SecurityContext`
  —— 用 Spring Security 过滤器，不用拦截器（理由见阶段 2.5）

### 验收标准

- [x] 无 token 访问业务接口 → `401`
- [x] 带 token 访问 → 能取到当前用户 ID

---

## 阶段 2.5｜工程结构对齐 hm-service（已完成）

参照 `D:\IDEA2026_code\hmall\hm-service` 的分层，重构 Java 侧的包结构。
原本 `entity/ dto/ mapper/ controller/ service/` 平铺，类一多就分不清哪个能对外返回。

### 最终包结构

```
com.insurance.biz
├── InsuranceBizApplication.java
├── common/                     ← 跨业务复用的东西（对应 hmall 的 hm-common 模块，这里是包）
│   ├── advice/CommonExceptionAdvice.java
│   ├── domain/R.java           ← 统一返回体 {code, msg, data}
│   └── exception/              ← CommonException 及 5 个子类
├── config/
│   ├── JwtProperties.java      ← @ConfigurationProperties 绑定 jwt.*
│   └── SecurityConfig.java
├── controller/                 ← 薄，只做入参接收 → 调 service → 包 R 返回
├── domain/
│   ├── dto/                    ← 入参（LoginFormDTO / RegisterFormDTO）
│   ├── po/                     ← 表实体（User / Product / ChatThread）
│   └── vo/                     ← 出参（UserLoginVO / UserInfoVO / ProductVO）
├── enums/UserStatus.java       ← @EnumValue + MybatisEnumTypeHandler
├── mapper/
├── security/                   ← JwtTool / JwtAuthenticationFilter / LoginUser
├── service/                    ← I*Service 接口
│   └── impl/                   ← *ServiceImpl extends ServiceImpl<Mapper, PO>
└── utils/UserContext.java      ← 读 SecurityContextHolder，用法同 hmall
```

### 照搬的部分

| 做法 | 收益 |
|---|---|
| `domain/{dto,po,vo}` 按数据流向分包 | 「这个类能不能直接返回给前端」一眼可见 |
| `service` 接口 + `impl`，`extends ServiceImpl<Mapper, PO>` | 实现类里直接 `lambdaQuery()` / `save()`，不注入 Mapper |
| 统一返回体 `R<T>` + 异常体系 | Controller 只写 `R.ok(...)`，异常翻译集中在一处 |
| 枚举 + `@EnumValue` | `UserStatus` 替代裸字符串，编译期能查错 |
| `JwtProperties` 配置绑定 | 替代散落的 `@Value` |
| `UserContext` | service 层取当前用户不用到处写 SecurityContext |

### 有意没照搬的部分

| hm-service 的做法 | 我们的做法 | 原因 |
|---|---|---|
| `LoginInterceptor` + 自建 ThreadLocal | `JwtAuthenticationFilter` + `SecurityContextHolder` | 它没启用 Security 的认证链（SecurityConfig 只有 17 行，只声明 PasswordEncoder 和 RSA KeyPair）；我们用了完整的 `SecurityFilterChain`，`SecurityContextHolder` 本身就是官方 ThreadLocal，再套一层是重复造轮子。`UserContext` 保留，但内部读的是 `SecurityContextHolder` |
| RSA + `hmall.jks` | HS256 对称密钥 | 只有单个服务既签发又校验，RSA 的价值在于网关用公钥验签；且 jks 文件本身也是密钥，等于多一个要防泄露的东西 |
| Maven 多模块 `hm-common` | 单模块里的 `common/` 包 | 现在只有一个服务；真出现第二个模块再抽，成本很低 |
| Feign / Nacos / Redis / MQ | 不引入 | 与「Java 业务 + Python Agent」的双栈目标无关 |

### 验收（2026-11-20 实测）

| # | 场景 | 结果 |
|---|---|---|
| 1 | 注册（含中文 displayName） | 201，返回 token + userId |
| 2 | 登录 | 200，token 169 字符 |
| 3 | `GET /auth/me` 带 token | 200，返回 userId / username / displayName（中文无损） |
| 4 | `GET /products` 带 token | 200，12 条，VO 无 `createdAt`/`updatedAt`，`highlights` 全为数组 |
| 5 | 无 token 访问业务接口 | 401 |
| 6 | 密码错误 | 401「用户名或密码错误」 |
| 7 | 用户名重复注册 | 409 |
| 8 | 用户名过短 | 400「用户名长度 3~80」 |
| 9 | 请求体不是合法 JSON | 400（不是 500） |
| 10 | 不存在的路径 | 404（不是 500） |

> 第 9、10 两项是重构时发现的真问题：`@ExceptionHandler(Exception.class)` 兜底会把
> `HttpMessageNotReadableException` 和 `NoResourceFoundException` 一起吞成 500，
> 已分别补上 400 / 404 的处理器。

---

## 阶段 3｜业务接口（2 天）

### 对外接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/products` | 产品列表 |
| POST | `/api/v1/chat-threads` | 新建会话 |
| GET | `/api/v1/chat-threads` | 会话列表 |
| PATCH | `/api/v1/chat-threads/{id}` | 重命名 |
| DELETE | `/api/v1/chat-threads/{id}` | 删除 |
| GET | `/api/v1/chat-threads/{id}/messages` | 历史消息（见决策点 ①） |

### 内部接口（Python 回调专用）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/internal/v1/products/candidates` | 候选产品查询 |
| GET | `/internal/v1/products/{id}` | 产品详情 |
| POST | `/internal/v1/chat-threads/{id}/messages` | 落库消息（决策点 ① 选 B 时才需要） |

- 内部接口用 `X-Internal-Token` 校验，不能只靠内网隔离

### 进度（2026-11-20）

- [x] `GET /api/v1/products`
- [x] `POST /api/v1/chat-threads`
- [x] `GET /api/v1/chat-threads`
- [x] `PATCH /api/v1/chat-threads/{id}`
- [x] `DELETE /api/v1/chat-threads/{id}`
- [ ] `GET /api/v1/chat-threads/{id}/messages` —— 依赖阶段 4/5（决策点 ① 选 A：转调 Python）
- [x] `/internal/v1/products/candidates` + `/internal/v1/products/{id}` + `X-Internal-Token` 校验
  - 契约与 Python 现有 SQL 语义逐条对齐：`status='active'`、`category` 去重保序、
    每类独立 `limit`、`min_premium < premiumMin`（严格小于）、
    **`ORDER BY min_premium IS NULL, min_premium ASC, id ASC`**（MySQL 无 `NULLS LAST`）
  - 两套凭证互不通用：内部 token 不能访问 `/api/v1/**`，用户 JWT 不能访问 `/internal/**`
- [ ] `/internal/v1/chat-threads/{id}/messages` —— 决策点 ① 选 B 时才需要，暂缓
- [x] 接口文档页 `/doc.html`：Knife4j 4.5.0 的界面 + springdoc 2.8.17 生成
  - **不能用 knife4j 的 starter**：它 4.5.0 是照着 springdoc 2.3.0 编译的，
    而 Boot 3.5 需要 springdoc 2.8.x，运行时报
    `NoSuchMethodError: SpringDocConfigProperties.getGroupConfigs()`
  - 正确做法：只引 `knife4j-openapi3-ui`（纯前端资源）+ `springdoc-openapi-starter-webmvc-ui`
  - Security 需要放行 `/doc.html`、`/webjars/**`、`/v3/api-docs/**`、`/swagger-ui/**`

> **会话接口的归属校验**：所有查询都带上 `user_id` 条件，而不是先按 id 查再用
> `userId` 比对 —— 后者等于把「这个 id 存在」泄露给攻击者。查不到就统一返回 404，
> 「不存在」和「不是你的」对外表现一致。

### 验收标准

- 6 个对外接口全部可用
- 完全不依赖 Python 服务

---

## 阶段 4｜AgentClient + SSE 透传（1–2 天）

### 任务

- [x] `AgentClient` 用 `WebClient` 调 Python 的 `/internal/v1/agent/chat`
- [x] `ChatController` 返回 `Flux<ServerSentEvent>`
- [x] 读超时设置 **≥ 60 秒**（LLM 首 token 慢，默认 30 秒不够）
- [x] Python 侧新增 `POST /internal/v1/agent/chat`，用 `X-Internal-Token` 校验
- [x] 归属校验放在建流之前（`assertOwned`），非法会话直接 404

### 进度（2026-11-22）

- 代码全部完成，`mvn compile` 与 Python 导入检查通过，路由已注册，两边 `INTERNAL_TOKEN` 一致
- **运行时验收 8 项全部通过**（结果见下表）
- 超时说明：Spring 6.2 的 `ReactiveTypeHandler` 给 `Flux<ServerSentEvent>` 创建 `SseEmitter`
  时固定用 `-1`（永不超时），`spring.mvc.async.request-timeout` 对这条路径不生效；
  真正起作用的是 `AgentClient` 的 `responseTimeout(120s)`（只管到收到响应头为止）
- SSE 数据格式：Python 的 `fastapi.sse.ServerSentEvent` 会把字符串 **JSON 序列化**，
  线上是 `data: "你好"`（带引号）；Java 侧原样透传，前端 `parseSseMessage` 已经 `JSON.parse`，
  两边格式一致，不用额外处理

### 验收标准

- 前端把 baseURL 指向 Java 端口，对话能正常流式输出

### 验收表

| # | 场景 | 期望 | 结果（2026-11-22） |
|---|---|---|---|
| 1 | 不带 token 调 `POST /api/v1/chat` | 401 | ✅ 401「未登录或登录已过期」 |
| 2 | 带 token + 别人的 threadId | 404 | ✅ 404「会话不存在」 |
| 3 | 带 token + 自己的 threadId | 200 `text/event-stream`，收到 `message` 若干 + `done` | ✅ 200，32 条 `message` + 1 条 `done` |
| 4 | message 为空 | 400 | ✅ 400「消息内容不能为空」 |
| 5 | 停掉 Python 再调 | 200 且收到一条 `event: error` | ✅ 200，`error: Agent 服务暂时不可用`，Java 日志有「调用 Agent 服务失败」 |
| 6 | 直接调 Python 内部接口，不带令牌 | 401 | ✅ 401 |
| 7 | 同上，错误令牌 | 401 | ✅ 401 |
| 8 | 同上，正确令牌 | 200，能收到 SSE | ✅ 200，24 条 `message` + 1 条 `done` |

---

## 阶段 5｜Python 瘦身（1 天）

### 改动清单

| 动作 | 目标文件 |
|---|---|
| 删除整个模块 | `app/modules/product/` |
| 删除整个模块 | `app/modules/chat_threads/` |
| 删除公开路由 | `app/modules/chat/router.py`（内部路由在阶段 4 已经建好） |
| 删除会话归属校验 | `app/modules/chat/service.py`（`required_owner` 相关代码） |
| 工具改 HTTP 回调 | `app/agents/tools.py`（`query_candidate_products`） |

### 实际改动（2026-11-22）

比原计划多做了三件事：

1. **历史消息保留成内部接口**：`GET /internal/v1/agent/threads/{id}/messages`。
   原计划把 `chat_threads/` 整个删掉，但历史只依赖 LangGraph checkpoint、不碰业务库，
   而阶段 6 前端查历史还要用，所以留在 Python 侧（MIGRATION「必须拍板」里的方案 A）。
2. **Java 补 `GET /internal/v1/products`**：知识库灌入脚本原来从 PostgreSQL 读产品清单，
   删掉 product 模块后改调 Java。这个接口只给灌库脚本用。
3. **修复节点返回 key 的 bug**：各节点原来返回 `{"message": [...]}`，而状态里声明的通道是
   `messages`，多出来的 key 被丢弃 —— 助手回复从来没进过状态，导致历史只有用户消息、
   `find_previous_ai_message` 永远取不到上一轮 AI 回复。已统一改成 `messages`
   （`chitchat.py` 2 处、`orchestrator.py` 3 处）。

新增文件：`app/infra/java_client.py`（httpx 异步客户端，懒加载单例 + 自动带
`X-Internal-Token` + 拆 `R<T>` 外壳）。

### 不动的部分

- `app/agents/` —— 编排图、意图路由、工具定义
- `app/rag/` —— 父子块切分、检索、重排
- `app/infra/checkpointer.py` —— 只改连接串，仍是 PostgreSQL
- `notebooks/` —— 课程笔记

> **注意**：Python 侧**不连接 MySQL**。所有业务数据一律通过 Java 的内部接口获取。
> 数据库配置里不该出现 MySQL 的连接信息。

### 验收标准

- Python 服务能独立启动（不依赖 `products` / `chat_threads` 表）
- `curl` 能正常打出 SSE 响应

### 验收结果（2026-11-22）

| 项 | 结果 |
|---|---|
| Python 独立启动，路由只剩 `/healthy` + 两个内部接口 | ✅ |
| 公开 `POST /api/v1/chat` 已移除 | ✅ 404 |
| Java → Python 完整对话 | ✅ 200，33 条 `message` + 1 条 `done` |
| 内部历史接口不带令牌 / 带令牌 | ✅ 401 / 200 |
| 历史内容包含 user + assistant | ✅ 修复 key 后验证通过 |
| Java 内部产品列表 | ✅ 12 条在售产品 |
| 工具 `query_candidate_products` 走 Java 取数 | ✅ 返回 2 条 medical 产品 |
| 灌库脚本 `find_all_products()` | ✅ 12 个 `ProductRef` |

---

## 阶段 6｜联调（1–2 天）

### 任务

- [ ] 端到端：登录 → 建会话 → 对话 → 流式 → 查历史 → 删除
- [ ] 前端把 `x-user-id` 换成 `Authorization: Bearer <token>`
- [ ] **拆 Spring Profile**（Java 进容器时才需要）
  - `application.yml`：公共配置
  - `application-local.yml`：本地，连 `192.168.100.128`，开 SQL 日志
  - `application-prod.yml`：容器内，host 走环境变量，**关掉 `log-impl`**
  - 原则：profile 文件只放非敏感差异；密码、密钥始终留在 `.env` / 环境变量里
  - 反面教材：hm-service 把 `pw: 123` 明文写进 `application-local.yaml`，且
    `profiles.active` 写死默认值 —— 换环境忘覆盖时会静默连错库

### 验收标准

- 全链路跑通，无 401 / 502
- 跨服务调用链路可以通过 traceId 串起来（如果做了这个优化）

---

## 必须拍板的两个设计点

### ① 历史消息存哪？

**现状**：`get_chat_history` 从 LangGraph checkpoint 的
`state_snapshot.values["messages"]` 里取（见 `modules/chat_threads/service.py`）。

| 方案 | 做法 | 代价 |
|---|---|---|
| **A（先做）** | Java 转调 Python 内部接口拿历史 | 改动最小，1 天 |
| **B（更正统）** | Java 建 `chat_messages` 表，Python 回复后回调写入 | 业务系统标准做法，但多一条写路径、要处理失败重试 |

**决定：先 A，跑通后再考虑迁到 B。**

### ② 会话删除时 checkpoint 怎么清？

Java 删 `chat_threads` 记录后，Python 侧要清掉对应 thread 的 checkpoint 数据。

- 做法：Java 删完异步调 `DELETE /internal/v1/agent/threads/{id}`
- 失败处理：记日志 + 定时任务补偿
- **面试高频考点**：跨服务删除的最终一致性

---

## 优化清单（改造完成后再动）

### 🥇 第一梯队 —— 性价比最高，必做

- [ ] **单元测试 + 集成测试**
  - Java：JUnit 5 + Testcontainers
  - Python：pytest
  - 当前项目**零测试**，这是最明显的短板
- [ ] **RAG 评测集**
  - 造 50–100 条「问题 → 应命中的条款」样本
  - 计算 recall@k、MRR，对比不同切分策略
  - **技术含量最高，最值得讲**
- [ ] **Docker 化**
  - 两个服务各写 Dockerfile
  - 合并成一份完整的 `docker-compose.yml`，一键起全栈
- [ ] **统一 traceId**
  - Java 生成，通过 header 传给 Python，两边日志都打
  - 排查跨服务问题全靠它

### 🥈 第二梯队 —— 锦上添花

- [ ] **混合路由**
  - 现状：纯 LLM 分类意图，每轮都多一次模型调用
  - 改进：规则快速通道（闲聊/理赔关键词）+ LLM 兜底
  - 收益：省钱、降延迟，且是真实生产做法
- [ ] **模型降级链**
  - 主模型超时 → 备用模型 → 转人工
- [ ] **Token 成本统计**
  - 每次调用记录 token 数，按会话/用户聚合
- [ ] **Query 改写**
  - 多轮对话中「那它呢」这类指代，先改写成完整问题再检索

### 🥉 第三梯队 —— 有余力再说

- [ ] 接口限流（Sentinel / Bucket4j）
- [ ] 链路追踪（Micrometer + OpenTelemetry 接入）
- [ ] 相邻父块扩展（contextual retrieval）
- [ ] 人工客服真正的工作台（当前 `human_handoff_node` 是占位节点）

---

## 执行节奏

```
第 1 周：阶段 1 + 2   → 有鉴权、能查数据
第 2 周：阶段 3 + 4   → Java 侧独立可用
第 3 周：阶段 5 + 6   → 端到端跑通
第 4 周起：第一梯队优化
```

**纪律：每个阶段结束必须有可演示的成果。**

若第 2 周末 Java 侧还不能独立跑，说明范围划大了 —— 砍掉产品 CRUD，先保会话管理。

---

## 协作方式

- 代码自己写，写完贴关键片段 review
- 卡住时报错栈 + 想实现的效果 + 已试过的方案
- 架构决策先讨论，定错了返工代价大
