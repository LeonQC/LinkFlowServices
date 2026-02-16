# LinkFlow Architecture Overview

## 1. Repositories

- `LinkFlowServices`: backend services, streaming, AI, infra definitions
- `LinkFlow-UI`: React frontend and API integration

## 2. Core Runtime Flow

1. User creates short link via Spring Boot API.
2. Redirect path reads `slug -> long_url` from Redis first.
3. Click event is published to Kafka asynchronously.
4. Flink consumes click stream and writes aggregates.
5. Dashboard APIs read from aggregates + Redis hot cache.
6. AI tasks are sent to RabbitMQ and processed asynchronously.
7. Risk alerts are pushed to admins via WebSocket.

## 3. Service Boundaries

- `gateway`: authentication, rate limiting, request routing
- `user-service`: user identity and JWT lifecycle
- `link-service`: short link lifecycle and redirect logic
- `analytics-service`: metrics query and dashboard endpoints
- `ai-service`: classification, recommendation, risk scanning

## 4. Data Layers

- PostgreSQL: source of truth and aggregates
- Redis: hot cache and low-latency redirect lookup
- Kafka: click and lifecycle event stream
- Flink: stream analytics and rolling aggregates
- RabbitMQ: async AI workloads and notifications
