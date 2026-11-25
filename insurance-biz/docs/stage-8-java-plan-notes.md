# 阶段 8 笔记：补齐 Java 业务域（保险方案）

> 这一阶段不碰模型，补的是"业务数据由谁落库"。
> 按「问题 → 回答」记录：为什么要补、方案域怎么设计、六个关键决策、踩的坑、验收结果。

---

## 一、为什么要补 Java 业务域

### 问题

> "我们这个项目 Java 端都有哪些业务功能？"

### 回答

数据库里设计了 16 张表，Java 代码只映射了 3 张：

| 业务域 | 表 | 代码状态 |
|---|---|---|
| 用户与认证 | `users` | ✅ 注册 / 登录 / 当前用户 |
| 产品目录 | `products` | ✅ 分页 + 险种筛选 + 内部候选查询 |
| 会话 | `chat_threads` | ✅ 增删改查 + 历史消息 |
| 保险方案 | `insurance_plans`、`insurance_plan_items` | ❌ 只有表 |
| 保单 | `policies` | ❌ 只有表 |
| 理赔 | `claims` | ❌ 只有表 |
| 费率与精算 | `rate_plans`、`rate_table_items`、`premium_quotes` 等 7 张 | ❌ 只有表 |
| 订单 | `orders` | ❌ 只有表 |

完整的业务链本该是：

```
产品目录 → 保费试算 → 生成方案 → 下单 → 支付 → 出保单 → 理赔
   ✅         ❌         ❌        ❌     ❌      ❌       ❌
```

**最直接的后果**：前端方案卡片上的「去投保」按钮需要 `plan_id` 和明细 `id`，
而"方案落库"这个域一行代码都没有，所以按钮永远置灰。

### 为什么先补方案而不是理赔

- 理赔咨询做实属于 Python 侧（改 `claims_node`），不增加 Java 能力
- 方案是唯一被前端"硬编码等待"的缺口：接口已经在调，只是 404
- 方案落库之后，保单、我的保单才有数据源

---

## 二、方案域的接口

| 接口 | 调用方 | 说明 |
|---|---|---|
| `POST /internal/v1/insurance-plans` | Python | 用户确认方案后落库，返回 plan_id + 明细 id |
| `GET /api/v1/insurance-plans` | 浏览器 | 我的方案列表（最多 20 条） |
| `GET /api/v1/insurance-plans/{id}` | 浏览器 | 方案详情，含产品名、险种、状态 |
| `PATCH /api/v1/insurance-plans/{id}/items/{itemId}/insured` | 浏览器 | 标记某项已投保 |

---

## 三、11 个文件的分层

```
InternalPlanController      /internal/v1/insurance-plans   ← Python
InsurancePlanController     /api/v1/insurance-plans        ← 浏览器
            │
IInsurancePlanService → InsurancePlanServiceImpl           ← 业务规则全在这
            │
InsurancePlanMapper / InsurancePlanItemMapper / ProductMapper
            │
insurance_plans / insurance_plan_items / products

数据形状：
  PO   = 数据库长什么样（InsurancePlan / InsurancePlanItem）
  DTO  = 别人传进来长什么样（CreatePlanDTO）
  VO   = 传出去长什么样（PlanVO / PlanItemVO）
```

### 问题

> "为什么 PO / DTO / VO 要分成三个类？"

### 回答

| 类 | 服务谁 | 只用一个会出什么问题 |
|---|---|---|
| PO | 数据库 | 带 `userId`、`insuredProfile`，直接返给浏览器就是信息泄露 |
| DTO | Python 传进来 | 需要 `userId`（内部接口没有登录态）和 `@NotBlank` 这类校验 |
| VO | 浏览器 | 要补表里没有的 `product_name`、`category`，且绝不能带 `userId` |

---

## 四、六个关键设计点

### 1. `userId` 的来源，两侧不一样

- `createByAgent`：用 `dto.userId()` —— 内部接口是 Python 调的，没有 SecurityContext
- `getMine` / `markItemInsured`：用 `UserContext.getUserId()` —— 对外接口走 JWT

这是内部接口和对外接口最容易混的地方。

### 2. 归属校验写成查询条件

```java
.eq(InsurancePlan::getId, planId)
.eq(InsurancePlan::getUserId, UserContext.getUserId())
```

别人的方案在 SQL 层就是"查不到"。若先按 id 查出来再比较 userId，
代码里就存在一个"已经拿到了别人数据"的瞬间。

### 3. 幂等：重复点支付不能有副作用

```java
if (!STATUS_INSURED.equals(item.getStatus())) { ...更新... }
```

下一步接保单后，这条更重要——否则会发出两张保单。

### 4. 更新只带 `id + status`

复用查询出来的实体去 `updateById`，`updatedAt` 会被原样写回，
数据库的 `ON UPDATE CURRENT_TIMESTAMP` 就不生效了。

### 5. 列表批量查产品，不用 N+1

先收集全部 `productId`，一次 `IN` 查回来做成 Map；
按明细循环查的话，一个 5 产品的方案要 6 次查询。

### 6. 事务加在哪

- `createByAgent`：方案 + 明细必须同生共死
- `markItemInsured`：明细状态和方案状态要一起变
- `listMine` / `getMine`：纯查询，加了只是多一层代理

---

## 五、Python 侧的配合（约 30 行）

1. `user_id` 进 `InsuranceAgentState`：内部接口没有登录态，Java 把 userId 透传进来
2. `confirm_plan` 改成 `async`，approve 分支调 `POST /internal/v1/insurance-plans`
3. 落库结果（plan_id、明细 id）通过 `additional_info` 的 `plan_items` 推给前端
4. `_persist_additional_info` 把它写回 checkpoint，刷新页面后卡片还在

### 问题

> "方案已经保存了，为什么前端还是拿不到 `plan_id`？"

### 回答

`record_saved()` 一开始用的是 `ContextVar.set()`。但 LangGraph 的节点跑在**子任务**里，
子任务里 `set()` 的值不会传回父上下文——父上下文读到的还是 `None`。

对比 `citations`：它能工作是因为 `record()` 往父上下文创建的**同一个 list 对象**里
`append`，而不是 `set` 一个新值。

修法：把存储换成单元素容器，只改容器里的内容。

```python
_saved_plan: ContextVar[list[dict | None]] = ContextVar("saved_plan", default=[None])

def record_saved(saved): _saved_plan.get()[0] = saved
def current_saved():     return _saved_plan.get()[0]
```

**一句话记住**：ContextVar 跨任务共享"状态"，靠的是共享可变对象，不是 `set()`。

---

## 六、验收结果

| 环节 | 结果 |
|---|---|
| 内部接口建方案 | 返回 plan_id + 明细 id；重复产品、缺失产品都被拦 |
| 用户接口查方案 | 列表 / 详情正常；不存在的方案 404，别人的产品 id 400 |
| 对话推荐 → 卡片确认 | MySQL 出现方案行 846.30 元 + 2 条明细（561 + 285.3） |
| `additional_info` 事件 | 带 plan_id、明细 id、产品名、保费、状态 |
| 历史回放 | 最后一条助手消息的 `additional_info` 里仍带 `plan_items` |
| 投保两次 | 明细 `uninsured → insured`，方案 `uninsured → applying → insured` |
| Python 单测 | 13 passed |

---

## 七、这一轮**没做**的（避免误会）

1. **发保单**：`markItemInsured` 里留了 `TODO(保单域)`，现在只改状态，`policies` 表不会新增数据
2. **我的保单页面**：需要保单域的两个查询接口
3. **理赔受理**：`claims` 表还没有任何接口
4. **方案删除 / 过期方案清理**：没做

---

## 八、下一步

```
保单域（0.5 天） → 我的保单页面（0.5 天） → 多气泡修复（0.5 天）
```

保单域补完后，「确认方案 → 投保 → 查保单」这条线才算真正闭环。
