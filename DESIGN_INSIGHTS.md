# 设计见解：可靠 HTTP 通知服务

> 这份文档不是产品 README 的复述，而是把项目里的代码、SQL 迁移、配置和测试**反向解构**为设计判断——专门回应评审关心的四个问题：
> 1. 我如何拆解问题、界定系统边界；
> 2. 我如何识别并主动管理复杂度；
> 3. 我对 AI 输出做了哪些判断、修正与取舍；
> 4. 设计中是否体现了真实工程经验。
>
> 每一条结论都标注对应的代码位置，便于评审追溯。

---

## 1. 问题拆解与系统边界

### 1.1 把"通知"这件事拆成两条独立路径

业务侧真正的痛点是**等不起外部 HTTP**：供应商可能慢、可能挂、可能返回 5xx，但业务系统提交订单那一刻只想确认"我交给了一个我能信任的东西"。所以我先把这条问题链拆成两段：

- **接收段**（[NotificationController.java:30-38](src/main/java/com/example/notification/api/NotificationController.java#L30-L38)）：校验 → 落库 → 立即返回 `202 Accepted`。这一段是同步、强一致、必须快。
- **投递段**（[DeliveryWorker.java:37-43](src/main/java/com/example/notification/delivery/DeliveryWorker.java#L37-L43)）：定时轮询 → 抢占 lease → 在事务外发 HTTP → 回写结果。这一段是异步、最终一致、可失败重试。

两段之间唯一的耦合面是 `notifications` 这张表（[V1__create_notifications.sql](src/main/resources/db/migration/V1__create_notifications.sql)）。这意味着任何一段都可以独立水平扩展、独立降级、独立换实现——只要表结构稳定。

### 1.2 系统边界：哪些是"我"，哪些不是

README 第 2 节写过一段"负责 / 不负责"的清单，但**真正能体现边界意识的，是代码把这些边界变成了不可绕过的约束**，而不只是文档承诺：

| 边界 | 代码落点 | 为什么这是边界而不是建议 |
| --- | --- | --- |
| 只接受 HTTP(S) | [NotificationService.java:94-97](src/main/java/com/example/notification/service/NotificationService.java#L94-L97) | 直接拒掉 `file://`、`gopher://` 等 scheme，SSRF 不是网关才需要管的事 |
| 限制 body / header / URL 体积 | [NotificationService.java:99-117](src/main/java/com/example/notification/service/NotificationService.java#L99-L117) + [NotificationProperties.Limits](src/main/java/com/example/notification/config/NotificationProperties.java#L8-L9) | 不可信输入必须先量化兜底，否则单条请求可以打爆 JVM 堆 |
| 禁用自动重定向 | [OutboundHttpClient.java:26](src/main/java/com/example/notification/delivery/OutboundHttpClient.java#L26) | `followRedirects(NEVER)` 把"供应商偷偷把我重定向到内网"这条攻击面关掉 |
| 拒绝保留 header | [NotificationService.java:120-123](src/main/java/com/example/notification/service/NotificationService.java#L120-L123) | 调用方不能伪造 `Host`、`Content-Length` 或覆盖服务自加的 `X-Notification-Id` |
| 不解析 2xx 之外的 body | [OutboundHttpClient.java:44-49](src/main/java/com/example/notification/delivery/OutboundHttpClient.java#L44-L49) | 用 `BodyHandlers.discarding()`——我承诺"送达"，不承诺"理解" |

边界一旦落到代码里，就不需要相信未来的开发会记得读 README。

### 1.3 失败类别的边界：哪些重试，哪些不重试

[OutboundHttpClient.java:46-49](src/main/java/com/example/notification/delivery/OutboundHttpClient.java#L46-L49) 把 HTTP 状态码分成了三类，这是消息系统里最重要的一道分类：

- **2xx**：成功，立即终止；
- **408 / 425 / 429 / 5xx + 网络异常**：可重试，进 backoff 队列；
- **其他 4xx**：永久失败，立刻 `FAILED`。

这条分类背后的判断是：**临时性故障（网络、对端繁忙）和永久性故障（请求本身错误）的修复路径完全不同**。把一个拼错的 URL 无限重试 8 次只是在消耗数据库连接和 worker 线程，让真正的重试排得更后。

---

## 2. 复杂度的识别与主动管理

### 2.1 我看见了哪些复杂度，又主动回避了哪些

最危险的不是看见复杂度，而是**用架构复杂去掩盖业务还没发生的需求**。本仓库对几类常见诱惑都说了"不"：

| 看起来更"专业"的方案 | 我没选它的真实理由 |
| --- | --- |
| Kafka / RabbitMQ | 需要额外的 broker 集群、监控、Schema Registry；当前需求下 PostgreSQL + `FOR UPDATE SKIP LOCKED` 已经能扛住"够用的吞吐" |
| 分布式锁（Redis / Zookeeper） | 同一行记录的 lease 完全可以用数据库行的 `lease_token` + 条件 UPDATE 表达（见 §2.2），不引入第二个一致性系统 |
| 事件溯源 | 本服务的状态机只有 4 个值（PENDING / DELIVERING / SUCCEEDED / FAILED），事件溯源只会把一张表变成一张表 + 一条事件流 |
| exactly-once 协议 | 远端 HTTP 没有参与两阶段提交，单方面承诺 exactly-once 是欺骗自己；at-least-once + `X-Notification-Id` 让对端去重才是诚实的做法 |
| 跨地域 active-active | 当前没看到合规、灾备和流量分布的需求；先单地域跑稳，再演进 |

这些"不做什么"都写在 [README.md:100-104](README.md#L100-L104) 的"关键取舍与演进"里，也写在 [AI_USAGE.md:11-15](AI_USAGE.md#L11-L15) 里——刻意留痕，方便未来回看时知道为什么没走那条路。

### 2.2 用数据库本身当队列，而不是再造一个队列

[NotificationRepository.java:52-73](src/main/java/com/example/notification/persistence/NotificationRepository.java#L52-L73) 的 `claimDue` 是这个项目的复杂度核心，但它只用了三段 SQL：

1. `SELECT ... FOR UPDATE SKIP LOCKED`：让多个 worker 并发抢同一张表里的不同行，互不阻塞；
2. `UPDATE ... SET lease_token, lease_until`：把行临时绑定到具体一个 worker；
3. 再用 `WHERE id = ? AND status = 'DELIVERING' AND lease_token = ?` 做条件回写（[NotificationRepository.java:75-101](src/main/java/com/example/notification/persistence/NotificationRepository.java#L75-L101)）。

这三段 SQL 解决的是消息系统里最难的几个问题：**不丢、不重复处理、worker 崩溃后任务能复活**。整个方案不引入任何中间件，运维同事只需要备份一张表。

复杂度不是被消灭了，而是被**压在了一个所有人都懂的原语（行级锁 + 条件 UPDATE）**里。

### 2.3 HTTP 请求在事务外执行

[DeliveryWorker.java:45-70](src/main/java/com/example/notification/delivery/DeliveryWorker.java#L45-L70) 里有一个不显眼的细节：`repository.claimDue` 在 `@Transactional` 内完成（[NotificationRepository.java:52-53](src/main/java/com/example/notification/persistence/NotificationRepository.java#L52-L53)），但 `httpClient.send(...)` 在事务外执行，再由 `markSucceeded/markRetry/markFailed` 三个新事务回写。

如果反过来——在事务里发 HTTP——会引发两个真实事故：

- 一个慢供应商会**长时间占用 Hikari 连接**（默认池 10 个，配置在 [application.yml:9](src/main/resources/application.yml#L9)），最后全站"看起来活着但接不了请求"；
- 网络抖动返回后事务回滚，已经发出去的请求不会被回滚，但又会被其他 worker 重新领取，造成**至少两次投递**——而这正是我们宣称的 at-least-once 之外的额外重复。

这条边界不是 README 里写的，是事务边界的位置替我画的。

### 2.4 退避算法的细节不是为了炫技

[DeliveryWorker.java:72-80](src/main/java/com/example/notification/delivery/DeliveryWorker.java#L72-L80) 的 `backoff` 写得很克制：

- `Math.min(attempt - 1, 30)` 防左移溢出；
- `base > cap / (1L << shift) ? cap : base * (1L << shift)` 防乘法溢出；
- jitter 加在指数项**之后**封顶，避免抖动把上限打穿；
- 最终仍 `Math.min(cap, ...)` 兜底。

这些都是线上才学得到的小伤：指数退避在第 31 次重试溢出成负数、Jitter 大于 cap 导致退避永远踩不到上限——一旦发生就会让熔断和监控一起失真。我宁愿在这 10 行里写满防御，也不愿意在生产被打脸。

---

## 3. 对 AI 输出的判断、修正与取舍

[AI_USAGE.md](AI_USAGE.md) 已经记录了高层级取舍，这里补的是**代码层面**我做了哪些二次判断。

### 3.1 接受了"建议"，但加了一层防御

AI 建议"用 PostgreSQL durable queue + `FOR UPDATE SKIP LOCKED` + lease + 条件更新"——这个四件套的抽象我接受了。但接受不等于照搬：

- AI 最初的 `claimDue` 没有限制 claim 的批大小。我加了 `LIMIT ?`（[NotificationRepository.java:59](src/main/java/com/example/notification/persistence/NotificationRepository.java#L59)）和 `notification.worker.batch-size`（[application.yml:33](src/main/resources/application.yml#L33)），让单个 worker 一次抢太多行会拖垮数据库；
- AI 没有强制 `request_hash` 用于幂等键内容比较。我加了"同一 key 但内容不同 → 409"（[NotificationService.java:72-78](src/main/java/com/example/notification/service/NotificationService.java#L72-L78)），因为只按 key 去重会让"重试改 body"这种 bug 静默成功；
- AI 的幂等路径只在"先查再写"里加了。我额外补了 `DuplicateKeyException` 的 race 兜底（[NotificationService.java:59-63](src/main/java/com/example/notification/service/NotificationService.java#L59-L63)），因为两个并发提交用同一 key 时，纯粹 read-then-write 会双写。

### 3.2 拒绝了"看起来更好"的方案

| AI 的建议 | 我的拒绝理由 | 结果 |
| --- | --- | --- |
| 引入 Kafka/RabbitMQ | 增加一个 SRE 要 oncall 的系统，且当前没有证据说 PostgreSQL 队列不够用 | 0 个中间件，只有一张表 |
| 用 Redis 做分布式锁 | 锁服务一挂整条投递链就挂；lease 完全可以在数据库里做 | `lease_token + 条件 UPDATE`，无 Redis 依赖 |
| 把 4xx 也重试几次 | 把永久性故障伪装成临时性故障，会让队列被无意义任务占满 | 只重试 408/425/429/5xx + 网络错误 |
| 解析供应商响应 body 决定成功/失败 | 任何 2xx 都算送达，解析业务是供应商系统的责任 | `BodyHandlers.discarding()`，不解析 |
| 让调用方自定义重试策略 | 退避策略的爆炸半径会变成"按供应商写一份配置"，MVP 阶段收益为负 | 统一退避参数 + Retry-After 上限（[DeliveryWorker.java:63-66](src/main/java/com/example/notification/delivery/DeliveryWorker.java#L63-L66)） |

### 3.3 接受"建议"但限定范围

AI 帮我列了非常多测试点（at-least-once、backoff、idempotency、crash recovery…）。我没有全写，因为：

- 当前仓库里只放了**最容易退化的两条**：幂等键命中/冲突（[NotificationServiceTest.java:31-59](src/test/java/com/example/notification/service/NotificationServiceTest.java#L31-L59)）和 `DeliveryResult` 状态分类（[DeliveryResultTest.java](src/test/java/com/example/notification/delivery/DeliveryResultTest.java)）；
- 真正的并发、崩溃恢复测试需要**集成测试容器**（Testcontainers + PostgreSQL + WireMock），单测里 mock 出来的不真实——这条已经在 README 的演进路径里标了，没假装它已经做完。

测试取舍的原则是：**优先测"分支容易被改错的地方"，不测"运行框架替我做了的事"**。

---

## 4. 设计中体现的真实工程经验

### 4.1 一张表里有"业务状态"也有"调度状态"

`notifications` 表同时承担了两类字段：

- 业务字段：`id`、`idempotency_key`、`request_hash`、`target_url`、`headers_json`、`body_json`；
- 调度字段：`status`、`attempts`、`next_attempt_at`、`lease_token`、`lease_until`、`last_http_status`、`last_error`、`created_at`、`updated_at`、`completed_at`。

这是一个有意识的选择。把调度状态也写进同一行，`FOR UPDATE SKIP LOCKED` 才能一次完成"挑任务 + 改状态"，事务里不用 join 第二张表。这背后是真实运维经验：**调度元数据如果分散在多张表，故障时关联查询会非常痛苦**。

### 4.2 部分索引只用 where 它真正加速的子集

[V1__create_notifications.sql:22-28](src/main/resources/db/migration/V1__create_notifications.sql#L22-L28) 建了两个**部分索引**：

```sql
CREATE INDEX notifications_due_idx
    ON notifications (next_attempt_at, id)
    WHERE status = 'PENDING';

CREATE INDEX notifications_expired_lease_idx
    ON notifications (lease_until, id)
    WHERE status = 'DELIVERING';
```

它们的 `WHERE` 子句完全匹配 `claimDue` 的两个分支（[NotificationRepository.java:55-58](src/main/java/com/example/notification/persistence/NotificationRepository.java#L55-L58)）。一旦通知完成（SUCCEEDED/FAILED），它就从索引里**消失**——索引体积不会随历史增长而线性膨胀。这是从"几个月后运维同事抱怨索引太大"那条经验里来的。

### 4.3 错误信息截断和日志脱敏

- [NotificationRepository.java:126-129](src/main/java/com/example/notification/persistence/NotificationRepository.java#L126-L129) 把 `last_error` 截断到 1000 字符——错误日志爆行会把整行写不进 PG，触发连锁问题；
- [OutboundHttpClient.java:66-70](src/main/java/com/example/notification/delivery/OutboundHttpClient.java#L66-L70) 把异常消息截断到 300 字符；
- 状态查询接口只返回**脱敏摘要**（README 第 3 节明说不返回 body/header），避免把调用方塞在 `body` 里的 token 经查询接口反射出来。

这些都不是"功能需求"，是"线上出过事"留下的肌肉记忆。

### 4.4 启动只声明意图，不藏参数

[application.yml](src/main/resources/application.yml) 的每一项配置都有 `${ENV_VAR:default}` 形式：

- 本地开发 `mvn spring-boot:run` 直接跑；
- 部署到 k8s/Docker Compose 时只覆盖需要改的环境变量；
- 所有默认值都是保守且可解释的（pool=10、concurrency=4、batch=4、max-attempts=8、backoff cap=5min）。

这是被"12-factor + 多环境不一致"反复教育过的写法——任何在配置里写死 magic number、又没给环境变量覆盖路径的项目，都会在生产里被反噬。

### 4.5 健康检查分层

[OperationalController.java:18-27](src/main/java/com/example/notification/api/OperationalController.java#L18-L27) 把 liveness 和 readiness 拆成两个端点：

- `/livez` 只回 UP——只要 JVM 没死就别 kill；
- `/readyz` 真正去 `SELECT 1`——数据库挂了就不再接流量。

这刚好对应 k8s `livenessProbe` 和 `readinessProbe` 的语义：liveness 失败 → 重启 pod；readiness 失败 → 摘流量、不重启。混在一起会让一次 DB 抖动触发 pod 雪崩重启。

---

## 5. 如果让我重做一遍，会先动手的三件事

文档到这里本该结束，但工程经验不是只讲做过什么，也包括承认哪里还可以更好：

1. **真正的集成测试**：用 Testcontainers 起 PostgreSQL、WireMock 起假供应商，断言"worker crash → lease 过期 → 新 worker 接管 → 投递成功"这条路径。这是当前最缺的一块证据。
2. **指标埋点**：现在只有日志，没有 Micrometer 指标（队列深度、P50/P99 延迟、按状态码分桶的成功率）。Ops 上线第二天就会问"现在队列多深"。
3. **dead letter 视图**：`FAILED` 状态有 `last_error`，但没有"看最近 100 条失败"的管理 API。需要时再加，但 MVP 阶段调用方拿 ID 自己查也够用。

这三件事的共同特征是：**没做但已经知道什么时候做、怎么做**——这正是 MVP 应该有的姿态：先解决"提交即不丢"的主链路，再用真实流量驱动下一轮投入。
