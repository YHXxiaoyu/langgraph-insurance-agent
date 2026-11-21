# 阶段 4 笔记：SSE 透传与 Java ↔ Python 调用

> 记录 2026-11-21 做阶段 4 时的问答，方便以后回顾。
> 涉及的代码见 `client/AgentClient.java`、`controller/ChatController.java`，
> Python 侧见 `agent-service/app/modules/chat/internal_router.py`。

---

## 一、阶段 4 是做什么的

### 目标

把「对话入口」从 Python 收回到 Java。

**改造前：**

```
浏览器 ──/api/v1/chat──> Python :8001
         请求头: x-user-id: 12
```

问题：`x-user-id` 是前端自己填的。任何人把 `x-user-id` 改成别人的 ID，
就能读别人的对话 —— 身份凭证挂在一个 HTTP 头里，等于没有鉴权。

**改造后：**

```
浏览器 ──/api/v1/chat──> Java :8080 ──内部接口 + X-Internal-Token──> Python :8001
         Authorization:    ↑ 校验 JWT
         Bearer <token>    ↑ 校验 threadId 是不是你的
                           ↑ 原样把 SSE 流透传回去
```

### 做完之后多了什么

1. **对话有了真正的鉴权**：用户身份来自 JWT，不是前端自报的 header。
   别人的会话 ID 拿来调，直接 404。
2. **前端只需要认识一个后端**：不用同时配置 Python 的 8001 和 Java 的 8080。
3. **Python 退到内网**：它不再接受用户凭证，只接受服务间令牌。
4. **归属校验发生在流开始之前**：非法请求返回干净的 404 JSON，
   而不是先建一条 SSE 流再断掉。

### 阶段 4 不做什么

| 遗留项 | 归哪个阶段 |
|---|---|
| Python 侧的 `chat_threads` 表、`/api/v1/chat-threads` 接口还在 | 阶段 5 删 |
| 前端还在直连 8001 | 阶段 6 改成指向 8080 |
| 历史消息接口 `/chat-threads/{id}/messages` | 依赖阶段 5 |

### 验收标准

**把前端 baseURL 从 `:8001` 改成 `:8080`，对话能正常流式输出。**

一句话概括：**阶段 4 是把「谁能说话」从 Python 手里收回到 Java 手里，
同时保证流式输出不受影响。**

---

## 二、一次对话的完整链路

```
① 浏览器  POST /api/v1/chat
          Authorization: Bearer <用户JWT>
          { threadId, message }
                 │
                 ▼
② Java    1. JwtAuthenticationFilter 解析 token → 得到 userId
          2. 校验 threadId 是不是这个 userId 名下的 → 不是就 404
          3. 换成服务间凭证，转发：
             POST http://127.0.0.1:8001/internal/v1/agent/chat
             X-Internal-Token: <内部令牌>     ← 不是用户的 JWT
             { user_id, thread_id, message }
                 │
                 ▼
③ Python  1. 校验 X-Internal-Token
          2. 跑 Agent：意图路由 → RAG 检索 → 调大模型
          3. 每生成几个字就吐一个 SSE 事件
                 │
                 ▼
④ Java    收到一个事件就转发一个，不攒着
                 │
                 ▼
⑤ 浏览器  陆续收到 event: message，最后 event: done
```

### 两个要点

**1. 两段用的是不同的凭证。**

- 浏览器 → Java：用户 **JWT**
- Java → Python：**X-Internal-Token**

用户 token 到不了 Python，Python 也不认它。这就是阶段 2 定下的双凭证模型，
阶段 4 把它用到了实际的调用链上。

**2. 「原路返回」是流式透传，不是等 Python 说完再返回。**

Java 收到一个 SSE 事件就立刻往浏览器写一个。如果 Java 先收完再一次性返回，
流式输出就退化成「转圈等半天然后刷出一整段」。

### 分工

> **Java 管「你能不能说话」，Python 管「说什么」。**

Python 仍然负责：Agent 编排、RAG 检索、大模型调用、LangGraph checkpointer
（PostgreSQL 记对话状态）。Java 不碰这些，只做鉴权和转发。

---

## 三、SSE 是什么

普通 HTTP 是「一问一答」：服务器算完，一次性把整个响应发回来，连接结束。

SSE（Server-Sent Events）是 **「一问，答案分很多次慢慢给」**：
服务器返回一个 `Content-Type: text/event-stream` 的响应，**响应一直不结束**，
服务器每隔一会儿往里面写一段文本，格式固定：

```
event: message
data: 你好

event: message
data: 我是安心保

event: done
data: END

```

规则：

- 一个事件 = 若干行 `字段: 值`，**以一个空行结尾**
- `event:` 是事件名，`data:` 是内容
- 浏览器收到一段就能立刻处理一段

### 和 WebSocket 的区别

| | SSE | WebSocket |
|---|---|---|
| 方向 | 单行道（服务器 → 浏览器） | 双向 |
| 协议 | 普通 HTTP 响应 | 需要 Upgrade 协议 |
| 适合 | 推送、流式输出 | 聊天室、实时协作 |

用在「LLM 一边生成一边显示」这个场景，SSE 刚好够用，
用户不需要同时往服务器推数据。

### 这条「没结束的响应」到底是怎么保持的

**保持「打开」的是「这次 HTTP 响应还没结束」，不是 TCP 连接本身。**

一次 HTTP 请求分两阶段：

1. **请求阶段**：Java 发出 POST，Python 收到。
2. **响应阶段**：Python 立刻回响应头 `200 OK` + `Content-Type: text/event-stream`，
   然后**开始往响应体里写内容，写不完就不结束**。

普通接口是「头 + 完整 body 一起发完」。SSE 是「头先发出去，body 像流水一样一点一点来」。
因为响应头里没有 `Content-Length`，传输层改用 **chunked 编码**：
每块数据前面标长度，最后一块长度为 0 表示结束。

准确说法：

> Java 发完请求后，这条**响应**一直是「未完成」状态，Python 生成一段就写一段，
> Java 一直在读；等 Python 的生成器跑完，它关闭响应，Java 的 `Flux` 收到
> `onComplete`，整个调用才结束。

至于 TCP 连接本身是复用还是新建，是传输层细节，不影响这个模型。

### 由此带来的两个实践点

- **谁先断，谁就要有超时**：Java 上设了 `responseTimeout`（等响应头）和连接超时；
  `application.yml` 的 `spring.mvc.async.request-timeout` 是浏览器那条响应的兜底。
- **中间任何一环攒数据，流式就废了**：比如 Nginx 默认 `proxy_buffering on`，
  会把 SSE 攒一波再发。以后部署时要在 Nginx 上关掉它。

---

## 四、Java 怎么把请求发给 Python

**Java 在这里扮演的是「浏览器」的角色** —— 它就是一个 HTTP 客户端，
跟 Postman 发请求没有本质区别。用的是 Spring 的 `WebClient`。

```java
webClient.post()                                        // 发一个 POST
        .uri("/internal/v1/agent/chat")                 // 地址
        .header(InternalTokenFilter.HEADER, token)      // X-Internal-Token 请求头
        .contentType(MediaType.APPLICATION_JSON)        // 我发的是 JSON
        .accept(MediaType.TEXT_EVENT_STREAM)            // 我期望收 SSE
        .bodyValue(new AgentChatRequest(...))           // 请求体
        .retrieve()                                     // 真正发出去
        .bodyToFlux(SSE_TYPE);                          // 把响应体读成「事件流」
```

拆开就是三件事，跟任何 HTTP 请求一样：

| 部分 | 代码 |
|---|---|
| 地址 | `.uri(...)` + `baseUrl` |
| 头部 | `.header(...)` `.contentType(...)` `.accept(...)` |
| body | `.bodyValue(...)` |

### `bodyToFlux` 是关键

它不会「等 Python 全部说完再把结果给我」，而是：
Python 写过来一个事件块，它解析一下，就往 `Flux` 里放一个 `ServerSentEvent`。

```java
Flux<ServerSentEvent<String>>
```

可以理解成**「一个会陆续到达的列表」**：

```
时间 →
[event=message, data="你"] [event=message, data="好"] ... [event=done, data="END"]
```

`retrieve()` 负责 HTTP 状态码（4xx/5xx 抛 `WebClientResponseException`），
`bodyToFlux` 负责把响应体按 SSE 格式一条条解出来。

---

## 五、Python 怎么把输出还给 Java

**不是 Python 反过来调 Java**，而是「Java 打开的那条 HTTP 响应，Python 一直往里写」——
同一个连接的两个方向。

```python
@router.post("/chat", response_class=EventSourceResponse)
async def internal_chat(...):
    service = ChatService(http_request.app.state.agent)
    async for sse in service.internal_chat_stream(request.thread_id, request.message):
        yield sse
```

`yield` 一个，FastAPI 就立刻按 SSE 格式写出去一个，**写出去就 flush**。

```
Python 生成第 1 个字 ──写──> Java 收到 ──写──> 浏览器显示
Python 生成第 2 个字 ──写──> Java 收到 ──写──> 浏览器显示
...
```

整条链路是**两段独立的 HTTP，Java 在中间做接力**：

```
浏览器 ⇄ Java        第一段 HTTP（用户带 JWT）
Java   ⇄ Python      第二段 HTTP（服务带 X-Internal-Token）
```

---

## 六、WebClient 和 Feign 有什么不同

两个东西解决的是同一类需求（服务间调用），但层次和形态差挺多。

### 写代码的样子

**Feign（hmall 那种）：声明式。**

```java
@FeignClient("item-service")
public interface ItemClient {
    @GetMapping("/items/{id}")
    ItemDTO getItem(@PathVariable Long id);
}

// 业务代码里
ItemDTO item = itemClient.getItem(1L);
```

没写地址、没写请求头、没处理响应体，框架全包了。

**WebClient（这里）：编程式。** 地址、头、body、怎么解响应，一条条自己声明。

### 底层对比

| | Feign（hmall） | WebClient（这里） |
|---|---|---|
| **怎么找到对方** | 只写服务名 `item-service`，Nacos 查实例列表 | 直接写地址 `http://127.0.0.1:8001`，没有注册中心 |
| **哪个实例** | LoadBalancer 自动轮询 | 就一个，不用选 |
| **调用模型** | **阻塞**，方法返回时整个响应已经到齐 | **非阻塞**，返回 `Flux`，数据一条条到 |
| **能不能流式** | 基本不行，默认读完响应体才返回 | 天生支持 |
| **依赖** | `spring-cloud-starter-openfeign` + Nacos 客户端 | `spring-boot-starter-webflux` |

### Feign 不是对 WebClient 的封装

两者**平级**，只是抽象层次不同。Feign 底层可替换的传输实现是：

| Feign 的传输实现 | 用的是什么 |
|---|---|
| 默认 | `HttpURLConnection`（JDK 自带，阻塞） |
| Apache | HttpClient（阻塞） |
| OkHttp | OkHttp（阻塞） |

**全是阻塞式客户端**，没有一个用 WebClient。

```
Feign（声明式接口）           WebClient（编程式 API）
        │                              │
        ▼                              ▼
HttpURLConnection / OkHttp      reactor-netty (非阻塞)
   （阻塞，一问一答）              （响应式，可流式）
```

### 为什么这里不用 Feign

1. **Feign 处理不了 SSE**（硬伤）。它的模型是「调方法拿返回值」，
   默认 HTTP 客户端会把响应体完整读进内存。流式输出会退化成
   「转圈等 30 秒然后刷出一整段」。
2. **没有注册中心，也不需要**。这里 Java 和 Python 是两个技术栈、各一个实例，
   地址固定写在配置里就够。为这个搭 Nacos，成本远大于收益
   （对应阶段 2.5「Feign / Nacos / Redis / MQ 不引入」）。
3. **只有一个调用方**。Feign 的价值在于「多服务复用同一套接口定义」，
   Python 那边是 FastAPI，不共享你的 Java 接口定义。

### WebClient 也能干 Feign 的活

```java
Mono<ItemDTO> item = webClient.get()
        .uri("/items/{id}", 1L)
        .retrieve()
        .bodyToMono(ItemDTO.class);
```

「拿一个结果」是「拿一串结果」的特例。WebClient 是更底层的版本，
保留 HTTP 的全部控制权，代价是地址和头要自己写。

### 顺带两个概念

- **`RestClient`**（Spring 6）：`RestTemplate` 的现代替代品，写法像 WebClient，
  但是**阻塞的**。不需要流式时用它更简单、更好调试。
- **HTTP Service Client**（`@HttpExchange`，Spring 6）：真正的「声明式接口 +
  底层 WebClient/RestClient」，思路最接近 Feign，底层可以选响应式。

---

## 七、配置类：`AgentProperties` 是干什么的

它把 `application.yml` 里 `agent:` 下面的几项绑成一个 Java 对象，供 `AgentClient` 使用。

| yml 里的 key | 类里的字段 | 谁在用 |
|---|---|---|
| `agent.base-url` | `baseUrl` | `WebClient.baseUrl(...)`，决定请求发去哪 |
| `agent.response-timeout` | `responseTimeout` | `HttpClient.responseTimeout(...)`，等响应头上限 |
| `agent.connect-timeout` | `connectTimeout` | `ChannelOption.CONNECT_TIMEOUT_MILLIS`，TCP 连接超时 |

### 为什么不用 `@Value`

不用它也能写：

```java
@Value("${agent.base-url}")
private String baseUrl;
```

但问题会随配置变多而放大：

| `@Value` 的问题 | `@ConfigurationProperties` 的解法 |
|---|---|
| 散在各处，想知道有哪些配置要全局搜 | 一个类就是一份配置清单 |
| 全是 `String`，用的时候自己转 | 字段写 `Duration`，`"120s"` 自动转 |
| 没有默认值就启动报错 | 字段直接 `= Duration.ofSeconds(120)` |
| yml 里没有提示 | 有配置元数据，IDE 可补全 |
| 别人复用得再注入一遍 | 它本身是 Bean，谁要谁注入 |

### 三个机制

1. **`@ConfigurationProperties(prefix = "agent")`**：声明字段对应 `agent.*`。
   绑定是「宽松」的，`base-url` / `baseUrl` 都能绑到 `baseUrl`。
2. **`@Component`**：光有注解不会变成 Bean，还要注册。另一种写法是在某个
   `@Configuration` 上加 `@EnableConfigurationProperties(AgentProperties.class)`
   —— 项目里 `JwtProperties` 用的就是那种。
3. **类型转换**：字段声明成 `Duration`，`"120s"` / `"5m"` 都能自动转换。

### 数据怎么流到 AgentClient

```
application.yml 的 agent 段
        ↓  Spring 启动时绑定
AgentProperties 实例（Bean）
        ↓  构造器注入
AgentClient 构造方法参数 properties
        ↓
WebClient 的 baseUrl / responseTimeout / connectTimeout
```

以后换 Python 地址只改 `.env` 里的 `AGENT_BASE_URL`，代码一行不动。

---

## 八、配置类：`config.py` 是干什么的

Python 侧跟 `AgentProperties` 是同一件事：**把外部配置变成有类型、有校验的对象**。

不用它的话，代码里会到处是：

```python
import os
host = os.getenv("DB_HOST")
port = int(os.getenv("DB_PORT", "5432"))
```

问题：配置项散落、全是字符串、没有校验、名字拼错返回 `None` 一路传到最深处才报错。

### 结构

```
.env 文件
   │  pydantic-settings 自动读取
   ▼
config.py
   ├── EnvSettings        ← 基类：指定读哪个 .env
   ├── AppSettings        ← app.*
   ├── DatabaseSettings   ← db.*
   ├── LLMSettings        ← llm.*
   ├── LoggingSettings    ← logging.*
   ├── RAGSettings        ← rag.*
   ├── SecuritySettings   ← security.*（阶段 4 新增）
   └── Settings           ← 把上面装在一起
        │
        ▼
   settings = Settings()   ← 模块级实例，全项目共用
```

用的时候：

```python
from app.core.config import settings
settings.security.internal_token
settings.db.url
settings.app.port
```

### 五个机制

| 机制 | 作用 |
|---|---|
| `EnvSettings.model_config` 里的 `env_file` | 指定去读项目根目录的 `.env` |
| `extra="ignore"` | `.env` 里有、类里没定义的字段忽略，不报错 |
| `Field(alias="APP_PORT")` | 环境变量叫 `APP_PORT`，代码里叫 `settings.app.port` |
| 类型声明（`int` / `Duration` 等） | 自动转换；转不了就在**启动时**报错，不会带病运行 |
| `default_factory=SubSettings` | 用到时才构造，避免所有实例共享一个对象 |
| `@computed_field` | 算出来的配置，例如 `db.url` 由 host/port/密码拼出来 |
| `settings = Settings()` 写在模块级 | import 时就构造，缺必填项直接启动失败 |

### 阶段 4 新增的部分

```python
class SecuritySettings(EnvSettings):
    internal_token: str = Field(alias="INTERNAL_TOKEN")
```

**故意不给默认值**：Java 和 Python 必须用同一个值，给默认值只会掩盖「忘了配」，
最终表现为「所有内部调用 401」，排查起来更费劲。现在缺了它 Python 起不来，一眼能看到。

---

## 九、`INTERNAL_TOKEN` 是什么，Python 怎么校验

### 解决什么问题

阶段 4 之后 Python 多了个接口：

```
POST /internal/v1/agent/chat
```

调一次就跑一遍 Agent、**烧一次大模型的 token**。不加保护的话，
任何能访问 8001 端口的人都能刷你的额度、往任意 `thread_id` 里塞内容。

### 为什么不用用户 JWT

| | 用户 JWT | INTERNAL_TOKEN |
|---|---|---|
| 代表谁 | 某个具体用户 | 「我是服务本身」 |
| 谁签发 | 登录接口运行时签发 | 手工生成，两边各配一份 |
| 有效期 | 24 小时 | 长期，改了才失效 |
| 谁校验 | Java 自己（验签） | Python 比对 |

Java 调 Python 是替**所有用户**跑 Agent，用户身份通过请求体里的 `user_id` 传过去，
而且 Java 已经校验过。Python 也不掌握 JWT 密钥，验不了用户 token。

### Python 的「预先的值」从哪来

**不是「Java 发过来，Python 存下来」，而是两边事先各自配好同一个暗号。**

```
准备阶段（只做一次）
  openssl rand -base64 32  →  aB3xK9...
  写进 Java  .env：INTERNAL_TOKEN=aB3xK9...
  写进 Python.env：INTERNAL_TOKEN=aB3xK9...   ← 手动抄一份

启动时
  Java   读自己的 .env → InternalProperties.token
  Python 读自己的 .env → settings.security.internal_token

请求时
  Java  ：把值放进 X-Internal-Token 头发出去
  Python：收到的值 与 内存里的值 比较，一样放行，不一样 401
```

所以校验是**比对**，不是查询 —— 没有数据库、没有列表，就是一个字符串比另一个字符串。

两边填得不一样时：两边各自都是「配置正确」的，表现是 Java 每次调 Python 都 401，
日志里看到「内部令牌无效」。

### 安全上的几个讲究

| 做法 | 原因 |
|---|---|
| 不进版本库（两个 `.env` 都 gitignore） | 泄露就能绕过 Java 直接调 Agent |
| `hmac.compare_digest`（Python）/ `MessageDigest.isEqual`（Java） | 常量时间比较，避免靠响应时间逐位猜 |
| 不跟 `JWT_SECRET` 共用 | 用途不同，泄露一个不影响另一个，也便于定位换哪个 |
| 不只靠内网隔离 | 内网其他机器、误映射端口都会让「只靠内网」失效 |

### 一个可以改进的点

现在两个方向（Java→Python、Python→Java）共用同一个令牌，泄露任意一侧影响两段。
更严格的做法是拆成 `JAVA_CALL_PY_TOKEN` 和 `PY_CALL_JAVA_TOKEN`。
现在只有两个服务、三个接口、全内网，共用够用，等上生产再说。

---

## 十、为什么 `service.py` 还保留公开入口

因为**阶段 4 只是新增了 Java 这条路，老路还没拆**。

```
浏览器 ──/api/v1/chat──> Python :8001
             x-user-id       ↓
                        router.py → chat_stream()      ← 老路，前端还在用

Java   ──/internal/v1/agent/chat──> Python :8001
             X-Internal-Token      ↓
                        internal_router.py → internal_chat_stream()   ← 阶段 4 新增
```

前端要到**阶段 6** 才切到 8080。如果现在删掉公开接口，前端立刻不能用。

这也是 MIGRATION 开头定的原则：**增量拆分，每一步都能跑通，随时可回退**。
三处同时改（Java 加接口 + Python 删接口 + 前端改地址）出问题时，无法定位是哪一段。

| 阶段 | 做什么 | 出问题时看哪 |
|---|---|---|
| 4（现在） | Java 加新路，Python 加内部接口 | 只验证 Java → Python |
| 5 | Python 删业务模块，公开路由改内部 | Python 变瘦后是否正常 |
| 6 | 前端切 8080 | 前端 → Java 这一段 |

两个入口共用 `_stream`，所以过渡期内两条路跑出来的流式效果完全一样，
差异只在第一步：要不要查 Python 自己的会话表。

阶段 5 删掉 `chat_threads` 模块后，`chat_stream` 会跟着消失，
`service.py` 里只剩 `internal_chat_stream` 和 `_stream`。

---

## 十一、为什么单独写 `internal_router.py`

技术上可以写在一个文件里（两个 `APIRouter` 对象），但分开有三个实际收益：

**1. 两个接口的「门」完全不同。**

```python
# 老路：相信前端传的 x-user-id
# 新路：dependencies=[Depends(verify_internal_token)]
```

如果写在一起并给 router 挂 `dependencies`，很容易把内部令牌的要求
误加到公开接口上，浏览器直接 401。

**2. 依赖的数据库会话不同。** 老路要 `get_session` 查会话；新路不查库，不需要 session。

**3. 阶段 5 要删掉一半。** 分开写的话阶段 5 是「整个 `router.py` 删掉」；
写在一起就得在一个文件里挑着删，容易误删。

另外跟 Java 侧保持一致：`ProductController` 和 `InternalProductController` 也是分开的，
看到 `internal_*` 就知道「这不是给浏览器的」。

---

## 十二、Python 侧代码走读

### `chat/schema.py`

```python
class InternalChatRequest(BaseSchema):
    user_id: int
    thread_id: UUID
    message: str = Field(min_length=1)
```

- 字段是 **snake_case**，对应 Java 的 `@JsonProperty("user_id")`
- `thread_id: UUID` 让 Pydantic 自动解析并校验格式，不对直接 422
- `min_length=1` 是第二层校验：Java 校验过一次，但内部接口不该假设调用方守规矩

### `chat/service.py`

三层：

| 方法 | 谁调 | 是否查会话表 |
|---|---|---|
| `chat_stream` | 浏览器（老路） | ✅ |
| `internal_chat_stream` | Java（新路） | ❌ Java 已校验 |
| `_stream` | 上面两个共用 | —— |

`_stream` 里是原样保留的 Agent 逻辑：`astream_events` → 解析事件 → `yield` SSE → `done`。

### `chat/router.py`

只改了 `ChatService` 的构造方式（`(session, agent)` → `(agent, repository)`），其余不动。

### `chat/internal_router.py`

**最容易踩的坑：鉴权必须写成 `Depends`，不能写在处理函数里。**

```python
@router.post("/chat", response_class=EventSourceResponse,
             dependencies=[Depends(verify_internal_token)])
async def internal_chat(...):
    ...
    yield sse
```

带 `yield` 的函数是**异步生成器**，函数体要等第一次迭代才执行。
而第一次 `yield` 时，`EventSourceResponse` 已经把 200 响应头发给 Java 了。
这时候再抛异常，调用方收到的是一个 200 的流里面突然出错 —— 鉴权形同虚设。

写成依赖后，FastAPI 在**调用处理函数之前**就把令牌验完，失败直接返回 401。

### `main.py`

只多两行：import 路由 + `app.include_router(internal_agent_router)`。

### 完整调用栈

```
POST /internal/v1/agent/chat
  X-Internal-Token: xxx
  {"user_id":12,"thread_id":"...","message":"你好"}
        ↓
① 路由匹配 → internal_chat
② 解析请求体 → InternalChatRequest
③ 执行依赖 verify_internal_token → 失败 401，成功继续
④ ChatService(agent) → internal_chat_stream
⑤ _stream → astream_events → 模型逐字生成
⑥ yield ServerSentEvent(data="你", event="message")
⑦ EventSourceResponse 立刻写成 "event: message\ndata: 你\n\n" 并 flush
⑧ Java 的 Flux 收到一条，转给浏览器，回到 ⑥
⑨ 生成完 → yield event="done" → 生成器结束 → 响应关闭
```

---

## 十三、验收时的注意点

| 观察点 | 说明 |
|---|---|
| 启动报 `No qualifying bean of type 'WebClient$Builder'` | Maven 没刷新，`spring-boot-starter-webflux` 没进 classpath |
| Python 启动报 `INTERNAL_TOKEN` 校验失败 | `.env` 没配，或者两边值不一致 |
| 第 3 项收到的不是流式而是一整段 | 看响应头 `Content-Type` 是不是 `text/event-stream` |
| 第 2 项返回 406 而不是 404 | 见下面的「已知瑕疵」 |

### 已知瑕疵：`spring.mvc.async.request-timeout` 对这条路径不生效

Spring 6.2 的 `ReactiveTypeHandler` 给 `Flux<ServerSentEvent>` 创建 `SseEmitter` 时
写死了 `-1`（永不超时），不读这个配置。留着无害，但不要以为是它保住了长连接。
真正起作用的是 `AgentClient` 里的 `responseTimeout(120s)`（只管到收到响应头为止）。

### 已知风险：严格 `Accept` 下 404 可能变 406

`@PostMapping(produces = TEXT_EVENT_STREAM_VALUE)` 限制了响应类型。
前端现在用 `fetch` 且没设 `Accept`（默认 `*/*`），404 能正常返回 JSON。
如果改用 `EventSource` 或显式写 `Accept: text/event-stream`，异常处理器返回 JSON 时
可能因类型不匹配报 406。

### 前端要补 `error` 事件的处理

`src/api.ts` 的 `handleSseEvent` 只认 `message` / `interrupt` / `additional_info`，
`event: error` 会被直接丢掉 —— Python 挂了用户看到的是「没有任何反应」。
阶段 6 改前端时记得加分支。

---

## 十四、几个容易踩的坑（都在代码里处理了）

| 坑 | 为什么 | 代码里的处理 |
|---|---|---|
| `UserContext` 在 `Flux` 里读不到 | 它读 `SecurityContextHolder`，本质是 ThreadLocal，进了订阅线程就没了 | 在 `return` 之前把 `userId` 取出来，传参进去 |
| 用 `.bodyToFlux(ServerSentEvent.class)` 丢泛型 | `data` 会变成 `Object` | 用 `ParameterizedTypeReference<ServerSentEvent<String>>` |
| 异常在订阅时才炸 | `WebClient` 是懒的，`.retrieve()` 的错误不在方法调用时抛 | `.onErrorResume(...)` 转成一条 `event: error` |
| 非法会话先建流再断 | 建流属于响应阶段，状态码已经发出去了 | 归属校验放在建流之前，抛 `NotFoundException` → 404 |
| Python 侧 401 变成 200 | 生成器里的异常在响应头发出之后才发生 | 令牌校验写成 FastAPI 依赖，在开始推流前执行 |
| 前端 `curl` 看不出流式 | 终端/客户端缓冲 | 用 `curl -N` 关掉缓冲 |
| Nginx 部署后不流式 | `proxy_buffering on` 默认开启 | 部署时关掉 |

---

## 十五、术语速查

| 术语 | 一句话解释 |
|---|---|
| **SSE** | Server-Sent Events，服务端往浏览器单向推送的文本流，长连接 |
| **透传** | 不解析、不改写，收到什么转什么 |
| **Flux** | Reactor 的「0~N 个元素的异步序列」，可以陆续到达 |
| **Mono** | Reactor 的「0~1 个元素」，可以理解为 `Flux` 的特例 |
| **背压** | 消费方告诉生产方「慢点，我跟不上」的机制 |
| **X-Internal-Token** | 服务间共享密钥，调用方是 Python 还是 Java 互相认，不认用户 JWT |
| **JWT** | 用户身份令牌，`Authorization: Bearer <token>` |
| **chunked 编码** | 没有 `Content-Length` 时，分块传输响应的方式 |
| **WebClient** | Spring 的非阻塞 HTTP 客户端 |
| **Feign** | Spring Cloud 的声明式 HTTP 客户端，底层阻塞 |

---

*最后更新：2026-11-21*
