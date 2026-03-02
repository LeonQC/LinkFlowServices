# LinkFlow API Schema (v3)

## 1. Purpose and Scope

This document defines the backend API contracts for LinkFlow based on:

- LinkFlow UI routes and data needs (`/dashboard`, `/links`, `/links/:id`, `/qr-codes`, `/alerts`, `/monitoring`, `/auth/login`)
- The target architecture (`gateway`, `user-service`, `link-service`, `analytics-service`, `ai-service`)
- Runtime stack (Spring Boot, PostgreSQL, Redis, Kafka, Flink, RabbitMQ, WebSocket)

Goals:

1. Stable frontend contracts for CRUD, analytics, risk review, and monitoring.
2. Clear request/response models for Spring Boot controller/service implementation.
3. Explicit async boundaries (Kafka/Flink/RabbitMQ/WebSocket).

## 2. API Baseline Conventions

- Base URL: `/api/v1`
- Public redirect URL: `/{slug}` and `/r/{slug}`
- Auth header: `Authorization: Bearer <access_token>`
- Service header (internal calls): `X-Service-Key: <service_key>`
- Time format: ISO 8601 UTC (`TIMESTAMPTZ` compatible)
- ID format: UUID
- Content type: `application/json; charset=utf-8`

### 2.1 Standard Response Envelope

Success:

```json
{
  "request_id": "req_01JH4F7H7W4KPK9M9X2Z",
  "data": {},
  "error": null,
  "meta": {}
}
```

Error:

```json
{
  "request_id": "req_01JH4F7H7W4KPK9M9X2Z",
  "data": null,
  "error": {
    "code": "LINK_NOT_FOUND",
    "message": "Short link does not exist or is inactive.",
    "details": {}
  },
  "meta": {}
}
```

Pagination (`meta.page`):

```json
{
  "page": 1,
  "size": 20,
  "total_elements": 358,
  "total_pages": 18,
  "has_next": true
}
```

### 2.2 Common Query Parameters

- `page` (default `1`)
- `size` (default `20`, max `100`)
- `sort` (e.g. `created_at,desc`)
- `from` / `to` (ISO 8601)
- `window` (`5m`, `15m`, `1h`, `24h`)

### 2.3 Idempotency

For create-like write endpoints, support:

- Header: `Idempotency-Key: <uuid>`
- Deduplicate repeated requests within 24h
- Return same response body for same key + same authenticated user

## 3. Domain Enums

`role`: `user | admin`  
`user_status`: `active | suspended | deleted`  
`link_status`: `active | paused | expired | blocked`  
`risk_level`: `unknown | low | medium | high | critical`  
`alert_status`: `pending | approved | blocked | blacklisted`  
`task_status`: `queued | running | succeeded | failed | cancelled`

## 4. Core API Models

### 4.1 User

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

### 4.2 Link Summary (for `/links`, `/qr-codes`)

```json
{
  "id": "f42f857c-c9cc-4d1f-8b7f-bfc86098b0cb",
  "slug": "promo2026",
  "short_url": "https://lfow.io/promo2026",
  "long_url": "https://example.com/campaign",
  "title": "Spring Campaign",
  "channel": "wechat",
  "status": "active",
  "click_count": 45892,
  "unique_visitors": 32847,
  "created_at": "2026-02-15T10:05:00Z",
  "expires_at": "2026-03-30T00:00:00Z"
}
```

### 4.3 Risk Alert

```json
{
  "id": "9adb658f-d98c-4abe-a66a-8d6637f4c9b4",
  "link_id": "f42f857c-c9cc-4d1f-8b7f-bfc86098b0cb",
  "short_url": "https://lfow.io/sus-deal",
  "title": "Suspicious campaign link",
  "risk_level": "high",
  "risk_score": 87,
  "reasons": ["domain_mismatch", "abnormal_traffic_spike"],
  "status": "pending",
  "detected_at": "2026-02-15T14:28:00Z",
  "reporter": "ai-risk-engine"
}
```

## 5. Endpoint Contracts

## 5.1 Auth and JWT

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `GET /api/v1/auth/me`

Login response:

```json
{
  "request_id": "req_01",
  "data": {
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
  },
  "error": null,
  "meta": {}
}
```

## 5.2 Link CRUD and QR Code

- `POST /api/v1/links`
- `GET /api/v1/links`
- `GET /api/v1/links/{link_id}`
- `PATCH /api/v1/links/{link_id}`
- `PATCH /api/v1/links/{link_id}/status`
- `DELETE /api/v1/links/{link_id}`
- `GET /api/v1/links/{link_id}/qrcode?format=png&size=512`

Create request:

```json
{
  "long_url": "https://example.com/very/long/path",
  "title": "Spring campaign",
  "custom_slug": "my-campaign-2026",
  "channel": "instagram",
  "expires_at": "2026-03-30T00:00:00Z",
  "tags": ["campaign", "spring"],
  "metadata": {
    "utm_source": "instagram"
  }
}
```

List query examples:

- `/api/v1/links?page=1&size=20&status=active&search=promo&sort=created_at,desc`
- `/api/v1/links?page=1&size=20&status=paused`

Field validation:

- `long_url`: required, valid URL, max 2048 chars
- `custom_slug`: optional, regex `^[a-zA-Z0-9][a-zA-Z0-9_-]{2,79}$`
- `title`: required, max 300 chars

## 5.3 Redirect (High Concurrency)

- `GET /{slug}`
- `GET /r/{slug}`

Behavior:

1. Resolve `slug -> long_url` in Redis first.
2. On cache miss, fallback to PostgreSQL and refill Redis.
3. Return `302` or `307` immediately.
4. Publish click event asynchronously to Kafka (`linkflow.click.raw.v1`).
5. Trigger optional risk/AI pipeline asynchronously via RabbitMQ.

## 5.4 Link Analytics (Detail Page)

- `GET /api/v1/links/{link_id}/analytics/summary?from=...&to=...`
- `GET /api/v1/links/{link_id}/analytics/timeseries?granularity=1h&from=...&to=...`
- `GET /api/v1/links/{link_id}/analytics/devices?from=...&to=...`
- `GET /api/v1/links/{link_id}/analytics/browsers?from=...&to=...`
- `GET /api/v1/links/{link_id}/analytics/locations?from=...&to=...`
- `GET /api/v1/links/{link_id}/access-logs?page=1&size=50`

## 5.5 Dashboard APIs

- `GET /api/v1/dashboard/summary?from=...&to=...`
- `GET /api/v1/dashboard/trends?granularity=1h&from=...&to=...`
- `GET /api/v1/dashboard/channels?from=...&to=...`
- `GET /api/v1/dashboard/locations?from=...&to=...`
- `GET /api/v1/analytics/realtime/overview?window=15m`
- `GET /api/v1/analytics/realtime/hot-links?window=15m&limit=10`

## 5.6 Risk Alerts and Review

- `GET /api/v1/risk/alerts?page=1&size=20&risk_level=high&status=pending&search=promo`
- `GET /api/v1/risk/alerts/{alert_id}`
- `POST /api/v1/risk/scan/tasks`
- `GET /api/v1/risk/scan/tasks/{task_id}`
- `POST /api/v1/admin/risk/alerts/{alert_id}/review` (admin)
- `POST /api/v1/admin/risk/blacklist/domain` (admin)
- `POST /api/v1/admin/risk/blacklist/url` (admin)

Review request:

```json
{
  "action": "blocked",
  "comment": "Confirmed phishing landing page"
}
```

`action` allowed values: `approved | blocked | blacklisted`

## 5.7 Monitoring APIs

- `GET /api/v1/monitoring/health`
- `GET /api/v1/monitoring/services`
- `GET /api/v1/monitoring/redis`
- `GET /api/v1/monitoring/kafka`
- `GET /api/v1/monitoring/rabbitmq`
- `GET /api/v1/monitoring/flink/jobs`
- `GET /api/v1/monitoring/metrics/timeseries?metric=qps&window=1h`

## 5.8 WebSocket Contracts

Handshake:

- `POST /api/v1/ws/token`

Realtime endpoint:

- `GET /ws/realtime?token=<ws_token>`

Server events:

- `analytics.realtime.updated`
- `analytics.hot_links.updated`
- `risk.alert.created`
- `ai.task.status_changed`
- `system.pipeline.degraded`

## 6. Async Message Contracts

## 6.1 Kafka Topics

- `linkflow.click.raw.v1`
- `linkflow.click.enriched.v1`
- `linkflow.link.lifecycle.v1`
- `linkflow.analytics.agg.1m.v1`
- `linkflow.analytics.hot.v1`

`linkflow.click.raw.v1` value:

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
  "is_bot": false
}
```

## 6.2 Flink Jobs

- `job_click_agg_1m`
- `job_hot_links_5m`
- `job_geo_channel_agg`
- `job_user_interest_profile`

Sink targets:

- PostgreSQL aggregate tables
- Redis hot cache
- Kafka aggregate topics (optional)

## 6.3 RabbitMQ

Exchanges:

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

## 7. Redis Key Design

- `lf:slug:{slug}` -> `{long_url, link_id, status, expires_at}`
- `lf:link:meta:{link_id}`
- `lf:analytics:realtime:overview:{window}`
- `lf:analytics:hot_links:{window}`
- `lf:user:recommendations:{user_id}`
- `lf:risk:blocklist:domain`
- `lf:risk:blocklist:url`

TTL guidance:

- Redirect mapping: 6h to 24h
- Realtime analytics: 30s to 2m
- Recommendations: 10m to 60m

## 8. Error Code Matrix

Authentication and authorization:

- `AUTH_INVALID_CREDENTIALS` -> 401
- `AUTH_TOKEN_EXPIRED` -> 401
- `PERMISSION_DENIED` -> 403

Link lifecycle:

- `LINK_SLUG_CONFLICT` -> 409
- `LINK_NOT_FOUND` -> 404
- `LINK_EXPIRED` -> 410
- `LINK_BLOCKED` -> 423

Risk/AI pipeline:

- `RISK_SCAN_FAILED` -> 502
- `AI_PROVIDER_UNAVAILABLE` -> 503

Platform and throttling:

- `KAFKA_PUBLISH_FAILED` -> 503
- `RABBITMQ_PUBLISH_FAILED` -> 503
- `FLINK_RESULT_UNAVAILABLE` -> 503
- `RATE_LIMITED` -> 429

## 9. Non-Functional Targets

1. Redirect path P95 < 80ms on Redis hit.
2. Redirect must stay non-blocking for logging and AI tasks.
3. Dashboard data freshness target: 5s to 30s.
4. Kafka/RabbitMQ pipelines support retry and replay.
5. All responses include `request_id` and async payloads include `event_id` for traceability.

## 10. Spring Boot Implementation Mapping (for backend coding)

Recommended package split:

- `com.linkflow.api.auth`
- `com.linkflow.api.link`
- `com.linkflow.api.analytics`
- `com.linkflow.api.risk`
- `com.linkflow.api.monitoring`

Contract-first suggestion:

1. Freeze DTOs in this schema first.
2. Generate OpenAPI yaml/json from controller annotations.
3. Keep API DTOs separated from JPA entities.
4. Use cursor/page DTO wrappers aligned to `meta.page`.
