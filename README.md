# Reliable HTTP Notification Service

一个面向企业内部业务系统的异步 HTTP 通知服务 MVP。业务系统提交通知后立即得到 `202 Accepted`，服务将请求持久化到 PostgreSQL，再由后台 worker 可靠地投递到供应商 API。

## 1. 问题理解与目标

不同供应商的 URL、请求头和 JSON body 不同，但业务系统不需要同步等待供应商响应；它只需要通知请求被可靠地接收并持续投递。本服务因此把“接收”和“投递”解耦：

```text
业务系统 -> POST /v1/notifications -> PostgreSQL durable queue
                                           |
                                           v
                                  bounded delivery workers
                                           |
                                           v
                                      Vendor HTTP API
```

MVP 的投递语义是 **at-least-once（至少一次）**。服务不会承诺 exactly-once：如果远端已经收到请求，但本服务在写回成功状态前崩溃，恢复后可能再次发送。请求会携带 `X-Notification-Id`，下游如需去重应使用这个 ID 或自己的幂等键。

## 2. 系统边界

### 本服务负责

- 校验和持久化 HTTP 通知请求；
- 使用 `Idempotency-Key` 防止业务系统重试造成重复入队；
- 异步发送请求、超时控制、有限重试和指数退避；
- worker lease、崩溃恢复、状态查询；
- 基础健康检查和不泄露 body/header 的结构化日志。

### 明确不负责

- 解析供应商业务响应或理解供应商业务语义；任何 `2xx` 都视为投递成功；
- exactly-once（跨越本服务和外部 HTTP 服务无法由本服务单方面保证）；
- 复杂供应商插件、工作流编排、全局计费和多地域灾备；
- 自动修复永久性 `4xx`；这类问题需要配置或供应商修复；
- 无限期保存历史记录。

MVP 只允许 `http`/`https` URL，限制 body/header 大小，禁用自动重定向。生产部署还应在网关或配置中增加目标 host allowlist、SSRF 防护和调用方认证。

## 3. API

### 提交通知

```http
POST /v1/notifications
Idempotency-Key: order-123-payment-success
Content-Type: application/json

{
  "targetUrl": "https://vendor.example.test/hooks/order",
  "headers": {
    "Authorization": "Bearer ...",
    "X-Event-Type": "payment.succeeded"
  },
  "body": {
    "orderId": "order-123"
  }
}
```

返回：

```http
202 Accepted
Location: /v1/notifications/{id}
```

```json
{
  "id": "uuid",
  "status": "PENDING",
  "createdAt": "2026-01-01T00:00:00Z",
  "statusUrl": "/v1/notifications/{id}"
}
```

同一 `Idempotency-Key` 和相同请求内容会返回原通知；同一 key 但请求内容不同返回 `409 Conflict`。`Idempotency-Key` 必须由调用方生成并保持稳定。

### 查询状态

`GET /v1/notifications/{id}` 返回通知状态、尝试次数、时间戳、最后 HTTP 状态、下一次重试时间和脱敏错误摘要，不返回保存的 body 或敏感 header。

### 健康检查

- `/livez`：进程存活；
- `/readyz`：数据库可连接且服务可以接收任务；
- `/actuator/health`：Spring Boot Actuator 健康信息。

## 4. 可靠性与失败处理

- 网络异常、超时、`408`、`425`、`429` 和 `5xx` 会重试；`Retry-After` 会被采用并受配置上限约束；
- 其他 `4xx` 和禁用重定向产生的 `3xx` 视为永久失败；
- 达到最大尝试次数后进入 `FAILED`，不无限重试；
- worker 领取任务时写入 lease。进程崩溃或请求卡死导致 lease 过期后，其他 worker 可以重新领取；条件更新避免旧 worker 覆盖新 worker 的状态；
- 领取和状态更新在数据库事务中完成，网络请求在事务外执行，避免长时间占用数据库连接。

这套设计牺牲了 exactly-once 和即时强一致，换取了简单、可恢复和容易验证的 MVP。外部供应商长期不可用时，任务会按退避策略重试，最终失败并保留最后错误，避免无限消耗资源。

## 5. 关键取舍与演进

第一版不引入 Kafka/RabbitMQ、独立调度服务、分布式锁、事件溯源、跨地域 active-active 或复杂 provider adapter。当前需求的主要失败模式可以由 PostgreSQL 队列表、`FOR UPDATE SKIP LOCKED`、lease 和有限 worker 覆盖；额外组件会增加部署和运维成本。

流量增加时先观察队列深度、投递延迟、数据库锁等待和各 host 错误率，再逐步增加 host 级限流、批量 claim、历史归档和分区。吞吐超过单库能力时，可使用 CDC/可靠消息 broker 替换队列实现，但保持幂等键和 at-least-once 语义。供应商差异明显增加时，再加入版本化 provider adapter 和凭据管理；多地域需求出现时再设计地域路由和故障转移。

## 6. 本地运行

要求 JDK 25、Maven 3.9+ 和 PostgreSQL 15+。例如：

```bash
createdb notifications
mvn spring-boot:run
```

默认连接：`jdbc:postgresql://localhost:5432/notifications`，用户和密码均为 `notifications`。也可以通过 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 等环境变量配置。构建与测试：

```bash
mvn test
mvn verify
```

当前执行环境没有可用的 `java` 和 `mvn` 命令，因此这里不能宣称本地构建已通过；代码和测试应在具备 Java 25/Maven/PostgreSQL 的环境中执行。

## 7. AI 使用说明

详见 [AI_USAGE.md](AI_USAGE.md)。
