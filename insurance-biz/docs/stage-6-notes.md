# 阶段 6 笔记：前后端联调与 Java 基础概念

> 记录 2026-11-22 做阶段 6 时的问答，方便以后回顾。
> 涉及的代码见 `client/AgentClient.java`、`controller/ChatThreadController.java`、
> `frontend/src/api.ts`，Python 侧见 `agent-service/app/modules/chat/`。

---

## 一、阶段 6 是做什么的

### 目标

让前端**只认识 Java 一个后端**，同时把身份凭证从"前端自报"换成真 JWT。

**改造前：**

```
浏览器 ──x-user-id: 12──> Python :8001     （假登录，谁都能冒充）
```

**改造后：**

```
浏览器 ──Authorization: Bearer <token>──> Java :8080
                                             │ 校验 JWT、校验会话归属
                                             └──> Python :8001（只收 X-Internal-Token）
```

### 做完之后多了什么

1. 登录是真的：前端调 `/api/v1/auth/login`，拿到 JWT 存起来。
2. 会话历史由 Java 转发：浏览器不直连 Python。
3. Java 的 `R<T>` 外壳在前端统一拆掉，业务代码不用关心外层格式。
4. 删除会话时 Java 会顺手清掉 Python 侧的 checkpoint。

---

## 二、历史接口：点左侧会话，右侧显示聊天

### 前端链路

```
点击左侧会话
  → AdvisorPage.openThread(threadId)
  → loadMessages(threadId)
  → getChatHistory()        →  GET /api/v1/chat-threads/{id}/messages
  → setMessages(history.messages)
  → 右侧聊天区重新渲染
```

### 改造前 Python 是怎么做的

老代码在 `app/modules/chat_thread/`，三层：

```python
# 1. 路由层
@router.get("/{thread_id}/messages", response_model=ChatHistoryResponse)
async def rename(thread_id: str, user_id: int = Header(alias="x-user-id"), ...):
    return await service.get_chat_history(user_id, thread_id)

# 2. 业务层
async def get_chat_history(self, user_id, thread_id):
    chat_thread = await self.repository.required_owner(user_id, thread_id)
    if not chat_thread:
        raise ChatThreadNotFound()

    config = {"configurable": {"thread_id": thread_id}}
    state_snapshot = await self.agent.aget_state(config)

    messages = []
    for message in state_snapshot.values.get("messages", []):
        if isinstance(message, HumanMessage):
            role = "user"
        elif isinstance(message, AIMessage):
            role = "assistant"
        else:
            continue
        if message.text:
            messages.append(ChatMessageResponse(role=role, content=message.text))
    return ChatHistoryResponse(thread_id=thread_id, messages=messages)

# 3. 仓储层：SQL 同时带 id 和 user_id
async def required_owner(self, user_id, id):
    return await self.session.scalar(
        select(ChatThread).where(ChatThread.id == id, ChatThread.user_id == user_id)
    )
```

三个要点：

1. **归属校验和现在 Java 里一模一样**：SQL 同时带 `id` 和 `user_id`，
   查不到就是 404，不先查出来再比较（那样会泄露"这个 id 存在"）。
2. **`agent.aget_state()` 不是查业务表**，是让 LangGraph 的 checkpointer
   把快照反序列化成消息对象。
3. **消息正文从来没进过业务表**：`chat_threads` 只有
   `id / user_id / title / created_at / updated_at`，对话内容全在 LangGraph
   的 `checkpoints`、`checkpoint_writes`、`checkpoint_blobs` 三张表里。

### 改造后的差别

| 环节 | 老 Python | 现在 |
|---|---|---|
| 身份 | `x-user-id` | JWT |
| 会话列表 | Python 查 PG | Java 查 MySQL |
| 归属校验 | Python 查 PG | Java 查 MySQL，**在调 Python 之前** |
| 读历史 | `aget_state()` | Java 转调，Python 仍然 `aget_state()` |
| 返回格式 | `{thread_id, messages}` | 同左，外面包 `R<T>` |

**读 checkpoint 这一步变不了**，只有 Python 的 checkpointer 能正确反序列化；
Java 负责的是"谁有资格看"和"对外的门面"。

---

## 三、被追问出来的一个老 bug

之前有一次看到"历史只有用户消息"，结论是节点返回的 key 写错：

```python
return {"message": [response]}   # 单数
```

而状态声明的通道是 `messages`，多出来的 key 会被 LangGraph **静默丢弃**。

但这里有个需要修正的细节：**受影响的范围没有最初说的那么大**。

| 路径 | 修复前是否存 AI 回复 |
|---|---|
| 产品咨询（`recommendation_plan` 子图） | ✅ 存。它是 `create_agent` 编出来的子图，内部写 `messages` 正确，跑完会合并回父状态 |
| 闲聊（`chitchat` 自定义节点） | ❌ 只存用户消息 |
| 理赔 / 转人工 / fallback | ❌ 同上 |

所以用户平时咨询产品时，历史里一直是有 AI 回复的；只有闲聊和另外几个
自定义节点回复会丢。验收时第一句话问的是 `hello, who are you`，正好走闲聊，
才把问题暴露出来。修复后所有路径统一写 `messages`。

---

## 四、record 是什么，为什么 `ChatMessageVO` 里什么都没写

```java
public record ChatMessageVO(String role, String content) {
}
```

**大括号空的不是漏写**——record 的全部内容就在括号里。编译器会生成：

- 两个 `private final` 字段
- 全参构造器
- 读取方法 `role()` / `content()`（注意不是 `getRole()`）
- `equals` / `hashCode` / `toString`

### 和 Lombok `@Data` 的区别

| | `record` | `@Data` |
|---|---|---|
| 字段 | 写在括号里 | 自己写 |
| 可变性 | 不可变，无 setter | 可变 |
| 读取方法 | `role()` | `getRole()` |
| 构造 | 自动全参 | 默认无参，要 `@AllArgsConstructor` |
| JDK | 16+ | 任意（需 Lombok） |
| 继承 | 不能继承（final） | 可以 |

### 为什么以前没见过

record 要 Java 16+。系统 `JAVA_HOME` 是 JDK 8，hmall 是 JDK 11，都没有；
`insurance-biz` 用 JDK 21 编译，所以能用。老教程基本都用 Lombok `@Data`。

### 什么时候用

| 场景 | 选择 |
|---|---|
| VO / DTO / 传值对象 | ✅ record |
| MyBatis-Plus 实体 | ❌ 必须无参构造 + setter |
| 需要在 Spring 容器里当 Bean | ❌ 一般用普通类 |
| 需要继承 | ❌ |

同一个项目里 `ProductVO`（`@Data class`）和 `ChatThreadVO`（`record`）并存，
不是刻意混搭，是不同的代码时代：前者是阶段 1–3 照 Lombok 习惯写的，
后者是阶段 6 新写的。

record 里还能加东西：紧凑构造器做校验、静态工厂（`ChatThreadVO.from()`）、
派生方法；**不能**加额外的实例字段。

---

## 五、`client` 层相当于什么层

`client` 不是一个业务层，是**出站适配层**：专门放"我要去找别人要数据"的代码。

```
controller/   入站适配：HTTP → Java 对象
service/      业务逻辑：该不该做、怎么做
domain/       数据模型：po / dto / vo
mapper/       出站适配：Java ↔ MySQL
client/       出站适配：Java ↔ 另一个服务的 HTTP
```

`mapper` 和 `client` 是对称的：一个找数据库，一个找远程服务。

### client 该放什么

| ✅ 该放 | ❌ 不该放 |
|---|---|
| URL、HTTP 方法、请求头 | 会话归属校验 |
| 令牌、重试、超时 | 业务规则 |
| JSON ↔ Java 对象 | 事务边界 |
| 把下游错误翻译成本地语义（503） | 决定功能要不要做 |

六边形架构里，`controller` 是入站适配器，`client` / `mapper` 是出站适配器，
`service` 是核心。`client` 在不同公司也叫 `infra`、`gateway`、`remote`、
`adapter.out`。

### 项目里一处"不标准"的调用

```java
// 流式：controller 直接调 client（中间没有业务逻辑，硬加一层是纯转发）
return agentClient.streamChat(dto.threadId(), userId, dto.message());

// 历史：controller → service 校验归属 → client
chatThreadService.assertOwned(id);
return R.ok(agentClient.chatHistory(id));
```

判断标准不是"必须三层"，而是**这一层有没有自己的职责**。

---

## 六、同步与异步在代码里怎么体现

两者都基于同一个 `WebClient`。WebClient 是惰性的：不订阅就不发请求。
区别只在订阅方式。

### 同步：`chatHistory`

```java
public ChatHistoryVO chatHistory(String threadId) {   // ③ 返回具体对象
    try {
        return webClient.get()...
                .bodyToMono(ChatHistoryVO.class)
                .block(SYNC_TIMEOUT);                  // ① 阻塞等待
    } catch (Exception ex) {
        throw new CommonException("...", 503);          // ② 异常就地抛出
    }
}
```

- `.block()`：订阅并让**当前 Tomcat 线程**停在这里等结果
- 同步调用会把异常重新抛出来，所以 `catch` 能接住
- 返回类型是 `ChatHistoryVO` 而不是 `Mono<>`，签名就是对外的证据

### 异步：`deleteThread`

```java
public void deleteThread(String threadId) {            // ② 返回 void
    webClient.delete()...
            .doOnError(ex -> log.warn("..."))          // ③ 错误只能进日志
            .subscribe();                              // ① 订阅后立刻返回
}
```

- `.subscribe()`：把流水线交给 Reactor 的 IO 线程，方法马上返回
- 没有调用方在等结果，异常无处可抛，只能记日志
- 返回 `void`

### 时间线对比（假设 Python 响应 200ms）

```
同步：Tomcat 线程 ──block()──▶ 等 200ms ──▶ 拿到结果 ──▶ 返回前端
异步：Tomcat 线程 ──subscribe()──▶ 立刻返回 R.ok()
                      └──▶ Reactor 线程：后台发请求，200ms 后结束，失败只记日志
```

### 失败表现

| | 同步 | 异步 |
|---|---|---|
| Python 挂了 | 前端收到 503 | 前端收到 200，后台一条 warn |
| 用户感知 | 历史加载失败，可以重试 | 删除成功（MySQL 确实删了） |

原则：**结果要立刻用的用 `block()`，只是顺带完成的用 `subscribe()`。**
删除不能反过来——先删 MySQL 再清 checkpoint，失败留下的是不可见的脏数据；
反过来会导致"界面还在、数据没了"。

---

## 七、Reactor 线程是什么

"Reactor 线程" = Reactor Netty 启动的**事件循环线程池**，日志里叫
`reactor-http-nio-1`、`reactor-http-nio-2`……

### 名字拆开

- **Reactor**：Spring 的响应式库，提供 `Mono` / `Flux`
- **Reactor Netty**：`WebClient` 底层默认的 HTTP 客户端实现
- **nio**：Java 非阻塞 IO

链路：`WebClient → Reactor Netty → Netty → socket`

### 为什么要它

| 模型 | 比喻 |
|---|---|
| Tomcat：一请求一线程 | 一桌一个服务员，客人点完菜就站旁边等 |
| Reactor：事件循环 | 一个服务员管二十桌，谁举手去谁那儿 |

线程数：Tomcat 默认 200，Reactor 默认 CPU 核数（最少 4）。
可以用 `-Dreactor.netty.ioWorkerCount=8` 调。

### 在 `AgentClient` 里发生什么

```
同步：tomcat-handler-3 ──block()──▶ 挂起
      reactor-http-nio-2 ──────────▶ 真正发请求、反序列化
      tomcat-handler-3 ◀─────────── 被唤醒

异步：tomcat-handler-3 ──subscribe()──▶ 立刻返回
      reactor-http-nio-4 ──────────▶ 后台执行
```

`block()` 跨线程工作的原理就是 `subscribe() + CountDownLatch.await() + get()`。

**Reactor 线程里绝对不能 `block()`**：4 个线程管所有连接，卡住一个就卡一批。
写错会看到：

```
IllegalStateException: block()/blockFirst()/blockLast() are blocking,
which is not supported in thread reactor-http-nio-2
```

验证线程名，可在 `chatHistory` 三段各加一行日志：
入口是 `tomcat-*`，中间 `doOnNext` 里是 `reactor-http-nio-*`，回到调用方又是 `tomcat-*`。

---

## 八、字段名为什么是 snake_case，yml 不能自动识别驼峰吗

能配置，但要先分清**三层名字转换**：

| 层 | 转换 | 配置项 | 本项目 |
|---|---|---|---|
| 数据库 ↔ 实体 | `clause_name` ↔ `clauseName` | `map-underscore-to-camel-case` | 已开 |
| JSON ↔ Java 对象 | 同上 | `spring.jackson.property-naming-strategy` | 本轮决定 |
| 请求参数 | 查询串 ↔ 方法参数 | `@RequestParam` | 用不到 |

平时说的"自动识别驼峰"通常是第一层（MyBatis-Plus），它只管数据库和实体之间，
不影响 JSON。

### 两个容易混的配置

- **强制转换** `property-naming-strategy: SNAKE_CASE`：
  输出一律改名，输入**只认**下划线写法
- **忽略大小写** `accept-case-insensitive-properties: true`：
  `ClauseName` 能匹配 `clauseName`，但 `clause_name` 多了一个下划线，**依旧匹配不上**

### 为什么不用全局配置

全局是双向的，会打中 Python 回调 Java 的内部接口：

```python
payload = {"premiumMin": "...", "limitPerCategory": 2}   # 驼峰
```

开了全局 SNAKE_CASE 后，Jackson 只认 `premium_min`，而 `premiumMin` 不报错，
直接当 null —— 用户说"预算 5000 以内"，筛选静默失效。这类 bug 最难查。

### 三个方案

| 方案 | 改动量 | 风险 |
|---|---|---|
| 全局 SNAKE_CASE | yml 一行 | 内部接口静默坏，必须给内部 DTO 逐个加 `@JsonProperty` |
| 对外 VO 打 `@JsonNaming`（本轮选的） | 5 个 VO 各一行 | 新加 VO 容易忘 |
| 改前端为驼峰 | 前端改动面大 | 破坏既有契约和嵌套结构 |

选择原则：**边界上迁就调用方，内部保持自己的习惯**。前端字段名是照老 Python
写的，是已经定下来的契约；Java 是新来的一侧，应该迁就它。
`ChatRequestDTO` 的 `threadId` 也加了 `@JsonProperty("thread_id")`，前端请求体不用改。

---

## 九、前端这一轮改了什么

### 1. 拆 `R<T>` 外壳

Java 所有接口返回 `{code, msg, data}`，前端在 `api.ts` 统一拆：

```ts
if (typeof body.code === 'number' && 'data' in body) {
  if (body.code !== 200) throw new ApiError(body.msg, body.code);
  return body.data;
}
```

### 2. 真登录 + Bearer

- `auth.ts` 删掉 `DEFAULT_SESSION`，存储键升到 v3（旧缓存没有 token，必须作废）
- `AuthModal` 提交 `/api/v1/auth/login`，失败显示错误
- 所有需要登录的请求带 `Authorization: Bearer <token>`
- 没有 token 直接抛 401，页面统一走 `onSessionExpired`

### 3. 处理 `event: error`

Java 在 Python 不可用时推一条 `event:error`。以前的 `handleSseEvent` 没处理，
前端只看到气泡一直空着。现在转给 `onError`，显示提示并移除空气泡。

---

## 十、验收结论

见 `MIGRATION.md` 阶段 6 的验收表。一句话：

**登录（JWT）→ 建会话（MySQL）→ 对话（Java 透传 SSE）→ 查历史（Java 转调 Python）
→ 删除（Java 删 MySQL + 异步清 checkpoint）整条链路跑通，前端不再直连 Python。**
