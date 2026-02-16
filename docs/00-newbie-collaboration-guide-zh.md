# LinkFlow 新手协作与落地指南（一步步操作版）

## 1. 你现在的仓库结构

你有两个仓库：

- `LinkFlowServices`：后端、数据、流处理、AI、基础设施
- `LinkFlow-UI`：前端 React 项目

建议职责：

- 后端协议和数据库文档放 `LinkFlowServices/docs/architecture/`
- 前端只保留接口对接说明，避免重复维护两套 schema

## 2. Fork 的意义（你为什么要 fork）

1. 你可以在自己的仓库安全迭代，不会直接破坏上游项目。
2. 你可以通过 PR 让协作过程有评审记录。
3. 你可以持续同步上游更新，保持自己的仓库不落后。

## 3. 必做的一次性初始化（每个仓库都做）

### 步骤 1：检查远程

```bash
git remote -v
```

操作含义：确认 `origin` 是否指向你的 fork。

### 步骤 2：添加上游仓库

```bash
git remote add upstream <上游仓库URL>
```

操作含义：后续你可以从上游拉最新改动。

### 步骤 3：同步主分支

```bash
git fetch upstream
git checkout main
git rebase upstream/main
git push origin main
```

操作含义：让你自己的 `main` 与上游保持一致。

## 4. 日常开发标准流程（最重要）

### 步骤 1：从 main 切功能分支

```bash
git checkout main
git pull origin main
git checkout -b feature/<你的功能名>
```

操作含义：每个需求独立开发，避免互相污染。

### 步骤 2：开发 + 本地验证

后端至少保证：接口可跑通、核心 SQL 可执行。  
前端至少保证：页面可打开、接口联调路径完整。

### 步骤 3：提交改动

```bash
git add .
git commit -m "feat: <简要描述>"
git push origin feature/<你的功能名>
```

操作含义：把当前功能快照推到远程，准备 PR。

### 步骤 4：提 Pull Request

- 从 `feature/<功能名>` 提到目标 `main`
- 在 PR 描述中写清楚：改了什么、怎么测、风险点

操作含义：让协作可评审、可回滚、可追踪。

## 5. 当前文档应该放在哪里

已经迁移：

- `LinkFlow-api-schema.md`
- `LinkFlow-database-schema.md`

目标位置：`LinkFlowServices/docs/architecture/`

原因：

1. API 与 DB 是后端契约，应该和服务端代码同仓管理。
2. 版本变更能和后端提交绑定，方便回溯。
3. 前端直接引用该目录文档即可，避免文档分叉。

## 6. 推荐的项目目录（基础骨架）

`LinkFlowServices`：

- `docs/architecture/`：架构与协议文档
- `backend/gateway/`：网关与鉴权入口
- `backend/user-service/`：用户与权限
- `backend/link-service/`：短链生成/重定向
- `backend/analytics-service/`：日志消费与统计查询
- `backend/ai-service/`：分类、推荐、风控
- `infra/`：Redis/Kafka/Flink/RabbitMQ/Postgres 配置

`LinkFlow-UI`：

- `src/pages/`：页面
- `src/components/`：通用组件
- `src/services/`：Axios API 封装
- `src/store/`：状态管理
- `src/hooks/`：复用逻辑
- `src/styles/`：样式体系

## 7. 第一周执行建议（按顺序）

1. 后端先实现 Auth + Link CRUD + Redirect + Redis 缓存。
2. 接入 Kafka：把点击日志异步写入 topic。
3. Flink 出第一版聚合（1m 点击量 + 热门链接）。
4. 前端完成登录页、短链创建页、基础仪表盘。
5. RabbitMQ 接入 AI 任务队列（分类和风险检测先跑通一个）。

## 8. 你现在可以马上做的下一步

1. 在两个仓库分别执行 `git status`，确认迁移结果。
2. 提交一次“docs + skeleton”的初始化 PR。
3. 基于本指南开始第一个 `feature/auth-and-link-crud` 分支。
