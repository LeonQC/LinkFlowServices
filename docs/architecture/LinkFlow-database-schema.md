# LinkFlow Database Schema (v3, PostgreSQL)

## 1. Purpose and Scope

This schema supports LinkFlow backend requirements from the UI and architecture documents:

- Auth and JWT session lifecycle
- Short link CRUD and high-concurrency redirect
- Per-link analytics and dashboard queries
- AI classification/recommendation/risk workflows
- Risk alert review and blacklist operations
- Monitoring and async delivery observability

## 2. Extensions and Global Conventions

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto; -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS citext;   -- case-insensitive email
CREATE EXTENSION IF NOT EXISTS pg_trgm;  -- fuzzy search for title/slug/url
```

Global rules:

- Primary keys: UUID
- Time columns: TIMESTAMPTZ
- Enum-like columns: `TEXT + CHECK` for migration flexibility
- Soft delete where needed: `deleted_at`
- Optimistic locking: `version BIGINT NOT NULL DEFAULT 0` on mutable core tables
- High-volume events: range partition on `occurred_at`

## 3. Core Auth and User Tables

### 3.1 users

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

CREATE INDEX idx_users_role_status ON users(role, status);
```

### 3.2 refresh_tokens

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

## 4. Link Lifecycle Tables

### 4.1 links

```sql
CREATE TABLE links (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  slug VARCHAR(80) NOT NULL,
  long_url TEXT NOT NULL,
  title VARCHAR(300) NOT NULL,
  channel VARCHAR(60),
  status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active', 'paused', 'expired', 'blocked')),
  expires_at TIMESTAMPTZ,
  click_count BIGINT NOT NULL DEFAULT 0,
  unique_visitor_count BIGINT NOT NULL DEFAULT 0,
  risk_level TEXT NOT NULL DEFAULT 'unknown'
    CHECK (risk_level IN ('unknown', 'low', 'medium', 'high', 'critical')),
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  deleted_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT uk_links_slug UNIQUE (slug),
  CONSTRAINT ck_links_slug_format CHECK (slug ~ '^[A-Za-z0-9][A-Za-z0-9_-]{2,79}$')
);

CREATE INDEX idx_links_owner_created_at ON links(owner_user_id, created_at DESC);
CREATE INDEX idx_links_owner_status_created ON links(owner_user_id, status, created_at DESC);
CREATE INDEX idx_links_channel ON links(channel);
CREATE INDEX idx_links_expires_at ON links(expires_at);
CREATE INDEX idx_links_search_title ON links USING GIN (title gin_trgm_ops);
CREATE INDEX idx_links_search_slug ON links USING GIN (slug gin_trgm_ops);
```

### 4.2 link_tags

```sql
CREATE TABLE link_tags (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  tag VARCHAR(60) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (link_id, tag)
);

CREATE INDEX idx_link_tags_tag ON link_tags(tag);
```

### 4.3 link_status_history (audit)

```sql
CREATE TABLE link_status_history (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  old_status TEXT NOT NULL,
  new_status TEXT NOT NULL,
  changed_by UUID REFERENCES users(id) ON DELETE SET NULL,
  reason TEXT,
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_link_status_history_link_time
  ON link_status_history(link_id, changed_at DESC);
```

### 4.4 link_qr_assets (optional pre-generated QR metadata)

```sql
CREATE TABLE link_qr_assets (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  format TEXT NOT NULL CHECK (format IN ('png', 'svg')),
  size_px INTEGER NOT NULL CHECK (size_px IN (256, 512, 1024)),
  storage_url TEXT NOT NULL,
  generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (link_id, format, size_px)
);
```

## 5. Event and Analytics Tables

### 5.1 click_events (partitioned)

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

### 5.2 Flink aggregate tables

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

CREATE TABLE agg_hot_links_5m (
  bucket_start TIMESTAMPTZ NOT NULL,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  rank_no INTEGER NOT NULL,
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  click_count BIGINT NOT NULL,
  PRIMARY KEY (bucket_start, owner_user_id, rank_no)
);

CREATE TABLE agg_location_1h (
  bucket_start TIMESTAMPTZ NOT NULL,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  country CHAR(2) NOT NULL,
  region VARCHAR(120),
  city VARCHAR(120),
  click_count BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (bucket_start, owner_user_id, country, region, city)
);

CREATE TABLE agg_channel_1h (
  bucket_start TIMESTAMPTZ NOT NULL,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  channel VARCHAR(60) NOT NULL,
  click_count BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (bucket_start, owner_user_id, channel)
);

CREATE TABLE agg_device_1h (
  bucket_start TIMESTAMPTZ NOT NULL,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  link_id UUID REFERENCES links(id) ON DELETE CASCADE,
  device_type TEXT NOT NULL,
  click_count BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (bucket_start, owner_user_id, link_id, device_type)
);

CREATE TABLE agg_browser_1h (
  bucket_start TIMESTAMPTZ NOT NULL,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  link_id UUID REFERENCES links(id) ON DELETE CASCADE,
  browser_name VARCHAR(100) NOT NULL,
  click_count BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (bucket_start, owner_user_id, link_id, browser_name)
);
```

## 6. AI and Recommendation Tables

```sql
CREATE TABLE ai_classification_tasks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider TEXT NOT NULL CHECK (provider IN ('huggingface', 'custom')),
  model_name VARCHAR(160) NOT NULL,
  status TEXT NOT NULL DEFAULT 'queued'
    CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
  error_message TEXT,
  queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ
);

CREATE TABLE ai_classification_results (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id UUID NOT NULL REFERENCES ai_classification_tasks(id) ON DELETE CASCADE,
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  label VARCHAR(120) NOT NULL,
  confidence NUMERIC(6,5) NOT NULL,
  explanation JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_interest_profiles (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  vector JSONB NOT NULL DEFAULT '{}'::jsonb,
  source_window_days INTEGER NOT NULL DEFAULT 30,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

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

CREATE TABLE recommendation_feedback (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recommendation_item_id UUID NOT NULL REFERENCES ai_recommendation_items(id) ON DELETE CASCADE,
  action TEXT NOT NULL CHECK (action IN ('viewed', 'clicked', 'dismissed', 'saved')),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## 7. Risk Management Tables

```sql
CREATE TABLE risk_scan_tasks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  providers TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  status TEXT NOT NULL DEFAULT 'queued'
    CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
  queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ,
  error_message TEXT
);

CREATE TABLE risk_scan_results (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id UUID NOT NULL REFERENCES risk_scan_tasks(id) ON DELETE CASCADE,
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  provider TEXT NOT NULL,
  model_name VARCHAR(160),
  risk_label TEXT NOT NULL CHECK (risk_label IN ('safe', 'suspicious', 'malicious')),
  risk_score NUMERIC(6,5) NOT NULL,
  reason_codes TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  raw_response JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE risk_alerts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  link_id UUID NOT NULL REFERENCES links(id) ON DELETE CASCADE,
  owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  severity TEXT NOT NULL CHECK (severity IN ('low', 'medium', 'high', 'critical')),
  risk_score INTEGER NOT NULL CHECK (risk_score BETWEEN 0 AND 100),
  title VARCHAR(200) NOT NULL,
  message TEXT NOT NULL,
  reasons TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  status TEXT NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending', 'approved', 'blocked', 'blacklisted')),
  reporter VARCHAR(120) NOT NULL DEFAULT 'ai-risk-engine',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_risk_alerts_status_time
  ON risk_alerts(status, created_at DESC);
CREATE INDEX idx_risk_alerts_owner_time
  ON risk_alerts(owner_user_id, created_at DESC);

CREATE TABLE risk_alert_reviews (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  alert_id UUID NOT NULL REFERENCES risk_alerts(id) ON DELETE CASCADE,
  action TEXT NOT NULL CHECK (action IN ('approved', 'blocked', 'blacklisted')),
  comment TEXT,
  reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL,
  reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE risk_blacklist_domains (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  domain VARCHAR(255) UNIQUE NOT NULL,
  source TEXT NOT NULL CHECK (source IN ('manual', 'huggingface', 'google_safe_browsing')),
  reason TEXT,
  created_by UUID REFERENCES users(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE risk_blacklist_urls (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  normalized_url TEXT UNIQUE NOT NULL,
  source TEXT NOT NULL CHECK (source IN ('manual', 'huggingface', 'google_safe_browsing')),
  reason TEXT,
  created_by UUID REFERENCES users(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## 8. Async Delivery and Monitoring Tables

```sql
CREATE TABLE async_job_dispatches (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  job_type TEXT NOT NULL
    CHECK (job_type IN ('classification', 'recommendation', 'risk_scan', 'ws_broadcast')),
  job_ref_id UUID,
  exchange_name VARCHAR(120) NOT NULL,
  routing_key VARCHAR(120) NOT NULL,
  status TEXT NOT NULL DEFAULT 'queued'
    CHECK (status IN ('queued', 'sent', 'failed', 'dead_letter')),
  retry_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE websocket_broadcast_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_name VARCHAR(120) NOT NULL,
  payload JSONB NOT NULL,
  audience_type TEXT NOT NULL CHECK (audience_type IN ('user', 'admin', 'all')),
  audience_ref UUID,
  sent_count INTEGER NOT NULL DEFAULT 0,
  failed_count INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE system_metric_snapshots (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  metric_time TIMESTAMPTZ NOT NULL,
  service_name VARCHAR(100) NOT NULL,
  metric_key VARCHAR(120) NOT NULL,
  metric_value NUMERIC(18,6) NOT NULL,
  tags JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_system_metric_snapshots_service_time
  ON system_metric_snapshots(service_name, metric_time DESC);

CREATE TABLE kafka_consumer_lag_snapshots (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  snapshot_time TIMESTAMPTZ NOT NULL,
  consumer_group VARCHAR(160) NOT NULL,
  topic VARCHAR(160) NOT NULL,
  partition_no INTEGER NOT NULL,
  lag BIGINT NOT NULL
);

CREATE TABLE flink_job_snapshots (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  snapshot_time TIMESTAMPTZ NOT NULL,
  job_id VARCHAR(120) NOT NULL,
  job_name VARCHAR(160) NOT NULL,
  state VARCHAR(40) NOT NULL,
  records_in_per_sec NUMERIC(18,6),
  records_out_per_sec NUMERIC(18,6),
  checkpoint_failed_count INTEGER,
  backpressure_level VARCHAR(40)
);
```

## 9. Query-Driven Index Strategy (UI alignment)

Links page (`/links`):

- Filter by `owner_user_id + status`
- Sort by `created_at desc`
- Search by `title/slug`

Risk alerts page (`/alerts`):

- Filter by `status`, `severity`, `owner_user_id`
- Sort by `created_at desc`

Link detail page (`/links/{id}`):

- Time window scans on `click_events` partition indexes
- Fast reads from aggregate tables for charts

Monitoring page (`/monitoring`):

- Latest metrics by `service_name + metric_time desc`

## 10. Data Retention and Operations

1. `click_events`: keep 90 to 180 days via partition drop.
2. `agg_*` tables: keep 12 to 24 months depending on dashboard depth.
3. `risk_alert_reviews`, `ai_*_tasks/results`: keep full audit history.
4. Downsample `system_metric_snapshots` after 30 days.

Operational recommendations:

- Monthly partition creation job for `click_events`
- Scheduled `ANALYZE` on hot partitions
- Archive old partitions before drop in production

## 11. Spring Data JPA Mapping Notes

Recommended mappings:

- `links.version` -> `@Version` for optimistic locking
- `status`, `role`, `risk_level` -> enum stored as `EnumType.STRING`
- `metadata` / `reason` / `tags` -> JSONB mapped with Hibernate JSON type
- `click_events` writes can use `JdbcTemplate` or batch insert (high throughput path)

Module/entity split suggestion:

- `user-service`: `User`, `RefreshToken`
- `link-service`: `Link`, `LinkTag`, `LinkStatusHistory`, `ClickEvent`
- `analytics-service`: `AggClicks1m`, `AggHotLinks5m`, `AggLocation1h`, `AggChannel1h`
- `risk-service`: `RiskScanTask`, `RiskScanResult`, `RiskAlert`, `RiskAlertReview`, blacklist tables

## 12. Relationship Summary

1. `users` 1:N `links`
2. `users` 1:N `refresh_tokens`
3. `links` 1:N `link_tags`
4. `links` 1:N `link_status_history`
5. `links` 1:N `click_events` (partitioned)
6. `links` 1:N `ai_classification_tasks` 1:N `ai_classification_results`
7. `users` 1:1 `user_interest_profiles`
8. `users` 1:N `ai_recommendation_tasks` 1:N `ai_recommendation_items`
9. `links` 1:N `risk_scan_tasks` 1:N `risk_scan_results`
10. `links` 1:N `risk_alerts` 1:N `risk_alert_reviews`
