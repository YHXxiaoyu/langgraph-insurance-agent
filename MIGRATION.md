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

- [x] 端到端：登录 → 建会话 → 对话 → 流式 → 查历史 → 删除
- [x] 前端把 `x-user-id` 换成 `Authorization: Bearer <token>`
- [ ] **拆 Spring Profile**（Java 进容器时才需要，本轮未做）
  - `application.yml`：公共配置
  - `application-local.yml`：本地，连 `192.168.100.128`，开 SQL 日志
  - `application-prod.yml`：容器内，host 走环境变量，**关掉 `log-impl`**
  - 原则：profile 文件只放非敏感差异；密码、密钥始终留在 `.env` / 环境变量里
  - 反面教材：hm-service 把 `pw: 123` 明文写进 `application-local.yaml`，且
    `profiles.active` 写死默认值 —— 换环境忘覆盖时会静默连错库

### 实际改动（2026-11-22）

1. **Java 补历史接口** `GET /api/v1/chat-threads/{id}/messages`：
   先 `assertOwned`，再转调 Python `GET /internal/v1/agent/threads/{id}/messages`。
   新增 `ChatMessageVO` / `ChatHistoryVO`，返回 `thread_id + messages`。
2. **对外 VO 统一 snake_case**：`@JsonNaming(SnakeCaseStrategy)` 打在
   `ProductVO`、`ChatThreadVO`、`ChatHistoryVO`、`UserLoginVO`、`UserInfoVO` 上。
   没配全局 `spring.jackson.property-naming-strategy`，否则 Python 回调 Java 的
   `premiumMin` / `limitPerCategory` 会被静默当成 null，筛选条件失效。
3. **删除会话清 checkpoint**：Java 删完 MySQL 后，异步调 Python
   `DELETE /internal/v1/agent/threads/{id}`（`AgentClient.deleteThread()` 用
   `subscribe()`，不阻塞用户），失败只记 warn 日志。Python 侧用
   `checkpointer.adelete_thread()`，不手写 SQL 碰 LangGraph 私有表。
4. **产品列表放开匿名访问**：`GET /api/v1/products` permitAll ——
   首页在未登录时就要展示产品目录。
5. **前端切到 Java**：
   - Vite 代理默认 8001 → 8080
   - `api.ts` 新增 `unwrap()` 拆 `R<T>` 外壳、`authHeaders()` 带 Bearer、
     `login()`、`event: error` 处理
   - `auth.ts` 删掉假登录 `DEFAULT_SESSION`，存储键升到 v3
   - `AuthModal` 真正提交 `/api/v1/auth/login`，带 loading 和错误提示
   - `AdvisorPage` / `ApplicationPage` 去掉全部 `userId` 参数

### 不改的部分

- Spring Profile 拆分：Java 进容器时再做
- `/api/v1/insurance-plans/**`：仍未实现，前端静默 404，不影响主链路
- 历史接口的 `interrupt` 恒为 null：只回放文字，刷新后不恢复未确认的方案卡片

### 验收结果（2026-11-22）

| 项 | 结果 |
|---|---|
| 未登录访问 `GET /api/v1/products` | ✅ 12 条，字段为 `clause_name` / `image_url` / `min_premium` |
| 登录 `tester7308` | ✅ 返回 `token` + `user_id` |
| 建会话 | ✅ 返回 `created_at` / `updated_at` |
| 新会话查历史 | ✅ `messages: []`，`interrupt: null` |
| SSE 对话经 Java 中转 | ✅ 逐条 `event:message`，最后 `event:done` |
| 对话后查历史 | ✅ user + assistant 两条都在（含此前有 bug 的闲聊路径） |
| 不带 token 查历史 | ✅ 401 |
| 其他用户访问该会话 | ✅ 404，未触达 Python |
| Python 内部历史接口无令牌 / 有令牌 | ✅ 401 / 200 |
| 删除会话 | ✅ 200；checkpoint 6 行 → 0，writes 14 行 → 0 |
| 前端 `npm run build` | ✅ tsc + vite 通过 |
| 浏览器端到端 | ✅ 登录 → 顾问页建会话 → 流式回复 → 刷新后历史恢复 |

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

> **2026-11-24 收敛**：不再追求清单全绿，只保留"能讲出因果、体现工程能力、有独特卖点"的项。
> 核心 5 项约 4 人天，可选 3 项约 3 人天；其余标「暂不做」并写明理由——比删掉好，以后想起来了知道当时为什么不选。

### ✅ 已完成

- [x] **单元测试（Java 部分）**
  - `JwtToolTest`、`TraceIdFilterTest`、`ChatThreadServiceImplTest`，共 14 个用例
- [x] **RAG 评测集**（2026-11-23 完成 baseline）
  - 60 条「问题 → 应命中的章节路径」样本（12 产品 × 5，反向生成 + 人工校验）
  - `scripts/eval_retrieval.py` 算 recall@k / MRR，报告落 `evals/reports/`
  - baseline（k=5，fetch_k=10，rerank on）：recall@1 **0.700**，recall@3 0.883，recall@5 **0.983**，MRR **0.809**
  - 对照：关 rerank → recall@1 0.567 / MRR 0.709（**rerank 明显有效**）；fetch_k=20 → 0.683 / 0.787（无收益）
  - 详见 `agent-service/evals/README.md`
- [x] **Docker 化**（2026-11-23 完成）
  - `insurance-biz/Dockerfile`：Maven 多阶段构建 → `eclipse-temurin:21-jre`，镜像 387MB
  - `agent-service/Dockerfile`：python:3.14-slim + uv，镜像 919MB
  - `frontend/Dockerfile`：Vite 构建 → Nginx，Nginx 模板注入后端地址
  - `deploy/docker-compose.yml` 已合并 backend / agent / frontend，一键起全栈
  - Spring Profile 拆分：`local`（读 `.env`、打印 SQL）/ `prod`（纯环境变量、关闭 SQL 日志）
  - 验收通过：`/api/v1/products` 200、SSE 对话返回寒暄 + 历史落库、`/healthy` 200
- [x] **引用信息打通（citations）**（2026-11-23）
  - 工具检索时登记来源（`S1`、`S2`…），推流发 `event: additional_info`，流结束后写回 checkpoint 里那条 AI 消息
  - 历史接口从 `additional_kwargs.additional_info` 回放，刷新后引用仍在
  - 两个坑：`fastapi.sse` 的 `data` 永远 JSON 序列化 → 必须传 dict；推荐分支流事件里拿不到完整 `AIMessage` → 改为流结束后回读 checkpoint
- [x] **产品分页 + 险种筛选**（2026-11-24）
  - `GET /api/v1/products?category=&page=&pageSize=`，返回 `{items, page, page_size, total, pages}`
  - `MybatisPlusConfig` 注册分页拦截器（3.5.9 起需额外引 `mybatis-plus-jsqlparser`，否则编译期找不到 `PaginationInnerInterceptor`）
  - 前端每页 6 条（网格 3 列 × 2 行），切险种自动回第 1 页
- [x] **全量部署复验（2026-11-25）**
  - 三个镜像在 VM 上串行重构建（2 核 3.7G，不要并行），`docker compose up -d backend agent frontend`
  - 浏览器实测：首页「共 12 款产品」+ 2 页翻页 + 切「医疗险」只剩 3 款；注册/登录；
    闲聊回复；方案确认卡片（回放挂起的 interrupt）→ 点「确认保存方案」→「方案已确认」；
    引用来源 `[1]`、`[2]` 正常渲染；删过的会话再点开提示「会话不存在（错误编号 …）」
  - 注意：Lightpanda 无头浏览器读不了超过 ~20 秒的长 SSE 流（报 `Failed to read response body`），
    同一请求用 curl 走 Nginx 正常（11.5 秒、301 条 message + interrupt），长轮次对话请在真实浏览器里点

- [x] **保险方案落库 + 投保状态打通（Java 域补齐，2026-11-25）**
  - Java 新增方案域：`InsurancePlan` / `InsurancePlanItem` 两张表补齐 PO、Mapper、Service、Controller
  - 接口：`POST /internal/v1/insurance-plans`（Python 确认方案后落库）、
    `GET /api/v1/insurance-plans`、`GET /api/v1/insurance-plans/{id}`、
    `PATCH /api/v1/insurance-plans/{id}/items/{itemId}/insured`
  - 规则：查询一律带 `user_id`（别人的方案 404）、重复标记幂等、
    全部明细投完方案转 `insured`、明细/方案状态分别流转（applying ↔ insured）
  - Python 侧：`confirm_plan` 的 approve 分支改 `async`，调内部接口落库；
    `user_id` 进 state；落库结果通过 `additional_info` 的 `plan_items` 推给前端
  - 坑：LangGraph 节点跑在子任务里，ContextVar 的 `set()` 传不回父上下文，
    必须改成"改共享容器里的元素"（和 citations 往同一个 list 里 append 同理）
  - 验收：对话推荐 → 卡片确认 → MySQL 出现方案行（846.30 元）+ 2 条明细 →
    `plan_items` 事件带 plan_id/item_id → 历史回放仍在 → 两次 PATCH 后状态
    `uninsured → applying → insured`，数据库一致

- [x] **订单域 + 保单/订单拆分（2026-11-26）**
  - Java 新增订单域：`Order` / `OrderItem` 的 PO、Mapper、DTO、VO、Service、Controller，
    复用已设计好的 `orders` 表，**没有改表结构**
  - 接口：`POST /api/v1/orders`（下单）、`POST /api/v1/orders/{id}/pay`（支付）、
    `GET /api/v1/orders`、`GET /api/v1/orders/{id}`
  - 规则：价格只从方案明细读（前端传不了价）；下单与出单分离，保单只在支付成功时产生；
    支付在同事务里逐项调用方案域的 `markItemInsured`（标记 + 发单 + 推进方案状态）
  - 幂等三层：下单幂等键（明细排序后 MD5 + 唯一索引）→ 支付幂等（已支付直接返回）→
    保单主键由方案项 id 派生（name-based UUID）
  - 前端：投保页「支付」改为「下单 → 支付」，成功提示显示订单号与实付金额；
    新增「我的订单」独立页面，与「我的保单」拆开（订单是短周期交易，保单是长期契约，
    且待支付订单需要单独提醒）；两张卡片抽到 `insuranceCards.tsx` 共用
  - 验收：脚本跑通注册 → 建方案 → 下单 → 重复下单（顺序反过来，返回同一张）→ 支付 →
    重复支付 → 2 张保单 → 方案转 insured；`tsc --noEmit` 通过，页面人工点验
  - 没做（已记录）：真实支付渠道、取消订单、订单详情页、待支付订单重新支付入口、保费试算

- [x] **保费试算（demo_formula，2026-11-27）**
  - Java 新增试算域：`PremiumQuote` 的 PO / Mapper / DTO / VO、`IPremiumQuoteService`、
    `PremiumQuoteController`；`ProductController` 增加 `GET /api/v1/products/{id}/pricing-form`
  - 接口：`POST /api/v1/premium-quotes`（试算并落库）、`GET /api/v1/premium-quotes/{id}`
  - 定价：`DemoFormulaPricing` 纯函数，`基准保费 × 保额系数 × 年龄系数 × 性别系数 × 社保系数`；
    保额系数夹在 0.5~3.0；社保系数只作用于医疗险；每一步系数和算式随结果返回
  - 存快照：入参 + 结果都进 `premium_quotes`，报价 24 小时有效；`pricing_mode = demo_formula`
  - 订单引用：`CreateOrderDTO.quoteId` → 校验归属 / 未过期 / 产品一致 / 只买一项，
    订单金额取试算价，引用关系记入 `orders.quote_ids`；幂等键加入 quoteId
  - 前端：投保页第一步变成按后端 `fields` 渲染的真表单 → 试算 → 显示逐行算式与有效期；
    改参数清空旧价；支付时携带 quoteId
  - 坑：第一版解释文案用了"夹完上下限"的系数拼算式，医疗险显示成
    `50万 ÷ 300万 = 0.500`，用户一验算就发现；改成先算原始值再夹、分情况写文案
  - 验收：重疾 3185×0.85×1.05=2842.61、医疗 255×0.5×0.95×1.3=157.46 均与手算一致；
    产品不一致 / 引用试算买多项均 400；下单金额等于试算价；重复下单返回同一张；
    支付后出单、方案转 applying
  - 没做（已记录）：真实费率表解析、核保、保司接口对接、表单真实配置

- [x] **理赔域（Java 受理 + Python 咨询，2026-11-27）**
  - Java：`Claim` 的 PO / Mapper / DTO / VO、`IClaimService`、`ClaimController`（报案 / 列表 / 详情）
  - 规则：理赔必须挂在本人且保障中的保单上（不属于我 404、非 active 400、过期 400）；
    状态固定 `submitted`（审核赔付由保司完成，不造假状态机）；`amount` 在 submitted
    阶段是申请金额，赔付后才是核定金额；单号 `CL + 日期 + 主键前 6 位`
  - 内部接口 `GET /internal/v1/claims?userId=`：给 AI 查用户自己的理赔进度
  - Python：`app/agents/claims.py` 理赔咨询 Agent（查产品 + 检索条款 + 查我的理赔）；
    新增 `app/agents/session.py` 用 ContextVar 把 user_id 传给子图里的工具
    （工具不收 user_id 参数，防止提示注入越权）
  - 前端：保单卡「申请理赔」（仅 active 显示）、「我的理赔」页（报案表单 + 按险种的材料清单 + 记录列表）

- [x] **体验修复四连（2026-11-27）**
  - 投保页 5 步压成 3 步：试算 → 确认（被保人参数 / 保费明细 / 两项必勾声明）→ 支付完成；
    改参数会重置勾选，未试算不能进下一步
  - 多气泡：`chat_history` 把连续 assistant 消息合并成一条（修复后同一会话 4 条 → 2 条，10 条引用保留）
  - 产品详情「咨询顾问」带上下文：`/advisor?ask=…` → 新建会话自动发问 → 清 URL 参数防重发
  - 订单：支持取消（幂等键置 NULL 以便重新下单）与继续支付（校验试算报价是否过期）

- [x] **理赔材料清单由 AI 生成（2026-11-28）**
  - Python：`app/agents/claim_materials.py` —— 检索条款里"保险金申请 / 索赔材料"段落，
    结构化输出成清单；主模型失败切备用；结果按产品缓存 6 小时
  - 内部接口 `GET /internal/v1/agent/claim-materials?product_id=`，Java 用 45s 超时同步调用
  - Java：`GET /api/v1/policies/{id}/claim-materials`，校验保单归属；Agent 不可用时
    回落成按险种的通用清单（`generated=false`），不让报案页因为模型挂了打不开
  - 前端：进入报案页先显示「正在依据条款生成材料清单…」，成功显示生成结果与依据说明，失败回落静态清单
  - 实测：首次 9.1s（检索 + 模型）、二次 2.6s（走缓存），直接打 Python 0.2s

- [x] **单元测试补强（2026-11-28）**
  - 新增 `DemoFormulaPricingTest`（6 例）：标准算例、保额系数夹下限、无社保贵 30%、
    结果确定性、年龄分段边界、基准保费兜底
  - 新增 `OrderIdempotencyTest`（4 例）：明细顺序无关、试算不同则键不同、用户/方案隔离、键长 32
  - 全量 `mvn test`：**25 例通过**（原 15 例 + 新增 10 例）
  - 过程记录：无社保用例第一次断言写成"有社保 × 1.3"精确相等，被公式的舍入（348.075 → 348.08）打回，
    改成比比值 —— 测试第一次就抓到了自己的假设错误

### 🎯 核心（保留，约 4 人天）

- [x] **Python pytest 基础**（2026-11-24 完成）
  - `tests/test_java_client.py`：R<T> 外壳拆分、业务码失败、非 2xx、网络错误、trace 头透传（5 例）
  - `tests/test_chat_service.py`：历史过滤、interrupt 回放、流事件映射、引用推送与写回、`Command(resume)`、无 checkpointer 删除（8 例）
  - `asyncio_mode=auto`，用假 Agent 替代 LangGraph，不连库、不调模型；`uv run pytest -q` 共 13 例
- [x] **统一 traceId 收尾**（2026-11-25 完成，本地链路验收通过；部署环境待复验）
  - Java `TraceIdFilter` 生成 16 位 traceId → MDC + 响应头 `X-Trace-Id` → 下游 `AgentClient` 透传给 Python
  - Python 侧 `TraceIdMiddleware` 从同名头取值，绑定到 structlog 上下文，日志里同一条链路可 `grep` 关联
  - 前端收尾：`ApiError` 带 `traceId`，`errorText()` 统一拼成「XXX（错误编号 a1b2c3d4e5f6）」，
    聊天 SSE 的 `event:error` 也走同一套
  - 验收：Java 401 响应头有 `X-Trace-Id`；经 Vite 代理后头依然透传（`x-trace-id: cfc316e7ef2c4fed`）；
    `tsc --noEmit` 通过
  - VM 实测（2026-11-25）：浏览器里删过的会话再点开，页面提示「会话不存在（错误编号 f9abc668275e45ac）」，
    `docker logs hm-backend | grep f9abc668275e45ac` 命中同一条业务异常日志；
    带 `X-Trace-Id: tracee2e0002abcd` 发起对话，`hm-agent` 的「条款检索」日志同一串 traceId，跨进程闭环成立
- [x] **保险方案人工确认（interrupt）**（2026-11-24 完成，API 层验收通过）
  - 主图新增 `prepare_plan`（整理方案写入 state）→ `confirm_plan`（`interrupt` 暂停）两个节点
  - `pending_plan` 必须放 state 而不是 ContextVar：内中断恢复时节点会重跑，ContextVar 已是空的
  - SSE 推 `event: interrupt`；带 `decision` 的请求走 `Command(resume=decision)`；历史接口回放 `interrupt`（刷新能恢复卡片）
  - Java：`ChatRequestDTO` 加 `decision`、`message` 改为可选；`AgentClient` 透传；控制器校验二者至少有一个
  - 验收：直接调 Python 与经 Java 两条链路都通过（approve / reject 两条分支、未确认时历史返回 interrupt、确认后为 null）
  - 待办：浏览器端点击验证（需起前端）
- [x] **模型降级链**（2026-11-25 完成，三步验收通过）
  - `agents/models.py` 统一出口：主模型 DeepSeek（`timeout` + `max_retries=0`）→ 备用 DashScope `qwen-plus` → 转人工
  - 普通节点用 `with_fallbacks`；`create_agent` 子图只认 `BaseChatModel`，改用 `FallbackModelMiddleware`
    在 `wrap_model_call` / `awrap_model_call` 里 `request.override(model=...)` 重试
  - 意图识别自己 try/except 切换，不用 `with_fallbacks`（见下方坑 ①）
  - 验收（用无效 key 模拟真实故障，比换模型名更贴近线上）：
    1. 正常：DeepSeek 200，日志无降级告警
    2. 主模型故障：DeepSeek 401 → 自动切 qwen 200，回答由 qwen 产出
    3. 主备都故障：两个 401 → 意图识别兜底 `human_handoff`，前端收到转人工提示、连接不挂死
  - 踩到的坑 ①：结构化输出（`with_structured_output`）的 AIMessage 会以顶层 `messages` 事件混进流里，
    降级重试时前端气泡会直接冒出 `{"intent": "recommendation_plan"...}`；
    修法是在 `ChatService` 里跳过 `additional_kwargs["parsed"]` 非空的消息
  - 踩到的坑 ②：`IntentRouter.route()` 漏 `return result`，LLM 分支必崩（`None.intent`），
    之前只测寒暄正则分支才没暴露
- [x] **Query 改写 + 评测验证**（2026-11-24 完成）
  - 先测后做：造 12 组多轮样本（`evals/rag_eval_multiturn.jsonl`，上文 + 半句追问）
  - 追问原话直接检索：recall@1 **0.167** / recall@5 **0.333** / MRR 0.215
  - 上文+追问拼接（近似改写）：recall@1 0.583 / recall@5 **0.833** / MRR 0.656
  - 线上实测发现真正卡住的不是检索器，而是工具调用：模型传 `product_id=0`、query 只有半句
  - 修法（不加改写模型）：系统提示词要求"先查候选产品拿 id、多轮把追问补全成完整问题"；
    工具对 `product_id <= 0` 直接返回提示，让模型自查
  - 复测：两轮对话的 query 变成「…重疾险的等待期是多久」「…犹豫期内退保和犹豫期后退保能退还多少保费」，
    `product_id=3`，引用分别带出 2 条 / 4 条

### 🌟 可选（有余力再做，约 3 人天）

- [ ] **理赔咨询做实**（1 天）：`claims_node` 目前是固定文案，改成按「报案 / 材料 / 进度」分类 + 条款检索
- [ ] **保单只读列表**（1 天）：`policies` 表已有，只做列表 + 详情，不做下单流程
- [ ] **混合路由**（1 天）：规则快速通道（闲聊/理赔关键词）+ LLM 兜底，省一次模型调用

### 🚫 暂不做（附理由）

| 项 | 理由 |
|---|---|
| Testcontainers 集成测试 | 已有单元测试 + 人工验收，2 天换"更完整的测试"，边际收益低 |
| Token 成本统计 | 有评测集后讲不出新东西 |
| 接口限流 | 一个注解的事，单独讲像凑数 |
| OpenTelemetry | traceId 已覆盖核心诉求；本地也没有 Jaeger 可展示 |
| 相邻父块扩展 | 属于检索调优，用评测集试一次就知道，不值得单独立项 |
| 人工客服工作台 | 5~15 天，本质是另一个 IM 系统；`human_handoff_node` 作为"能力边界"演示已足够 |
| 订单 / 理赔全流程 | 2~4 天，会把项目重心从 AI 拉回传统 CRUD |

---

## 执行节奏

```
第 1 周：阶段 1 + 2   → 有鉴权、能查数据
第 2 周：阶段 3 + 4   → Java 侧独立可用
第 3 周：阶段 5 + 6   → 端到端跑通
第 4 周起：第一梯队优化
2026-11-24 起：按「核心 5 项（约 4 人天）」收尾，其余项见「暂不做」
```

**纪律：每个阶段结束必须有可演示的成果。**

若第 2 周末 Java 侧还不能独立跑，说明范围划大了 —— 砍掉产品 CRUD，先保会话管理。

---

## 协作方式

- 代码自己写，写完贴关键片段 review
- 卡住时报错栈 + 想实现的效果 + 已试过的方案
- 架构决策先讨论，定错了返工代价大
