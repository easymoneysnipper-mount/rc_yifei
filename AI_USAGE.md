# AI 使用说明

## AI 提供的帮助

- 帮助梳理了“接收请求”和“外部投递”解耦的整体架构；
- 建议使用 PostgreSQL durable queue、Flyway 和 Spring JDBC，以便明确实现 `FOR UPDATE SKIP LOCKED`、lease 和条件状态更新；
- 协助整理了 at-least-once 语义、重试状态分类、指数退避、`Retry-After`、幂等键和崩溃恢复的测试要点；
- 协助生成了 Maven 项目骨架、API DTO、数据库迁移、worker 和 HTTP client 的初始实现，以及 README 的设计说明结构。

## 没有采纳的建议

没有在 MVP 中引入 Kafka/RabbitMQ、独立调度服务、分布式锁、事件溯源、跨地域 active-active、exactly-once 事务协议或完整的供应商插件平台。这些建议可以用于大规模演进，但当前仓库没有既有基础设施，需求也没有给出相应吞吐和多地域要求；它们会显著增加部署、运维和验证成本。

也没有把供应商响应 body、复杂业务重试规则和无限期 attempt audit 纳入第一版，因为本服务只需要确认 HTTP 投递结果，且这些功能不影响基础的可靠投递闭环。

## 自己做出的关键决策

- 选择单体 Spring Boot + PostgreSQL 队列表，而不是内存队列：服务重启后仍能恢复未完成任务；
- 选择 at-least-once 而不是 exactly-once：外部 HTTP 已接受请求与本地状态提交之间存在不可消除的崩溃窗口；
- 将网络请求放在数据库事务之外，并使用 lease token 条件更新：避免长事务，同时阻止过期 worker 覆盖新 worker；
- 仅对网络错误、超时、408/425/429/5xx 重试，其他 4xx 终止：这符合“暂时不可用可恢复、请求本身错误通常不可恢复”的判断；
- 默认禁用重定向并限制 URL/body/header：降低 SSRF、资源耗尽和误投递风险；生产环境仍需网关认证、host allowlist 和更完整的网络出口策略。
