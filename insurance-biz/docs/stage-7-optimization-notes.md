# 优化阶段笔记：从"能跑"到"能讲"

> 记录优化阶段的五个核心项（单测、traceId、方案确认、降级链、Query 改写 + 评测），
> 以及过程中被追问出来的问题、踩到的坑、部署复验结果。
> 结构：先列做了什么，再按「问题 → 回答」展开。

---

## 一、这个阶段做了什么

| 项 | 一句话 | 关键文件 |
|---|---|---|
| 单元测试 | Java 侧只补三个核心类，Python 侧 13 个用例 | `JwtToolTest`、`TraceIdFilterTest`、`ChatThreadServiceImplTest`、`agent-service/tests/` |
| 引用来源 | 回答里的 `[1]` 能点开看到条款原文 | `app/agents/citations.py`、`app/agents/tools.py` |
| 方案人工确认 | 推荐完先弹方案卡，用户点确认才继续 | `app/agents/plan.py`、`app/agents/orchestrator.py` |
| 模型降级链 | 主模型挂了自动切备用，再挂转人工 | `app/agents/models.py`、`app/agents/intent_router.py` |
| Query 改写 + 评测 | 先量化多轮检索差多少，再决定怎么修 | `evals/`、`scripts/eval_retrieval.py` |

---

## 二、单元测试有必要吗？

### 问题

> "这个单元测试有必要吗，之前我们做这些的时候，不都测试跑通了吗？"

### 回答

跑通 ≠ 有测试。手动跑一遍只能证明"这条路径此刻是好的"，下次改动它不会提醒你。

但也不必追求全覆盖。判断标准是：**这段逻辑出错时，能不能只靠眼睛看出来。**

按这个标准，Java 侧挑了三类：

1. `JwtTool`：签发/解析/过期/伪造签名/空密钥——纯计算，边界多，出错只表现为"线上偶发 401"。
2. `TraceIdFilter`：生成规则、上游透传、MDC 清理——纯逻辑。
3. `ChatThreadServiceImpl`：归属校验、标题生成、删除分支——分支多但都走 mock。

Controller 和 MyBatis 真库的部分没做：起 Spring 上下文 + Testcontainers 成本远超收益，
这部分靠阶段 6 的联调验收覆盖。

### 踩到的三个坑（MyBatis-Plus 3.5.9）

**坑 1：`updateById(any())` 爆红**

`BaseMapper.updateById` 有两个重载：`updateById(T)` 和 `updateById(Collection<T>)`。
`any()` 推不出是哪个，必须写类型：

```java
verify(mapper).updateById(any(ChatThread.class));
```

**坑 2：`Unable to get MybatisMapperProxy`**

`lambdaQuery()` 在 mock 上会去拿真实代理。3.5.9 改成从代理推导实体类，mock 拿不到。
解法是匿名子类硬编码实体类：

```java
ChatThreadServiceImpl service = new ChatThreadServiceImpl(mapper) {
    @Override
    protected Class<ChatThread> getEntityClass() {
        return ChatThread.class;
    }
};
```

**坑 3：`can not find lambda cache`**

`lambdaQuery().eq(ChatThread::getId, ...)` 生成 SQL 片段时要查 `TableInfo` 缓存，
纯单测里没有 Spring 扫描，缓存是空的。手动初始化一次：

```java
@BeforeAll
static void initTableInfo() {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(
        new MybatisConfiguration(), ""), ChatThread.class);
}
```

---

## 三、Docker 化是什么意思，为什么现在做

### 问题

> "这个项目不是还没改造完，直接 docker 化会不会太急了？"

### 回答

Docker 化不依赖功能完成度，它依赖的是"依赖清单稳定"——三个阶段改造后，
Java 只连 MySQL、Python 只连 Postgres + Milvus + Java，依赖已经定型，可以固化了。

三个镜像都是多阶段构建：

| 服务 | 构建 | 运行 | 镜像大小 |
|---|---|---|---|
| backend | Maven 编译 | `eclipse-temurin:21-jre` | 387MB |
| agent | uv 装依赖 | `python:3.14-slim` | 919MB |
| frontend | Vite 打包 | Nginx | 48.9MB |

**一个容易踩的点**：Windows 上没有 Docker，所有容器只在 VMware 虚拟机跑。
"项目里怎么没有 docker 命令"不是环境坏了，是本来就没有。

**网络分叉**：中间件（postgres/milvus/minio/etcd）在 `deploy_heima-net`，
应用在 `deploy_insurance-net`，两个网络不通。解法：

```bash
docker network connect --alias postgres deploy_insurance-net hm-postgres
```

`--alias` 必须显式给，否则只有容器名能解析，compose 里写的服务名 `postgres` 解析不了。

**2 核 3.7G 的机器不要并行 build**，三个镜像一个一个来，否则 OOM。

---

## 四、traceId：一次请求穿过三个进程

### 问题

> "traceId 是干嘛的？"

### 回答

一次对话会穿过：浏览器 → Java(8080) → Python(8001) → Java 内部接口，再回来。
出问题时三个进程各有各的日志，时间戳对不上就串不起来。

做法是入口生成一个 16 位十六进制 ID：

1. Java `TraceIdFilter`（`HIGHEST_PRECEDENCE`，比 Security 还早）生成或沿用 `X-Trace-Id`，
   写进 MDC，同时回写响应头；
2. 调 Python 时放在请求头里带过去；
3. Python `TraceIdMiddleware` 读同名头，绑定到 structlog 上下文，所有日志自动带 `trace_id=`；
4. Python 回调 Java 内部接口时再带回去；
5. 前端收到错误时，从响应头取 traceId 显示成「错误编号 a1b2c3d4e5f6」，用户截图发过来就能 grep。

### 为什么 Python 不用 `BaseHTTPMiddleware`

`BaseHTTPMiddleware` 会把请求处理丢到另一个 task 里，`contextvars` 不跟着走。
SSE 这种流式响应期间，日志里的 trace_id 会丢。所以用纯 ASGI 中间件，直接包在 `app` 外层。

### 验收证据（VM 实测）

- 浏览器点开一个已删除的会话 → 提示「会话不存在（错误编号 f9abc668275e45ac）」
- `docker logs hm-backend | grep f9abc668275e45ac` → 命中同一条业务异常
- 带 `X-Trace-Id: tracee2e0002abcd` 发起对话 → hm-agent 的「条款检索」日志同一串 ID

---

## 五、引用来源是怎么设计的

### 问题

> "引用信息这块，前端那些 `[1]`、`[2]` 是怎么出来的？"

### 回答

链路是这样的：

1. 工具 `retrieve_insurance_clause` 检索完，给每个父块编号 `S1`、`S2`…，登记到 ContextVar，
   返回给模型的 JSON 里只保留 `source_id / section_path / content`（UUID、score 不给模型，省 token）；
2. 系统提示词要求模型在引用句末尾标 `[S1]`；
3. 流结束时，`ChatService` 把 ContextVar 里的来源打包成 `event: additional_info`
   推给前端，然后写回 checkpoint 里那条 AI 消息的 `additional_kwargs.additional_info`；
4. 历史接口从 checkpoint 回放，所以刷新页面引用还在。

### 踩到的两个坑

**坑 1：`fastapi.sse` 的 `data` 永远 JSON 序列化**

传 dict 得到 `{"type":"clause_sources",...}`，传 str 得到 `"..."`（带引号）。
前端解析的时候要按 JSON 处理，不能假设是裸字符串。

**坑 2：拿不到完整的 `AIMessage`**

`create_agent` 子图流出来的基本都是 dict delta（`{"delta": {"text": "..."}}`），
没有完整消息可用于写回。所以改成**流结束后回读 checkpoint**，找最后一条人类消息之后的
最后一条 AI 消息，给它补上 `additional_info`。

---

## 六、ContextVar 是什么

### 问题

> "citations 和 plan 都用了 ContextVar，为什么不直接用一个模块级 list？"

### 回答

`asyncio` 里一个线程同时跑很多协程（很多用户同时对话）。模块级 list 是所有请求共享的，
A 用户的引用会串到 B 用户的气泡里。

`ContextVar` 是"随协程上下文走的变量"：每个请求（LangGraph 的每次运行）有自己的副本，
子协程能继承到，但不会影响别的请求。用法固定三步：

```python
_citations: ContextVar[list[str]] = ContextVar("citations", default=[])

def reset():   _citations.set([])      # 每次请求开始清空
def record(x): _citations.get().append(x)  # 工具里登记
def current(): return _citations.get()     # 流结束时读取
```

注意 `default=[]` 是共享的可变对象，所以**每个请求进来必须先 `reset()`**。
`ChatService._stream` 开头的 `reset_citations()` / `reset_plan_products()` 就是干这个的。

---

## 七、方案人工确认：为什么 `pending_plan` 必须放 state

### 问题

> "方案确认的具体效果是什么样的？"

### 回答

推荐节点跑完后，`prepare_plan` 把候选产品整理成方案写进 state，`confirm_plan` 调
`interrupt(payload)` 暂停图，前端收到 `event: interrupt` 渲染方案卡片（确认 / 继续调整）。

用户点按钮时前端发 `{"thread_id": ..., "decision": {"action": "approve"}}`，
Python 用 `Command(resume=decision)` 恢复，`confirm_plan` 返回一条 AIMessage（"方案已确认…"）。

**关键设计**：方案数据必须放 state（`pending_plan`），不能只放 ContextVar。
LangGraph 恢复中断时会**重新执行这个节点**，此时 ContextVar 已经被上次请求清空了，
只有 state 里的数据还在。

前端配套：`pendingInterrupt !== null` 时输入框和发送按钮禁用，避免用户绕过确认直接问下一句。

---

## 八、Query 改写 + 评测：为什么要先测再做

### 问题

> "Query 改写 + 评测对比是干什么的？为什么还要做这个？"

### 回答

一句话：**证明多轮对话里检索确实变差了，再决定花力气在哪。**

做法是先造 12 组「上文 + 半句追问 + 期望章节」的多轮样本，跑同一个检索器：

| 配置 | recall@1 | recall@3 | recall@5 | MRR |
|---|---|---|---|---|
| 追问原话直接检索 | 0.167 | 0.250 | **0.333** | 0.215 |
| 上文 + 追问拼接（近似改写） | 0.583 | 0.750 | **0.833** | 0.656 |

结论：半句追问的召回只有单轮的三分之一。

但继续往下查发现，**真正卡住的不是检索器，是工具调用**——模型传 `product_id=0`、
query 只有半句。所以最后没加改写模型，而是：

- 系统提示词要求"先调 `query_candidate_products` 拿 id、多轮把追问补全成完整问题"；
- 工具对 `product_id <= 0` 直接返回提示，让模型自查。

复测两轮对话，query 变成「…重疾险犹豫期内退保和犹豫期后退保能退还多少保费」，引用正常带出。

### 评测集本身的偏差（面试会被问）

问题是从条款片段**反向生成**的，天然贴近原文语义，会高估真实效果。
缓解办法是从真实对话里捞 15~20 条问法做校准集，目前还没做，属于已知短板。
另外样本 60 条，单条影响 1.7 个百分点，差异小于 2 个点当噪声看。

---

## 九、模型降级链

### 问题 1

> "为什么 `models.py` 里有**两个** `wrap_model_call`？"

### 回答

一个是同步版 `wrap_model_call`，一个是异步版 `awrap_model_call`。

LangGraph 在 async 场景（`await agent.astream_events`）里走的是异步钩子，
如果只实现同步版，异步调用根本不会经过它——降级逻辑等于没写。
两个都实现，谁被调用取决于运行时怎么调。

### 问题 2

> "主模型失败后怎么自动切到备用模型？"

### 回答

分两种场景，因为框架限制不同：

**普通节点**（闲聊、意图识别）——模型是 `Runnable`，用框架自带的 `with_fallbacks`：

```python
primary.with_fallbacks([fallback])
```

主模型抛异常（含超时）就自动执行下一个。

**`create_agent` 子图**（保险顾问）——它只接受 `BaseChatModel`，没有 `with_fallbacks` 可用。
所以在 middleware 里换模型重试：

```python
def wrap_model_call(self, request, handler):
    try:
        return handler(request)                       # 先用主模型跑
    except Exception as exc:
        logger.warning("主模型调用失败，切换备用模型", reason=str(exc))
        return handler(request.override(model=self._fallback))  # 换成备用模型再跑
```

`handler` 是框架传进来的"下一步执行器"，`request.override(model=...)` 是换模型的标准写法——
框架会自己给新模型重新绑定工具，不用手动处理。

### 问题 3

> "`with_fallbacks` 和 `handler` 是 Python 提供的函数吗？"

### 回答

都是 LangChain 框架提供的，不是 Python 内置：

- `with_fallbacks([...])` 是 `Runnable` 上的方法，把多个模型串成"前一个失败就换下一个"；
- `handler` 是 `AgentMiddleware` 钩子的第二个参数，由框架在调用时注入。

意图识别这里最后**没用** `with_fallbacks`，改成自己 try/except 切换——原因见第十节坑 2。

### 一个易错点：`max_retries=0`

主模型如果保留默认重试（通常 2 次），一次超时会先在自己身上重试两遍，
30 秒超时变成 90 秒，用户等不到降级。所以主模型显式设 `max_retries=0`，失败立刻交给备用。

### 怎么验收

不要用"换个不存在的模型名"来造故障——`init_chat_model` 在启动时就会报
`Unable to infer model provider`，那是构造期错误，测不到运行期降级。

用**无效 API Key** 造真实的 401，更接近线上故障：

| 场景 | 期望 | 实测 |
|---|---|---|
| 正常 | 主模型回答，无降级日志 | DeepSeek 200，0 条降级告警 |
| 主模型故障 | 自动切 qwen | DeepSeek 401 → DashScope 200，回答由 qwen 产出 |
| 主备都故障 | 转人工，连接不挂死 | 两个 401 → 意图识别兜底 `human_handoff`，前端收到转人工提示 |

---

## 十、这个阶段踩的两个真 bug

### bug 1：`route()` 漏了 `return result`

```python
result = self.router_model.invoke(prompt)
# 少了 return result
```

结果是 LLM 意图识别分支返回 `None`，调用方 `route_result.intent` 直接
`AttributeError: 'NoneType' object has no attribute 'intent'`，整轮对话变成"模型服务不可用"。

**为什么一直没暴露**：之前测试都发"你好"这类寒暄，命中正则快速通道直接返回字符串，
根本没走到 LLM 分支。

### bug 2：结构化输出混进了用户气泡

降级重试时，意图识别用的 `with_structured_output` 产生的那条 `AIMessage` 会以顶层
`messages` 事件混进流里，前端气泡直接显示：

```json
{"intent": "recommendation_plan", "reason": "用户询问重疾险的功能和作用…"}
```

排查方法：临时把每个流事件的原始结构打进日志，发现它 `namespace` 是空的（顶层事件），
但 `additional_kwargs` 里带 `parsed` 字段——这是结构化输出的标记。

修法在 `ChatService`：

```python
if isinstance(data, AIMessage):
    if data.additional_kwargs.get("parsed") is not None:
        continue          # 给程序看的结构化结果，不进用户气泡
    yield ServerSentEvent(data=data.text, event="message")
```

---

## 十一、部署复验（2026-11-25）

三个镜像在 VM 上串行重建后 `docker compose up -d`，浏览器实测：

| 项 | 结果 |
|---|---|
| 首页分页 | 「共 12 款产品」、2 页、翻页内容不同 |
| 险种筛选 | 切「医疗险」→ 共 3 款 |
| 登录 | 注册后表单登录，token 落 localStorage |
| 闲聊 | 回复正常 |
| 方案确认 | 卡片回放 → 点「确认保存方案」→「方案已确认」 |
| 引用来源 | 回答带 `[1] [2]`，底部列出条款 PDF |
| traceId | 前端「错误编号」能在后端日志里 grep 到，Java→Python 传递成立 |

**一条工具限制**：Lightpanda 无头浏览器读不了超过约 20 秒的长 SSE 流，
会报 `Failed to read response body`；同一个请求用 curl 走 Nginx 完全正常。
长轮次对话要在真实浏览器里点，别把工具限制当成应用 bug。

---

## 十二、面试可以怎么讲这三个卖点

1. **可信**：回答能追溯到条款原文（`[1]` 引用 + 写回 checkpoint + 刷新不丢），
   高风险动作（确认方案）走人工确认而不是模型自己决定。
2. **可用**：模型降级链 + 超时控制 + 事件级错误兜底，单点故障不会让用户对着空白气泡等。
3. **可查**：traceId 贯穿浏览器/Java/Python 三端，用户报错给一个编号就能定位到日志行。

配套的可量化证据：RAG 检索 recall@5 0.983 / MRR 0.809，多轮追问 recall@5 只有 0.333，
修完工具调用后复测正常——**能说清"改之前多差、改之后多好、为什么这么改"**。
