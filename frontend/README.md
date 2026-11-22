# frontend

React + Ant Design 保险商城和智能客服前端。

当前状态：阶段 6 完成，登录、产品、会话、流式对话、历史全部经 Java 中转。

## 本地运行

```powershell
docker compose up frontend --build
```

访问：

```text
http://localhost:5173
```

## 后端地址配置

本地开发默认代理到 Java 的 `http://127.0.0.1:8080`。如需覆盖，复制 `.env.example` 为 `.env.local` 后修改 `VITE_BACKEND_HOST` 和 `VITE_BACKEND_PORT`。

容器运行时通过 `BACKEND_HOST` 和 `BACKEND_PORT` 配置 Nginx 代理目标，默认值为 `backend:8080`（Java 是唯一对外入口，Python 不再被浏览器直连）。
