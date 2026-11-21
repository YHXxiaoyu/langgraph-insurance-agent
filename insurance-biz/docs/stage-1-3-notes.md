# 阶段 1–3 笔记：从单体到双栈

> 整理 2026-11-19 ~ 2026-11-20 的工作，方便回顾。
> 完整原始计划见项目根目录 `MIGRATION.md`，阶段 4 见 `stage-4-sse-notes.md`。
> 代码位置：`insurance-biz/src/main/java/com/insurance/biz/`

---

## 全局：为什么拆成两个服务

不是随意选型，两边都有硬理由：

| 理由 | 说明 |
|---|---|
| **Agent 侧必须 PostgreSQL** | LangGraph 的持久化只有 `langgraph-checkpoint-postgres` 和 SQLite 两种官方实现，**没有 MySQL 版**。这是硬约束 |
| **业务侧用 MySQL** | 国内 Java 招聘的事标准，索引 / 事务 / MVCC 这些面试点都在 MySQL 语境下问；且已有 hmall 的 MySQL 经验可复用 |
| **物理隔离** | Python 的数据库账号对业务库没有任何权限，比靠 schema 约定可靠得多 |

**代价**：多维护一套数据库；`parent_chunks.product_id` 与 `products.id`
只能软关联（跨库没有外键）。这两点都可接受。

---

## 阶段 1｜Java 骨架 + 数据层

### 做了什么

1. **MySQL 8 容器**：`deploy/docker-compose.yml` 里新增 `mysql` 服务，
   容器名 `hm-mysql`，端口 3306，库名 `insurance_biz`，
   字符集 `utf8mb4` / `utf8mb4_unicode_ci`，时区 `+08:00`。
   建表脚本挂到 `/docker-entrypoint-initdb.d`，**数据卷为空时自动执行**。
2. **16 张表**：`deploy/mysql/init/01-schema.sql`，
   从早期的 PostgreSQL 业务库 DDL 逐条翻译而来。
3. **种子数据**：`deploy/mysql/init/02-seed.sql`（产品 / 费率 / 试算 / 保单 / 理赔）。
4. **Spring Boot 工程**：Maven 单模块，包名 `com.insurance.biz`。
5. **第一个接口**：`GET /api/v1/products` 返回 12 条种子产品。

业务表分组：

| 分组 | 表 |
|---|---|
| 基础 | `app_metadata`、`users`、`products` |
| 费率 | `rate_table_files`、`rate_plans`、`rate_table_items` |
| 报价 | `premium_quotes`、`product_pricing_schemas`、`insurer_api_adapters`、`product_pricing_bindings` |
| 交易 | `orders`、`policies`、`claims` |
| 方案 | `insurance_plans`、`insurance_plan_items` |
| 会话 | `chat_threads`（原属 agent schema，迁入业务库） |

> 表全部建好了，但 Java 侧**不需要一次实现全部实体**。
> 目前只写了 `Product` / `ChatThread` / `User` 三个 PO，其余等业务推进时再补。

### PostgreSQL → MySQL 类型对译

| PostgreSQL | MySQL | 说明 |
|---|---|---|
| `BIGSERIAL` | `BIGINT AUTO_INCREMENT` | 主键 |
| `UUID` | `CHAR(36)` | 由 Java 侧生成 UUID 字符串 |
| `TIMESTAMP WITH TIME ZONE` | `DATETIME` | 统一 `Asia/Shanghai` |
| `JSONB` / `text[]` | `JSON` | MySQL 无数组类型 |
| `numeric(14,2)` | `DECIMAL(14,2)` | 金额**绝不能用 float** |
| `ON CONFLICT` | `INSERT IGNORE` / `ON DUPLICATE KEY UPDATE` | 语法差异 |
| GIN 索引 | 无对应 | MySQL 没有 GIN，已省略 |

### 阶段 1 的两个坑

**坑 1：Spring Boot 版本选错，启动就报错。**

IDEA 向导默认给的是 **Spring Boot 4.1.2**，它自带 Spring Framework 7，
而 MyBatis-Plus 3.5.9 只支持到 Spring 6，启动时报：

```
Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required
```

**正确版本是 Spring Boot 3.5.16。** 不追 4.x。

**坑 2：MySQL 的 JSON 列不会自动变成 `List<String>`。**

`products.highlights` 在 MySQL 里是 JSON，实体类要两样东西配合：

```java
@TableName(value = "products", autoResultMap = true)   // autoResultMap 必须开
public class Product {
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> highlights;
}
```

少了 `autoResultMap = true`，TypeHandler 不会生效，取出来是字符串。

### 验收

`GET /api/v1/products` 返回 12 条，`highlights` 正确反序列化为数组。

---

## 阶段 2｜鉴权

### 技术选型

| 项 | 选择 |
|---|---|
| 框架 | Spring Security 6（`spring-boot-starter-security`） |
| JWT 库 | jjwt **0.12.6** |
| 算法 | HS256（对称密钥，来自 `JWT_SECRET`） |
| 密码 | BCrypt（`BCryptPasswordEncoder`） |

> jjwt 拆了三个包：`jjwt-api`（编译期用）+ `jjwt-impl` / `jjwt-jackson`（运行期用）。

### 登录一次，之后靠什么认人

```
POST /auth/register  ──> 存用户（BCrypt 加密密码）──> 签发 JWT 返回
POST /auth/login     ──> 校验密码 ──────────────────> 签发 JWT 返回
GET  /api/v1/**      ──> Authorization: Bearer <token>
                         │
                         ▼
                    JwtAuthenticationFilter
                     1. 取出 token
                     2. JwtTool 解析出 userId
                     3. 回库查用户（确认存在 + status = ACTIVE）
                     4. 塞进 SecurityContext
```

**第 3 步为什么每次都回库查：** 用户被删或被禁用时，已签发的 token 要立即失效。
代价是每个请求多一次主键查询，可以接受。

### SecurityFilterChain 的关键配置

```java
.csrf(disable)                                  // 无状态 API，不需要
.sessionManagement(STATELESS)                   // 不建 session
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login").permitAll()
    .requestMatchers("/internal/**").permitAll()          // 交给 InternalTokenFilter
    .requestMatchers("/doc.html", "/v3/api-docs/**", ...).permitAll()
    .anyRequest().authenticated())
.exceptionHandling(...)                         // 401 返回统一 JSON 而不是默认页面
.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
```

**注意 `/internal/**` 为什么要 `permitAll`：** 不放行的话 JWT 过滤器会先把
Python 的请求 401 掉。放行之后由 `InternalTokenFilter` 单独把关 —— 两道门，各管一段路径。

### 验收（2026-11-20 实测 10 项全过）

| # | 场景 | 结果 |
|---|---|---|
| 1 | 注册（含中文 displayName） | 201，返回 token + userId |
| 2 | 登录 | 200，token 169 字符 |
| 3 | `GET /auth/me` 带 token | 200，userId / username / displayName（中文无损） |
| 4 | `GET /products` 带 token | 200，12 条 |
| 5 | 无 token 访问业务接口 | 401 |
| 6 | 密码错误 | 401「用户名或密码错误」 |
| 7 | 用户名重复注册 | 409 |
| 8 | 用户名过短 | 400「用户名长度 3~80」 |
| 9 | 请求体不是合法 JSON | 400 |
| 10 | 不存在的路径 | 404 |

> 第 6 项故意不区分「用户名不存在」和「密码错误」，避免被撞库探测。

---

## 阶段 2.5｜工程结构对齐 hm-service

参照 `D:\IDEA2026_code\hmall\hm-service` 的分层，重构包结构。
原本 `entity/ dto/ mapper/ controller/ service/` 平铺，类一多就分不清哪个能对外返回。

### 最终包结构

```
com.insurance.biz
├── InsuranceBizApplication.java
├── common/                     ← 跨业务复用（对应 hmall 的 hm-common 模块）
│   ├── advice/CommonExceptionAdvice.java
│   ├── domain/R.java           ← 统一返回体 {code, msg, data}
│   └── exception/              ← CommonException 及 5 个子类
├── config/                     ← JwtProperties / InternalProperties / SecurityConfig / OpenApiConfig
├── controller/                 ← 薄：接参 → 调 service → 包 R 返回
├── domain/
│   ├── dto/                    ← 入参
│   ├── po/                     ← 表实体
│   └── vo/                     ← 出参
├── enums/UserStatus.java       ← @EnumValue
├── mapper/
├── security/                   ← JwtTool / JwtAuthenticationFilter / InternalTokenFilter / LoginUser
├── service/ + impl/            ← I*Service + *ServiceImpl extends ServiceImpl<Mapper, PO>
└── utils/UserContext.java      ← 读 SecurityContextHolder
```

### 照搬了什么

| 做法 | 收益 |
|---|---|
| `domain/{dto,po,vo}` 按数据流向分包 | 「这个类能不能直接返回给前端」一眼可见 |
| `service` 接口 + `impl`，`extends ServiceImpl<Mapper, PO>` | 实现类里直接 `lambdaQuery()` / `save()`，不用注入 Mapper |
| 统一返回体 `R<T>` + 异常体系 | Controller 只写 `R.ok(...)`，异常翻译集中在一处 |
| 枚举 + `@EnumValue` | `UserStatus` 替代裸字符串，编译期能查错 |
| `JwtProperties` 配置绑定 | 替代散落的 `@Value` |
| `UserContext` | service 层取当前用户不用到处写 SecurityContext |

### 有意没照搬什么

| hm-service | 我们的做法 | 原因 |
|---|---|---|
| `LoginInterceptor` + 自建 ThreadLocal | `JwtAuthenticationFilter` + `SecurityContextHolder` | hmall 没启用 Security 的认证链；我们用了完整的 `SecurityFilterChain`，`SecurityContextHolder` 本身就是官方 ThreadLocal，再套一层是重复造轮子 |
| RSA + `hmall.jks` | HS256 对称密钥 | 只有单个服务既签发又校验；RSA 的价值在网关用公钥验签，否则 jks 文件本身也是要防泄露的东西 |
| Maven 多模块 `hm-common` | 单模块里的 `common/` 包 | 现在只有一个服务，真出现第二个模块再抽 |
| Feign / Nacos / Redis / MQ | 不引入 | 与「Java 业务 + Python Agent」的双栈目标无关 |

### 重构时发现的真 bug

`@ExceptionHandler(Exception.class)` 兜底会把所有异常都变成 500。
实测时发现两类被吞掉的：

| 异常 | 正确状态码 | 处理 |
|---|---|---|
| `HttpMessageNotReadableException` | 400 | 新增处理器 |
| `NoResourceFoundException` | 404 | 新增处理器 |

这两项就是验收表第 9、10 项的来源。

---

## 阶段 3｜业务接口

### 对外接口（`/api/v1/**`，需 JWT）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/products` | 产品列表 |
| POST | `/api/v1/chat-threads` | 新建会话（标题可省略，默认「新会话」） |
| GET | `/api/v1/chat-threads` | 会话列表，按最后更新时间倒序 |
| PATCH | `/api/v1/chat-threads/{id}` | 重命名 |
| DELETE | `/api/v1/chat-threads/{id}` | 删除 |
| GET | `/api/v1/chat-threads/{id}/messages` | 历史消息（依赖阶段 4/5，未做） |

### 内部接口（`/internal/v1/**`，需 `X-Internal-Token`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/internal/v1/products/candidates` | 候选产品查询（推荐流程用） |
| GET | `/internal/v1/products/{id}` | 产品详情 |

### 重点 1：越权防护写在 SQL 条件里

会话的每个操作都是这样查的：

```java
ChatThread thread = lambdaQuery()
        .eq(ChatThread::getId, id)
        .eq(ChatThread::getUserId, UserContext.getUserId())   // ← 关键
        .one();
if (thread == null) {
    throw new NotFoundException("会话不存在");               // ← 统一 404
}
```

两个有意为之的设计：

- **查询条件同时带 `id` 和 `userId`**，别人的会话在这里就是「查不到」。
  不能先按 id 查出来再比对 `userId` —— 那等于把「这个 id 存在」泄露给攻击者。
- **「不存在」和「不是你的」返回同一种结果**，避免被枚举探测。

### 重点 2：两套凭证互不通用

```
用户 JWT  →  只能进 /api/v1/**
内部令牌  →  只能进 /internal/**
```

`InternalTokenFilter` 里的几个细节：

```java
@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    // @Component 的 Filter 对所有 URL 生效，不加这个会把 /api/v1/** 也 401 掉
    return !request.getRequestURI().startsWith("/internal/");
}

boolean valid = token != null
        && MessageDigest.isEqual(expected, token.getBytes(UTF_8));
        // 用 isEqual 而不是 equals：equals 逐字符短路，能被响应时间差逐位猜出
```

### 重点 3：内部接口的 SQL 语义跟 Python 对齐

`/internal/v1/products/candidates` 是把 Python 原有的候选产品 SQL 翻译过来的，
几条语义逐条对齐：

| Python 原语义 | Java 实现 |
|---|---|
| 只查 `status = 'active'` | 同 |
| `category` 去重保序 | `LinkedHashSet` |
| 每个险种各取前 N 个 | 每类独立 `limit` |
| `min_premium < premiumMin` | **严格小于**，不是 `<=` |
| `NULLS LAST` | MySQL 没有这个关键字，改写成 `ORDER BY min_premium IS NULL, min_premium ASC, id ASC` |

### 重点 4：Knife4j 的版本陷阱

接口文档页 `/doc.html` 需要两个依赖配合：

```xml
<dependency>   <!-- 只有前端界面 -->
    <groupId>com.github.xiaoymin</groupId>
    <artifactId>knife4j-openapi3-ui</artifactId>
    <version>4.5.0</version>
</dependency>
<dependency>   <!-- 文档生成 -->
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.8.17</version>
</dependency>
```

**不能引 `knife4j` 的 starter。** 它 4.5.0 是照着 springdoc 2.3.0 编译的，
而 Boot 3.5 需要 springdoc 2.8.x，运行时报：

```
NoSuchMethodError: SpringDocConfigProperties.getGroupConfigs()
```

另外 Security 要放行 `/doc.html`、`/webjars/**`、`/v3/api-docs/**`、`/swagger-ui/**`，
否则打开是 401。

### 验收

Apifox 测试清单写在 [apifox-test-plan.md](apifox-test-plan.md)，
分两组：用户主流程 14 步、内部接口鉴权边界 8 步。

---

## 贯穿全程的约定

### 密钥管理

```
insurance-biz/.env          ← 真实密钥，.gitignore 排除，永不提交
insurance-biz/.env.example  ← 模板，提交到仓库
```

`application.yml` 里所有敏感项**不写默认值**：

```yaml
jwt:
  secret: ${JWT_SECRET}          # 缺了直接启动失败，而不是用一个弱默认值跑起来
internal:
  token: ${INTERNAL_TOKEN}
```

启动时通过 `spring.config.import: optional:file:.env[.properties]` 把 `.env` 当配置文件读进来。

### JDK

- JDK 21 在 `C:\Users\小鱼\.jdks\ms-21.0.12.1`
- **系统 `JAVA_HOME` 保持 JDK 8 不动**，在 IDEA 里给 `insurance-biz` 单独指定 SDK

### 时间线

| 日期 | 内容 |
|---|---|
| 2026-11-19 | 阶段 1：MySQL 容器、16 张表、种子数据、Java 骨架 |
| 2026-11-20 | 阶段 2 鉴权 + 阶段 2.5 结构重构 + 阶段 3 业务接口 |
| 2026-11-21 | 阶段 4：AgentClient + SSE 透传（见 `stage-4-sse-notes.md`） |

---

## 相关文档

- `MIGRATION.md` —— 完整改造计划与逐项进度
- `stage-4-sse-notes.md` —— SSE、WebClient、服务间调用
- `apifox-test-plan.md` —— 接口验收清单

---

*最后更新：2026-11-21*
