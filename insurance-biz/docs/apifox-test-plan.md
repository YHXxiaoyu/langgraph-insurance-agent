# Apifox 接口测试清单

> 配套 `insurance-biz` 的接口验收。分两组：用户主流程、内部接口鉴权边界。
> 接口定义以 `http://localhost:8080/v3/api-docs` 为准，改了代码文档会自动变。

## 一、准备

1. 启动应用，确认 `http://localhost:8080/doc.html` 能打开
2. Apifox 新建项目 → 左侧目录右键 **导入 → URL 导入** →
   填 `http://localhost:8080/v3/api-docs`，格式选 **OpenAPI 3**
3. 进去后应看到四个目录：认证 / 会话 / 产品 / 内部接口
4. 右上角环境 → 新建「本地」环境，加两个变量：

| 变量名 | 值 |
|---|---|
| `BASE` | `http://localhost:8080` |
| `INTERNAL_TOKEN` | 取 `.env` 里 `INTERNAL_TOKEN` 的值 |

5. 把所有请求的 URL 改成 `{{BASE}}/...` 开头

## 二、两个公共 Header

「会话」「产品」「认证/当前用户」这三个目录下的接口，都需要：

```
Authorization: Bearer {{token}}
```

内部接口目录下需要：

```
X-Internal-Token: {{INTERNAL_TOKEN}}
```

> 省事做法：在目录上设公共 Header，一次配置对该目录所有接口生效。
> 登录 / 注册两个接口不要带 `Authorization`，它们是白名单。

---

## 场景一：用户主流程

### 前置脚本（放在「注册」这一步）

```javascript
// 每次运行生成唯一用户名，避免重复运行撞 409
pm.environment.set("uname", "apifox" + Date.now());
```

### 步骤

| # | 方法 | 路径 | 请求体 | 期望 | 断言脚本 |
|---|---|---|---|---|---|
| 1 | POST | `{{BASE}}/api/v1/auth/register` | `{"username":"{{uname}}","email":"{{uname}}@example.com","password":"123456"}` | 201 | 见下方 A |
| 2 | POST | `{{BASE}}/api/v1/auth/login` | `{"username":"{{uname}}","password":"123456"}` | 200 | 见下方 B（存 token） |
| 3 | GET | `{{BASE}}/api/v1/auth/me` | - | 200 | `data.userId` 有值 |
| 4 | GET | `{{BASE}}/api/v1/products` | - | 200 | `data.length === 12` |
| 5 | POST | `{{BASE}}/api/v1/chat-threads` | `{"title":"测试会话"}` | 201 | 见下方 C（存 threadId） |
| 6 | GET | `{{BASE}}/api/v1/chat-threads` | - | 200 | `data` 里有 `{{threadId}}` |
| 7 | PATCH | `{{BASE}}/api/v1/chat-threads/{{threadId}}` | `{"title":"改名后"}` | 200 | `code === 200` |
| 8 | GET | `{{BASE}}/api/v1/chat-threads` | - | 200 | 第一条 `title === "改名后"` |
| 9 | DELETE | `{{BASE}}/api/v1/chat-threads/{{threadId}}` | - | 200 | - |
| 10 | PATCH | `{{BASE}}/api/v1/chat-threads/{{threadId}}` | `{"title":"x"}` | **404** | 删掉之后再操作应 404 |
| 11 | GET | `{{BASE}}/api/v1/products`（**不带** Authorization） | - | **401** | - |
| 12 | POST | `{{BASE}}/api/v1/auth/login` | `{"username":"{{uname}}","password":"wrong"}` | **401** | `msg` 含「用户名或密码错误」 |
| 13 | POST | `{{BASE}}/api/v1/auth/register` | 重复第 1 步的 username | **409** | `msg` 含「已被占用」 |
| 14 | POST | `{{BASE}}/api/v1/auth/register` | `{"username":"ab","email":"ab@example.com","password":"123456"}` | **400** | `msg` 含「用户名长度」 |

### 断言脚本

**A. 注册**

```javascript
const body = pm.response.json();
pm.test("201 Created", () => pm.response.to.have.status(201));
pm.test("code 为 200", () => pm.expect(body.code).to.eql(200));
pm.test("返回了 token", () => pm.expect(body.data.token).to.be.a("string").and.not.empty);
pm.environment.set("token", body.data.token);
```

**B. 登录**

```javascript
const body = pm.response.json();
pm.test("200 OK", () => pm.response.to.have.status(200));
pm.test("code 为 200", () => pm.expect(body.code).to.eql(200));
pm.environment.set("token", body.data.token);
```

**C. 新建会话**

```javascript
const body = pm.response.json();
pm.test("201 Created", () => pm.response.to.have.status(201));
pm.test("id 是 36 位 UUID", () => pm.expect(body.data.id).to.have.lengthOf(36));
pm.test("createdAt 有值", () => pm.expect(body.data.createdAt).to.be.a("string"));
pm.environment.set("threadId", body.data.id);
```

---

## 场景二：内部接口鉴权边界

这一组不需要登录，只用 `X-Internal-Token`。

| # | 方法 | 路径 | 请求头 | 请求体 | 期望 |
|---|---|---|---|---|---|
| 1 | POST | `{{BASE}}/internal/v1/products/candidates` | 无 | `{"categories":["medical"]}` | **401** |
| 2 | 同上 | 同上 | `X-Internal-Token: wrong-token` | 同上 | **401** |
| 3 | 同上 | 同上 | `X-Internal-Token: {{INTERNAL_TOKEN}}` | 见下方 | 200 |
| 4 | 同上 | 同上 | `Authorization: Bearer {{token}}`（**不**带内部 token） | `{"categories":["medical"]}` | **401** |
| 5 | GET | `{{BASE}}/api/v1/products` | `X-Internal-Token: {{INTERNAL_TOKEN}}` | - | **401** |
| 6 | GET | `{{BASE}}/internal/v1/products/999999` | `X-Internal-Token: {{INTERNAL_TOKEN}}` | - | **404** |
| 7 | POST | `{{BASE}}/internal/v1/products/candidates` | 正确 token | `{"categories":[]}` | **400** |
| 8 | POST | `{{BASE}}/internal/v1/products/candidates` | 正确 token | `{"categories":["medical"],"limitPerCategory":999}` | **400** |

**第 3 步的请求体**

```json
{
  "categories": ["medical", "critical_illness"],
  "premiumMin": 500,
  "limitPerCategory": 3
}
```

**第 3 步的断言**

```javascript
const body = pm.response.json();
const data = body.data;

pm.test("200 OK", () => pm.response.to.have.status(200));

pm.test("每个险种最多 3 条", () => {
    const counter = {};
    data.forEach(x => counter[x.category] = (counter[x.category] || 0) + 1);
    Object.values(counter).forEach(n => pm.expect(n).to.be.at.most(3));
});

pm.test("保费都严格小于 500", () => {
    data.forEach(x => {
        if (x.minPremium !== null) pm.expect(Number(x.minPremium)).to.be.below(500);
    });
});

pm.test("组内按保费升序", () => {
    const groups = {};
    data.forEach(x => (groups[x.category] = groups[x.category] || []).push(x.minPremium));
    Object.values(groups).forEach(list => {
        const nonNull = list.filter(v => v !== null).map(Number);
        const sorted = [...nonNull].sort((a, b) => a - b);
        pm.expect(nonNull).to.eql(sorted);
    });
});
```

第 4、5 步是这一组的重点：**两套凭证双向都进不去对方的门**。

---

## 三、常见问题

| 现象 | 原因 |
|---|---|
| 导入后 URL 变成 `http://localhost:8080/http://localhost:8080/...` | 导入时带了绝对地址，改成 `{{BASE}}/...` |
| 重跑场景一时第 1 步 409 | 用户名固定了，用前置脚本生成 `{{uname}}` |
| Apifox 把 401 标红以为失败 | 正常，写断言 `pm.response.to.have.status(401)` 就不会误判 |
| 网页版 Apifox 连不上 localhost | 网页版在你浏览器里跑、请求发往 Apifox 服务器；用桌面客户端 |
| 内部接口导入后没有 Header | 手动加 `X-Internal-Token: {{INTERNAL_TOKEN}}` |

## 四、日常用法

- 改完代码 → 重启应用 → 跑场景一，全绿说明没有回归
- 内部接口改动 → 跑场景二
- 新增接口 → 重新从 `/v3/api-docs` 导入一次，Apifox 会做增量合并
