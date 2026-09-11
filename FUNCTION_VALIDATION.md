# 功能验证

这份文档给出从启动到端到端验证服务全部能力的完整步骤。**应用启动成功后**（看到 `Started NotificationApplication`），按顺序跑下面的命令即可。

> 假设应用监听在 `http://localhost:8080`。如果不是，改对应端口即可。

---

## 目录

- [0. 准备工作](#0-准备工作)
- [1. 健康检查](#1-健康检查)
- [2. 入队一条通知（主流程）](#2-入队一条通知主流程)
- [3. 查询通知状态](#3-查询通知状态)
- [4. 幂等性：相同 Key 返回相同结果](#4-幂等性相同-key-返回相同结果)
- [5. 幂等冲突：同 Key 不同 payload → 409](#5-幂等冲突同-key-不同-payload--409)
- [6. 输入校验](#6-输入校验)
- [7. 失败投递 + 重试退避](#7-失败投递--重试退避)
- [8. 直接查数据库（可选）](#8-直接查数据库可选)
- [9. 指标与日志](#9-指标与日志)
- [关键观测点速查](#关键观测点速查)
- [常见问题](#常见问题)

---

## 0. 准备工作

### 0.1 准备一个接收回调的端点

通知服务会异步向 `targetUrl` 发 HTTP 请求，所以测试时需要一个能看见请求的目标。两种推荐方式：

**A. webhook.site（推荐，无需安装）**

打开 https://webhook.site ，页面顶部会给一个唯一 URL，形式如：

```
https://webhook.site/xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
```

页面会实时展示收到的请求（方法、Header、Body）。把这段 URL 记下来，下面命令里用 `$WH_URL` 代替。

**B. 本地 echo 服务（需要 Node.js）**

```bash
npx -y http-echo-server 8088
```

它会监听 `http://localhost:8088/`，返回 200 并在终端打印请求详情。下面用 `http://localhost:8088/notify` 之类的路径。

### 0.2 一个可触发失败的端点（可选，第 7 步用）

`httpbin.org` 是个好选择：

- `https://httpbin.org/status/503` —— 永远返回 503，触发重试。
- `https://httpbin.org/status/404` —— 永远返回 404，被视为永久失败。
- `https://httpbin.org/delay/30` —— 触发请求超时。

---

## 1. 健康检查

确认应用就绪、数据库可连：

```bash
# 基础存活
curl http://localhost:8080/livez
# 期望: {"status":"UP"}

# 就绪（含数据库连通）
curl http://localhost:8080/readyz
# 期望: {"status":"UP"}

# Spring Boot Actuator 综合健康
curl http://localhost:8080/actuator/health
# 期望: {"status":"UP"}
```

任何一个不是 `UP`，先别往下走，看应用日志排查。

---

## 2. 入队一条通知（主流程）

```bash
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-key-001" \
  -d '{
    "targetUrl": "https://webhook.site/你的-uuid/notify",
    "headers": {"X-Custom-Header": "hello"},
    "body": {"orderId": "A-1024", "amount": 99}
  }'
```

**期望结果**：

- HTTP 状态码：`202 Accepted`
- `Location` 响应头：`/v1/notifications/<uuid>`
- 响应体（JSON）大致：

```json
{
  "id": "c1f2e6c0-1d2a-4b3c-9d4e-5f6a7b8c9d0e",
  "status": "PENDING",
  "attempts": 0,
  "lastHttpStatus": null,
  "lastError": null,
  "createdAt": "2026-09-11T12:00:00Z",
  "updatedAt": "2026-09-11T12:00:00Z",
  "completedAt": null,
  "nextAttemptAt": "2026-09-11T12:00:00.500Z",
  "statusUrl": "/v1/notifications/c1f2e6c0-1d2a-4b3c-9d4e-5f6a7b8c9d0e"
}
```

**同时观察**：

- webhook.site 页面应该很快（默认 500ms 轮询）收到一个 POST 请求。
- 自定义 Header `X-Custom-Header: hello` 和 JSON body 应当可见。
- 应用日志（IDEA Run 窗口 / `docker compose logs -f app`）应该能看到 `DeliveryWorker` 的输出：`POST ... 200`，`notification ... SUCCEEDED`。

把响应里的 `id` 复制下来，下一步用到。也可以从 `statusUrl` 里截取。

---

## 3. 查询通知状态

```bash
# 把 <id> 替换成上一步拿到的 UUID
curl http://localhost:8080/v1/notifications/<id>
```

几秒后查询，应该看到：

```json
{
  "id": "c1f2e6c0-1d2a-4b3c-9d4e-5f6a7b8c9d0e",
  "status": "SUCCEEDED",
  "attempts": 1,
  "lastHttpStatus": 200,
  "lastError": null,
  "createdAt": "...",
  "updatedAt": "...",
  "completedAt": "...",
  "nextAttemptAt": "...",
  "statusUrl": "/v1/notifications/c1f2e6c0-1d2a-4b3c-9d4e-5f6a7b8c9d0e"
}
```

| 字段 | 期望 |
| --- | --- |
| `status` | `SUCCEEDED` |
| `attempts` | `1` |
| `lastHttpStatus` | `200`（如果 webhook.site 是 200）或 `2xx` |
| `completedAt` | 不为空 |

如果 status 一直 `PENDING`：

- 看应用日志里的 worker 输出，确认有 `POST ...` 调用。
- 看目标 URL 是否真的从你机器能访问到（先用浏览器或 curl 测一下）。
- 看 webhook.site 上是否真的收到（如果它没收到，可能是被中间网络阻断了）。

---

## 4. 幂等性：相同 Key 返回相同结果

用相同 `Idempotency-Key` 和相同 payload 提两次：

```bash
# 第一次
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-key-001" \
  -d '{
    "targetUrl": "https://webhook.site/你的-uuid/notify",
    "headers": {"X-Custom-Header": "hello"},
    "body": {"orderId": "A-1024", "amount": 99}
  }'

# 第二次（payload 完全一样）
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-key-001" \
  -d '{
    "targetUrl": "https://webhook.site/你的-uuid/notify",
    "headers": {"X-Custom-Header": "hello"},
    "body": {"orderId": "A-1024", "amount": 99}
  }'
```

**期望**：

- 两次的 HTTP 状态码都是 `202 Accepted`。
- 两次返回的 `id` **完全相同**。
- webhook.site 只看到 **1 个** POST 请求（说明 Worker 没重复投递）。

---

## 5. 幂等冲突：同 Key 不同 payload → 409

```bash
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-key-001" \
  -d '{
    "targetUrl": "https://webhook.site/你的-uuid/notify",
    "headers": {"X-Custom-Header": "hello"},
    "body": {"orderId": "DIFFERENT"}
  }'
```

**期望**：

- HTTP `409 Conflict`。
- 错误信息含：`Idempotency-Key was already used for a different request`。

---

## 6. 输入校验

每个 case 都预期返回 `4xx`，且错误信息明确。

```bash
# 6.1 缺少 Idempotency-Key
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{"targetUrl":"https://example.com"}'
# 期望: 400

# 6.2 targetUrl 不是 http(s)
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: bad-url-1" \
  -d '{"targetUrl":"ftp://example.com/x"}'
# 期望: 400

# 6.3 targetUrl 是相对路径
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: bad-url-2" \
  -d '{"targetUrl":"/foo/bar"}'
# 期望: 400

# 6.4 保留头 Host
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: bad-header-host" \
  -d '{"targetUrl":"https://example.com","headers":{"Host":"evil.com"}}'
# 期望: 400 "invalid header"

# 6.5 保留头 Content-Length
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: bad-header-cl" \
  -d '{"targetUrl":"https://example.com","headers":{"Content-Length":"100"}}'
# 期望: 400

# 6.6 header value 含换行
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: bad-header-cr" \
  --data-raw '{"targetUrl":"https://example.com","headers":{"X-A":"a\r\nb"}}'
# 期望: 400

# 6.7 header 数量超过上限（默认 32）
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: too-many-headers" \
  -d "$(printf '{"targetUrl":"https://example.com","headers":{'; for i in $(seq 1 40); do printf '"X-H-%d":"v",' $i; done; printf '"X-Last":"v"}}')"
# 期望: 400 "too many headers"

# 6.8 body 超大（默认 1 MiB）
# Windows PowerShell 较难一行构造，用 bash / Git Bash：
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: big-body-1" \
  --data-binary @<(printf '{"targetUrl":"https://example.com","body":{"x":"%s"}}' "$(head -c 2000000 < /dev/zero | tr '\0' 'a')")
# 期望: 413

# 6.9 Idempotency-Key 过长（>128 字符）
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(printf 'a%.0s' {1..200})" \
  -d '{"targetUrl":"https://example.com"}'
# 期望: 400

# 6.10 targetUrl 超长（>2048 字符）
LONG=$(printf 'a%.0s' {1..2100})
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: long-url-1" \
  -d "{\"targetUrl\":\"https://example.com/${LONG}\"}"
# 期望: 400
```

---

## 7. 失败投递 + 重试退避

目标 URL 返回 5xx，服务应该按配置退避并重试，`attempts` 累加。

```bash
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: retry-key-001" \
  -d '{
    "targetUrl": "https://httpbin.org/status/503",
    "body": {"x": 1}
  }'
```

记录返回的 `id`，然后周期性查询：

```bash
curl http://localhost:8080/v1/notifications/<id>
```

**期望**：

- `status` 一开始 `PENDING`，`attempts` 逐渐增长，`lastHttpStatus: 503`，`lastError` 简短描述失败原因，`nextAttemptAt` 在未来。
- 退避默认从 1 秒开始，最多到 5 分钟，所以查几次会看到 `attempts` 从 1 → 2 → 3 → ...。
- 达到 `NOTIFICATION_MAX_ATTEMPTS`（默认 8 次）后变 `FAILED`，不再尝试。

**永久失败（4xx）**：

```bash
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: perm-fail-1" \
  -d '{
    "targetUrl": "https://httpbin.org/status/404",
    "body": {"x": 1}
  }'
```

**期望**：`status: FAILED`，`attempts: 1`，不再重试（4xx 视为永久失败）。

**超时**：

```bash
curl -i -X POST http://localhost:8080/v1/notifications \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: timeout-1" \
  -d '{
    "targetUrl": "https://httpbin.org/delay/30",
    "body": {"x": 1}
  }'
```

**期望**：`lastError` 类似 `request timeout`，`attempts` 累加（超时被视为可重试错误）。

---

## 8. 直接查数据库（可选）

### 8.1 用 psql

```bash
# 容器里
docker compose exec postgres psql -U notifications -d notifications

# 或本机 psql
psql -h localhost -U notifications -d notifications
# 密码 notifications
```

```sql
-- 所有通知
SELECT id, idempotency_key, status, attempts, last_http_status, next_attempt_at
FROM notifications
ORDER BY created_at DESC
LIMIT 20;

-- 状态分布
SELECT status, count(*) FROM notifications GROUP BY status;

-- 某条详情
SELECT * FROM notifications WHERE id = '<id>';

-- 看 worker 是否在处理：lease_token / lease_until 不为空且未过期
SELECT id, status, lease_token, lease_until
FROM notifications
WHERE status = 'DELIVERING';
```

### 8.2 关键字段

| 字段 | 含义 |
| --- | --- |
| `id` | 通知主键 UUID |
| `idempotency_key` | 幂等键 |
| `request_hash` | 请求内容 SHA-256（防同 key 不同 payload） |
| `status` | `PENDING` / `DELIVERING` / `SUCCEEDED` / `FAILED` |
| `attempts` | 已尝试次数 |
| `lease_token` / `lease_until` | Worker 领取后的租约（防重复领取） |
| `last_http_status` | 上次远端 HTTP 状态 |
| `last_error` | 脱敏后的错误摘要 |
| `next_attempt_at` | 下次可投递时间 |

---

## 9. 指标与日志

### 9.1 Actuator 指标

```bash
curl http://localhost:8080/actuator/metrics | head -c 2000
```

常用指标：

```bash
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active
curl http://localhost:8080/actuator/metrics/jdbc.connections.active
curl http://localhost:8080/actuator/metrics/jvm.memory.used
```

### 9.2 应用日志

```bash
# Docker 方式
docker compose logs -f app

# IDEA 启动时直接看 Run 窗口
```

**关注日志**：

- `DeliveryWorker ... POST <url> ... <status>`：每次出站投递。
- `Successfully applied 1 migration`：启动时 Flyway 跑迁移（每次启动只会跑一次）。
- `UnsatisfiedDependencyException` / `FlywaySqlException`：启动失败。
- `Connection refused`：Postgres 连不上。

---

## 关键观测点速查

| 验证目标 | 怎么验证 |
| --- | --- |
| 入队 + 异步投递 | webhook.site 收到请求 + GET 返回 SUCCEEDED |
| 幂等 | 相同 Key 第二次请求返回相同 `id`,webhook 只收到一次 |
| 幂等冲突 | 同 Key 不同 payload → 409 |
| 输入校验 | 各非法 payload → 400 / 413 |
| 失败重试 | httpbin 5xx → `attempts` 递增、`nextAttemptAt` 推进 |
| 永久失败 | httpbin 404 → 一次后 FAILED |
| 超时 | httpbin delay/30 → `lastError` 含 timeout |
| 持久化 | psql 查表有数据 |
| 健康检查 | `/livez`、`/readyz`、`/actuator/health` 都 UP |
| Worker lease | `SELECT ... WHERE status='DELIVERING'` 看 lease_token |

---

## 常见问题

### 投递不到 webhook.site

- 把 `$WH_URL` 复制到浏览器看能不能直接打开。
- 检查应用日志有没有 `Connection timed out` / `SSL handshake failed`。
- 临时用 `https://httpbin.org/post` 替代（国内网络可能不稳）。

### GET 一直返回 PENDING

- 应用日志里搜 worker 关键字，确认 worker 在跑。
- `psql` 看表里这条 `next_attempt_at` 是不是在过去（如果是未来，等几分钟）。
- `SELECT * FROM notifications WHERE id='<id>'` 看 `lease_token` / `lease_until`：如果 lease 在未来且 worker 没在跑，说明 lease 没释放，等过期即可。

### 重试次数到了没变成 FAILED

- 默认 `NOTIFICATION_MAX_ATTEMPTS=8`,8 次 × 退避(1s→2s→4s→...) 在 `backoff-max-ms`(默认 5 分钟)封顶,大概 10~20 分钟跑完。
- 调小重试上限可以加快测试:`NOTIFICATION_MAX_ATTEMPTS=3 NOTIFICATION_BACKOFF_BASE_MS=200`。

### `Connection refused localhost:5432`

Postgres 没起。两种处理:

- 本机已有:`服务管理器` → 启动 `postgresql-x64-XX`。
- 本机没装:`docker compose up -d postgres`。

完整排查见 [README.md](README.md) 的「常见问题排查」一节。

---

## 端到端测试脚本（可选）

把常用命令拼成一个 `verify.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
WH_URL="${WH_URL:?usage: WH_URL=https://webhook.site/<your-id> verify.sh}"

echo "[1/4] health"
curl -fsS "$BASE/actuator/health"; echo

echo "[2/4] enqueue"
RESP=$(curl -fsS -X POST "$BASE/v1/notifications" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: verify-$(date +%s)" \
  -d "{\"targetUrl\":\"$WH_URL\",\"headers\":{\"X-Source\":\"verify\"},\"body\":{\"ok\":true}}")
echo "$RESP"
ID=$(echo "$RESP" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

echo "[3/4] wait + get"
sleep 3
curl -fsS "$BASE/v1/notifications/$ID"; echo

echo "[4/4] idempotent replay"
curl -fsS -X POST "$BASE/v1/notifications" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: replay-$(date +%s)" \
  -d "{\"targetUrl\":\"$WH_URL\",\"headers\":{\"X-Source\":\"verify\"},\"body\":{\"ok\":true}}" \
  >/dev/null
ID2=$(curl -fsS -X POST "$BASE/v1/notifications" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: replay-$(date +%s)" \
  -d "{\"targetUrl\":\"$WH_URL\",\"headers\":{\"X-Source\":\"verify\"},\"body\":{\"ok\":true}}" \
  | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
echo "first id: $ID, second id: $ID2"
[ "$ID" != "$ID2" ] && echo "(distinct; use the SAME key in your own check to verify idempotency returns the same id)" || echo "(same id — idempotent)"
```

跑法：

```bash
chmod +x verify.sh
WH_URL=https://webhook.site/你的-uuid ./verify.sh
```