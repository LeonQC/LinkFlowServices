# LinkFlow Database Schema (v2, PostgreSQL)

## 1. Design Goals

This database schema supports the following goals:

1. User and JWT-based permission management
2. Short URL creation and high-concurrency redirect support
3. Real-time analytics with Kafka + Flink outputs
4. AI link classification and recommendation persistence
5. Spam/malicious link detection and admin review
6. Dashboard and system monitoring data support

## 2. Extensions and Global Rules
中文说明：定义 PostgreSQL 扩展能力与全局建模约定。

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto; -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS citext;   -- case-insensitive email
```

Global conventions:

- Primary keys: UUID
- Time fields: TIMESTAMPTZ
- Soft delete field when needed: `deleted_at`
- High-volume event table: range partitioned by `occurred_at`

## 3. Auth and User Management
中文说明：实现用户身份、权限状态与刷新令牌存储。

### Table: `users`

```sql
CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email CITEXT UNIQUE NOT NULL,
  username VARCHAR(40) UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'user'
    CHECK (role IN ('user', 'admin')),
  status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active', 'suspended', 'deleted')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Table: `refresh_tokens`

```sql
CREATE TABLE refresh_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash TEXT UNIQUE NOT NULL,
  issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  ip INET,
  user_agent TEXT
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);
```

## 4. Short Link Core
中文说明：定义短链接主数据结构及索引策略。

### Table: `links`

```sql
CREATE TABLE links (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  slug VARCHAR(80) UNIQUE NOT NULL,
  long_url TEXT NOT NULL,
  title VARCHAR(300),
  channel VARCHAR(60), -- publish channel: twitter, wechat, tiktok, etc.
  status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active', 'paused', 'expired', 'blocked')),
  expires_at TIMESTAMPTZ,
  click_count BIGINT NOT NULL DEFAULT 0,
  risk_level TEXT NOT NULL DEFAULT 'unknown'
    CHECK (risk_level IN ('unknown', 'low', 'medium', 'high', 'critical')),
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  deleted_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_links_owner_created_at ON links(owner_user_id, created_at DESC);
CREATE INDEX idx_links_channel ON links(channel);
CREATE INDEX idx_links_status ON links(status);
```

### Table: `link_tags`

```sql
CREATE TABLE link_tags (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  tag VARCHAR(60) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (link_id, tag)
);
```

## 5. Redirect Event Logging (Kafka source data persisted)
中文说明：持久化重定向访问事件，作为流式分析源数据。

### Table: `click_events` (Partitioned)

```sql
CREATE TABLE click_events (
  occurred_at TIMESTAMPTZ NOT NULL,
  event_id UUID NOT NULL DEFAULT gen_random_uuid(),
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  slug VARCHAR(80) NOT NULL,
  ip INET,
  country CHAR(2),
  region VARCHAR(120),
  city VARCHAR(120),
  device_type TEXT NOT NULL DEFAULT 'unknown'
    CHECK (device_type IN ('desktop', 'mobile', 'tablet', 'bot', 'unknown')),
  os_name VARCHAR(100),
  browser_name VARCHAR(100),
  referrer TEXT,
  channel VARCHAR(60),
  utm_source VARCHAR(80),
  utm_medium VARCHAR(80),
  utm_campaign VARCHAR(120),
  session_id UUID,
  visitor_id UUID,
  is_bot BOOLEAN NOT NULL DEFAULT false,
  latency_ms INTEGER,
  kafka_partition INTEGER,
  kafka_offset BIGINT,
  PRIMARY KEY (occurred_at, event_id)
) PARTITION BY RANGE (occurred_at);
```

Example monthly partition:

```sql
CREATE TABLE click_events_2026_02
PARTITION OF click_events
FOR VALUES FROM ('2026-02-01 00:00:00+00') TO ('2026-03-01 00:00:00+00');

CREATE INDEX idx_click_events_2026_02_link_time
  ON click_events_2026_02(link_id, occurred_at DESC);
CREATE INDEX idx_click_events_2026_02_owner_time
  ON click_events_2026_02(owner_user_id, occurred_at DESC);
CREATE INDEX idx_click_events_2026_02_geo
  ON click_events_2026_02(country, region, city);
CREATE INDEX idx_click_events_2026_02_channel
  ON click_events_2026_02(channel);
```

## 6. Flink Output Aggregates (for dashboard)
中文说明：存储 Flink 计算后的聚合结果，支撑实时看板查询。

### Table: `agg_clicks_1m`

```sql
CREATE TABLE agg_clicks_1m (
  bucket_start TIMESTAMPTZ NOT NULL,
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  click_count BIGINT NOT NULL DEFAULT 0,
  unique_visitors BIGINT NOT NULL DEFAULT 0,
  bot_clicks BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (bucket_start, link_id)
);

CREATE INDEX idx_agg_clicks_1m_owner_time
  ON agg_clicks_1m(owner_user_id, bucket_start DESC);
```

### Table: `agg_hot_links_5m`

```sql
CREATE TABLE agg_hot_links_5m (
  bucket_start TIMESTAMPTZ NOT NULL,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  rank_no INTEGER NOT NULL,
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  click_count BIGINT NOT NULL,
  PRIMARY KEY (bucket_start, owner_user_id, rank_no)
);
```

### Table: `agg_location_1h`

```sql
CREATE TABLE agg_location_1h (
  bucket_start TIMESTAMPTZ NOT NULL,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  country CHAR(2) NOT NULL,
  region VARCHAR(120),
  city VARCHAR(120),
  click_count BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (bucket_start, owner_user_id, country, region, city)
);
```

### Table: `agg_channel_1h`

```sql
CREATE TABLE agg_channel_1h (
  bucket_start TIMESTAMPTZ NOT NULL,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  channel VARCHAR(60) NOT NULL,
  click_count BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (bucket_start, owner_user_id, channel)
);
```

## 7. AI Link Classification
中文说明：管理链接分类任务生命周期与分类结果。

### Table: `ai_classification_tasks`

```sql
CREATE TABLE ai_classification_tasks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider TEXT NOT NULL
    CHECK (provider IN ('huggingface', 'custom')),
  model_name VARCHAR(160) NOT NULL,
  status TEXT NOT NULL DEFAULT 'queued'
    CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
  error_message TEXT,
  queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ
);

CREATE INDEX idx_ai_classification_tasks_owner
  ON ai_classification_tasks(owner_user_id, queued_at DESC);
```

### Table: `ai_classification_results`

```sql
CREATE TABLE ai_classification_results (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id UUID NOT NULL REFERENCES ai_classification_tasks(id) ON DELETE CASCADE,
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  label VARCHAR(120) NOT NULL,          -- news, social, shopping, etc.
  confidence NUMERIC(6,5) NOT NULL,
  explanation JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_classification_results_link
  ON ai_classification_results(link_id, confidence DESC);
```

## 8. AI Recommendation
中文说明：管理用户兴趣画像、推荐任务、推荐结果与反馈行为。

### Table: `user_interest_profiles`

```sql
CREATE TABLE user_interest_profiles (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  vector JSONB NOT NULL DEFAULT '{}'::jsonb,  -- category weights
  source_window_days INTEGER NOT NULL DEFAULT 30,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Table: `ai_recommendation_tasks`

```sql
CREATE TABLE ai_recommendation_tasks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider TEXT NOT NULL CHECK (provider IN ('huggingface', 'custom')),
  model_name VARCHAR(160) NOT NULL,
  status TEXT NOT NULL DEFAULT 'queued'
    CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
  error_message TEXT,
  queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ
);
```

### Table: `ai_recommendation_items`

```sql
CREATE TABLE ai_recommendation_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id UUID NOT NULL REFERENCES ai_recommendation_tasks(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recommended_link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  score NUMERIC(8,6) NOT NULL,
  reason JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (task_id, recommended_link_id)
);

CREATE INDEX idx_ai_recommendation_items_user
  ON ai_recommendation_items(user_id, created_at DESC);
```

### Table: `recommendation_feedback`

```sql
CREATE TABLE recommendation_feedback (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recommendation_item_id UUID NOT NULL REFERENCES ai_recommendation_items(id) ON DELETE CASCADE,
  action TEXT NOT NULL CHECK (action IN ('viewed', 'clicked', 'dismissed', 'saved')),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## 9. Spam / Malicious Link Detection
中文说明：沉淀风险扫描、黑名单与告警审计数据。

### Table: `risk_scan_tasks`

```sql
CREATE TABLE risk_scan_tasks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  providers TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[], -- huggingface, google_safe_browsing
  status TEXT NOT NULL DEFAULT 'queued'
    CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
  queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ,
  error_message TEXT
);
```

### Table: `risk_scan_results`

```sql
CREATE TABLE risk_scan_results (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id UUID NOT NULL REFERENCES risk_scan_tasks(id) ON DELETE CASCADE,
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  provider TEXT NOT NULL,
  model_name VARCHAR(160),
  risk_label TEXT NOT NULL
    CHECK (risk_label IN ('safe', 'suspicious', 'malicious')),
  risk_score NUMERIC(6,5) NOT NULL,
  reason_codes TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  raw_response JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_risk_scan_results_link
  ON risk_scan_results(link_id, created_at DESC);
```

### Table: `risk_blacklist_domains`

```sql
CREATE TABLE risk_blacklist_domains (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  domain VARCHAR(255) UNIQUE NOT NULL,
  source TEXT NOT NULL CHECK (source IN ('manual', 'huggingface', 'google_safe_browsing')),
  reason TEXT,
  created_by UUID REFERENCES users(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Table: `risk_blacklist_urls`

```sql
CREATE TABLE risk_blacklist_urls (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  normalized_url TEXT UNIQUE NOT NULL,
  source TEXT NOT NULL CHECK (source IN ('manual', 'huggingface', 'google_safe_browsing')),
  reason TEXT,
  created_by UUID REFERENCES users(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Table: `risk_alerts`

```sql
CREATE TABLE risk_alerts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  severity TEXT NOT NULL CHECK (severity IN ('medium', 'high', 'critical')),
  title VARCHAR(200) NOT NULL,
  message TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'open'
    CHECK (status IN ('open', 'acknowledged', 'resolved')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at TIMESTAMPTZ
);

CREATE INDEX idx_risk_alerts_status_time
  ON risk_alerts(status, created_at DESC);
```

## 10. RabbitMQ and WebSocket Delivery Tracking
中文说明：记录异步任务投递状态与 WebSocket 广播日志。

### Table: `async_job_dispatches`

```sql
CREATE TABLE async_job_dispatches (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  job_type TEXT NOT NULL
    CHECK (job_type IN ('classification', 'recommendation', 'risk_scan', 'ws_broadcast')),
  job_ref_id UUID, -- task id or alert id
  exchange_name VARCHAR(120) NOT NULL,
  routing_key VARCHAR(120) NOT NULL,
  status TEXT NOT NULL DEFAULT 'queued'
    CHECK (status IN ('queued', 'sent', 'failed', 'dead_letter')),
  retry_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Table: `websocket_broadcast_logs`

```sql
CREATE TABLE websocket_broadcast_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_name VARCHAR(120) NOT NULL,
  payload JSONB NOT NULL,
  audience_type TEXT NOT NULL CHECK (audience_type IN ('user', 'admin', 'all')),
  audience_ref UUID, -- user_id when audience_type=user
  sent_count INTEGER NOT NULL DEFAULT 0,
  failed_count INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## 11. Monitoring and Pipeline Health
中文说明：存储系统指标、Kafka lag 与 Flink 任务健康快照。

### Table: `system_metric_snapshots`

```sql
CREATE TABLE system_metric_snapshots (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  metric_time TIMESTAMPTZ NOT NULL,
  service_name VARCHAR(100) NOT NULL, -- api, redis, kafka, flink, rabbitmq
  metric_key VARCHAR(120) NOT NULL,   -- cpu_usage, memory_usage, redis_hit_rate, etc.
  metric_value NUMERIC(18,6) NOT NULL,
  tags JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_system_metric_snapshots_service_time
  ON system_metric_snapshots(service_name, metric_time DESC);
```

### Table: `kafka_consumer_lag_snapshots`

```sql
CREATE TABLE kafka_consumer_lag_snapshots (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  snapshot_time TIMESTAMPTZ NOT NULL,
  consumer_group VARCHAR(160) NOT NULL,
  topic VARCHAR(160) NOT NULL,
  partition_no INTEGER NOT NULL,
  lag BIGINT NOT NULL
);

CREATE INDEX idx_kafka_lag_group_time
  ON kafka_consumer_lag_snapshots(consumer_group, snapshot_time DESC);
```

### Table: `flink_job_snapshots`

```sql
CREATE TABLE flink_job_snapshots (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  snapshot_time TIMESTAMPTZ NOT NULL,
  job_id VARCHAR(120) NOT NULL,
  job_name VARCHAR(160) NOT NULL,
  state VARCHAR(40) NOT NULL, -- RUNNING, FAILED, CANCELED, etc.
  records_in_per_sec NUMERIC(18,6),
  records_out_per_sec NUMERIC(18,6),
  checkpoint_failed_count INTEGER,
  backpressure_level VARCHAR(40)
);

CREATE INDEX idx_flink_job_snapshots_job_time
  ON flink_job_snapshots(job_id, snapshot_time DESC);
```

## 12. Relationships Summary
中文说明：汇总核心实体关系，便于后端与数据团队统一理解。

1. `users` 1:N `links`
2. `users` 1:N `refresh_tokens`
3. `links` 1:N `click_events` (partitioned)
4. `links` 1:N `ai_classification_tasks` 1:N `ai_classification_results`
5. `users` 1:1 `user_interest_profiles`
6. `users` 1:N `ai_recommendation_tasks` 1:N `ai_recommendation_items`
7. `links` 1:N `risk_scan_tasks` 1:N `risk_scan_results`
8. `links` 1:N `risk_alerts`
9. Flink outputs feed aggregate tables (`agg_clicks_1m`, `agg_hot_links_5m`, `agg_location_1h`, `agg_channel_1h`)

## 13. Redis Mapping (Not stored in PostgreSQL)
中文说明：定义缓存键空间，提升重定向和看板读取性能。

Recommended key spaces:

- `lf:slug:{slug}` for redirect mapping
- `lf:analytics:*` for realtime dashboard data
- `lf:user:recommendations:{user_id}` for recommendation cache
- `lf:risk:blacklist:*` for fast risk checks

## 14. Data Retention and Operations
中文说明：定义事件、聚合与监控数据的保留和运维策略。

1. `click_events` partitions retained for 90 to 180 days (environment configurable).
2. Aggregate tables retained longer for dashboard trends.
3. AI task and result tables keep full audit history for traceability.
4. Monitoring snapshots can be downsampled after 30 days.
