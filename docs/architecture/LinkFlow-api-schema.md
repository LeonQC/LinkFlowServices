# LinkFlow API Schema (v2)

## 1. Scope and Target

LinkFlow is a high-concurrency intelligent short URL system:

- Frontend: React + Axios
- Backend: Java Spring Boot
- Data: PostgreSQL + Redis
- Streaming: Kafka + Flink
- Async jobs and notifications: RabbitMQ + WebSocket
- AI: Hugging Face (classification/recommendation/risk) and optional Google Safe Browsing API

This schema covers the following scenarios:

1. Short URL generation and high-concurrency redirection
2. User management and JWT authorization
3. Real-time log analytics
4. Intelligent link classification and recommendation
5. Spam and malicious link detection
6. Data visualization and monitoring
7. Frontend-ready API contracts

## 2. API Conventions

- Base URL: `/api/v1`
- Public redirect URL: `/{slug}` or `/r/{slug}`
- Auth header: `Authorization: Bearer <jwt_access_token>`
- Service auth (internal): `X-Service-Key: <service_key>`
- Time format: ISO 8601 UTC
- ID format: UUID

Standard success:

```json
{
  "request_id": "req_01JH4F7H7W4KPK9M9X2Z",
  "data": {},
  "error": null
}
```

Standard error:

```json
{
  "request_id": "req_01JH4F7H7W4KPK9M9X2Z",
  "data": null,
  "error": {
    "code": "LINK_NOT_FOUND",
    "message": "Short link does not exist or is inactive.",
    "details": {}
  }
}
```

## 3. Domain Models (API Level)

### 3.1 User

```json
{
  "id": "4f5f4169-ece2-4d2e-b3b4-a03ff9dc5da4",
  "email": "alice@example.com",
  "username": "alice",
  "role": "user",
  "status": "active",
  "created_at": "2026-02-15T10:00:00Z"
}
```

### 3.2 Short Link

```json
{
  "id": "f42f857c-c9cc-4d1f-8b7f-bfc86098b0cb",
  "owner_user_id": "4f5f4169-ece2-4d2e-b3b4-a03ff9dc5da4",
  "slug": "x7Ab9Q",
  "long_url": "https://example.com/articles/ai",
  "title": "Example AI Article",
  "channel": "twitter",
  "status": "active",
  "expires_at": null,
  "click_count": 0,
  "created_at": "2026-02-15T10:05:00Z"
}
```

### 3.3 Click Event (Kafka source payload)

```json
{
  "event_id": "078b133d-fb47-4da8-a972-febca42cbe2d",
  "occurred_at": "2026-02-15T10:07:12Z",
  "link_id": "f42f857c-c9cc-4d1f-8b7f-bfc86098b0cb",
  "slug": "x7Ab9Q",
  "ip": "203.0.113.10",
  "country": "US",
  "city": "San Francisco",
  "device_type": "mobile",
  "browser": "Chrome",
  "os": "Android",
  "referrer": "https://t.co/...",
  "channel": "twitter",
  "utm_source": "twitter",
  "is_bot": false
}
```

## 4. Endpoint Groups

### 4.1 Auth and JWT
中文说明：提供用户注册、登录、令牌刷新与身份校验能力。

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `GET /api/v1/auth/me`

Login response:

```json
{
  "access_token": "jwt_access_token",
  "refresh_token": "jwt_refresh_token",
  "token_type": "Bearer",
  "expires_in": 3600,
  "user": {
    "id": "4f5f4169-ece2-4d2e-b3b4-a03ff9dc5da4",
    "email": "alice@example.com",
    "username": "alice",
    "role": "user"
  }
}
```

### 4.2 User and Permission
中文说明：管理用户资料、用户状态与管理员权限操作。

- `GET /api/v1/users/me`
- `PATCH /api/v1/users/me`
- `GET /api/v1/users/me/links`
- `GET /api/v1/admin/users` (admin only)
- `PATCH /api/v1/admin/users/{user_id}/status` (admin only)

### 4.3 Short URL CRUD
中文说明：提供短链接创建、查询、更新、删除的核心接口。

- `POST /api/v1/links`
- `GET /api/v1/links/{link_id}`
- `GET /api/v1/links`
- `PATCH /api/v1/links/{link_id}`
- `DELETE /api/v1/links/{link_id}`

Create link request:

```json
{
  "long_url": "https://example.com/very/long/path",
  "custom_slug": "my-campaign-2026",
  "channel": "instagram",
  "expires_at": "2026-03-30T00:00:00Z"
}
```

Create link response:

```json
{
  "id": "f42f857c-c9cc-4d1f-8b7f-bfc86098b0cb",
  "slug": "my-campaign-2026",
  "short_url": "https://lfow.io/my-campaign-2026",
  "long_url": "https://example.com/very/long/path",
  "channel": "instagram",
  "status": "active"
}
```

### 4.4 Redirect (High Concurrency Path)
中文说明：定义高并发重定向主链路，强调 Redis 命中与异步日志。

- `GET /{slug}`
- `GET /r/{slug}`

Redirect behavior:

1. Resolve `slug -> long_url` via Redis (cache hit first)
2. Fallback to PostgreSQL on cache miss, then refill Redis
3. Return HTTP 302/307 redirect
4. Emit click log asynchronously to Kafka topic
5. Trigger risk check and AI tasks asynchronously when configured

### 4.5 Link Ownership and History
中文说明：查询链接归属与历史访问记录，支持用户侧追踪。

- `GET /api/v1/links/{link_id}/history`
- `GET /api/v1/links/{link_id}/clicks`
- `GET /api/v1/users/me/recent-links`

### 4.6 Real-Time Analytics (Flink output APIs)
中文说明：提供实时分析结果查询，包括趋势、地域和渠道拆解。

- `GET /api/v1/analytics/realtime/overview?window=15m`
- `GET /api/v1/analytics/realtime/hot-links?window=15m&limit=10`
- `GET /api/v1/analytics/links/{link_id}/timeseries?granularity=1m&from=...&to=...`
- `GET /api/v1/analytics/links/{link_id}/location-breakdown?from=...&to=...`
- `GET /api/v1/analytics/links/{link_id}/channel-breakdown?from=...&to=...`
- `GET /api/v1/analytics/realtime/traffic-map?window=15m`

### 4.7 Link Classification (AI)
中文说明：提交与查询链接分类任务，存储并返回 AI 分类结果。

- `POST /api/v1/ai/classification/tasks`
- `GET /api/v1/ai/classification/tasks/{task_id}`
- `GET /api/v1/links/{link_id}/classification`
- `POST /api/v1/links/{link_id}/classification/retry`

Create classification task request:

```json
{
  "link_id": "f42f857c-c9cc-4d1f-8b7f-bfc86098b0cb",
  "provider": "huggingface",
  "model": "facebook/bart-large-mnli"
}
```

### 4.8 Recommendation (AI)
中文说明：提交推荐任务并返回个性化推荐结果，支持用户反馈闭环。

- `POST /api/v1/ai/recommendation/tasks`
- `GET /api/v1/ai/recommendation/tasks/{task_id}`
- `GET /api/v1/users/me/recommendations?limit=20`
- `POST /api/v1/users/me/recommendations/feedback`

Feedback request:

```json
{
  "recommendation_id": "37b60dad-b90e-486b-a06c-b8c8e93917af",
  "action": "clicked"
}
```

### 4.9 Spam and Malicious Link Detection
中文说明：提供垃圾链接与恶意链接检测、审核和黑名单管理能力。

- `POST /api/v1/risk/scan/tasks`
- `GET /api/v1/risk/scan/tasks/{task_id}`
- `GET /api/v1/links/{link_id}/risk-report`
- `POST /api/v1/admin/risk/links/{link_id}/review` (admin only)
- `POST /api/v1/admin/risk/blacklist/domain` (admin only)
- `POST /api/v1/admin/risk/blacklist/url` (admin only)

Create risk scan request:

```json
{
  "link_id": "f42f857c-c9cc-4d1f-8b7f-bfc86098b0cb",
  "providers": ["huggingface", "google_safe_browsing"]
}
```

### 4.10 Dashboard and Monitoring APIs
中文说明：提供业务看板与系统监控指标查询接口。

- `GET /api/v1/dashboard/summary?from=...&to=...`
- `GET /api/v1/dashboard/trends?granularity=1h&from=...&to=...`
- `GET /api/v1/dashboard/channels?from=...&to=...`
- `GET /api/v1/dashboard/locations?from=...&to=...`

System metrics endpoints:

- `GET /api/v1/monitoring/health`
- `GET /api/v1/monitoring/redis`
- `GET /api/v1/monitoring/kafka`
- `GET /api/v1/monitoring/rabbitmq`
- `GET /api/v1/monitoring/flink/jobs`

### 4.11 WebSocket Contracts
中文说明：定义实时推送通道与事件格式，用于前端即时展示。

Handshake:

- `POST /api/v1/ws/token`

Realtime WebSocket endpoint:

- `GET /ws/realtime?token=<ws_token>`

Server event names:

- `analytics.realtime.updated`
- `analytics.hot_links.updated`
- `risk.alert.created`
- `ai.task.status_changed`
- `system.pipeline.degraded`

Example WebSocket event payload:

```json
{
  "event": "analytics.realtime.updated",
  "occurred_at": "2026-02-15T10:10:00Z",
  "data": {
    "window": "15m",
    "total_clicks": 12840,
    "active_users": 624
  }
}
```

## 5. Async Messaging Contracts
中文说明：定义 Kafka、Flink、RabbitMQ 的异步数据契约。

### 5.1 Kafka Topics (Logs and Streaming Analytics)
中文说明：点击日志和聚合结果的流式主题定义。

- `linkflow.click.raw.v1`
- `linkflow.click.enriched.v1`
- `linkflow.link.lifecycle.v1`
- `linkflow.analytics.agg.1m.v1`
- `linkflow.analytics.hot.v1`

`linkflow.click.raw.v1` key/value:

- Key: `slug`
- Value: Click Event JSON (see section 3.3)

### 5.2 Flink Jobs
中文说明：实时计算任务定义及其下游存储目标。

- `job_click_agg_1m`: raw click stream -> per minute aggregates
- `job_hot_links_5m`: rolling top links
- `job_geo_channel_agg`: location and channel breakdown
- `job_user_interest_profile`: update user interest vectors from click behavior

Flink sink targets:

- PostgreSQL aggregate tables
- Redis hot dashboards cache
- Kafka aggregated topics (optional)

### 5.3 RabbitMQ Exchanges and Queues
中文说明：AI 异步任务与通知广播的队列和路由规则。

Exchange:

- `linkflow.ai.exchange` (topic)
- `linkflow.notify.exchange` (topic)

Queues:

- `linkflow.ai.classification.q`
- `linkflow.ai.recommendation.q`
- `linkflow.ai.risk_scan.q`
- `linkflow.ws.broadcast.q`
- `linkflow.admin.alert.q`

Routing keys:

- `ai.classification.request`
- `ai.recommendation.request`
- `ai.risk_scan.request`
- `notify.ws.broadcast`
- `notify.admin.alert`

## 6. Redis Key Design (Performance)
中文说明：定义高并发场景下的缓存键空间与 TTL 策略。

- `lf:slug:{slug}` -> `{long_url, link_id, status, expires_at}`
- `lf:link:meta:{link_id}` -> link metadata
- `lf:analytics:realtime:overview:{window}`
- `lf:analytics:hot_links:{window}`
- `lf:user:recommendations:{user_id}`
- `lf:risk:blocklist:domain`
- `lf:risk:blocklist:url`

Cache policy suggestions:

- Redirect mapping TTL: 6h to 24h
- Realtime analytics TTL: 30s to 2m
- Recommendation cache TTL: 10m to 60m

## 7. Frontend Integration Notes (React + Axios)
中文说明：约束前端页面依赖的 API、鉴权刷新和实时订阅流程。

Required pages and API dependencies:

1. Home page: `POST /api/v1/links`, `GET /api/v1/analytics/realtime/overview`
2. Login/Register: `/api/v1/auth/register`, `/api/v1/auth/login`
3. User dashboard: link CRUD + analytics + recommendations
4. Admin dashboard: risk review + blacklist + monitoring

Axios requirements:

- Interceptor for JWT refresh on 401
- WebSocket token fetch before realtime subscription

## 8. Error Codes
中文说明：统一错误码，便于前后端联调和异常定位。

- `AUTH_INVALID_CREDENTIALS`
- `AUTH_TOKEN_EXPIRED`
- `PERMISSION_DENIED`
- `LINK_SLUG_CONFLICT`
- `LINK_NOT_FOUND`
- `LINK_EXPIRED`
- `LINK_BLOCKED`
- `RISK_SCAN_FAILED`
- `AI_PROVIDER_UNAVAILABLE`
- `KAFKA_PUBLISH_FAILED`
- `RABBITMQ_PUBLISH_FAILED`
- `FLINK_RESULT_UNAVAILABLE`
- `RATE_LIMITED`

## 9. Non-Functional Targets
中文说明：给出性能、可用性与可观测性方面的目标基线。

1. Redirect P95 < 80ms with Redis cache hit.
2. Redirect path must be non-blocking for logging and AI tasks.
3. Kafka and RabbitMQ pipelines must support replay/retry.
4. Dashboard data freshness target: 5s to 30s.
5. End-to-end observability: request trace ID + event ID correlation.
