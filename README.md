# Reliable HTTP Notification Service

一个面向企业内部业务系统的异步 HTTP 通知服务 MVP。业务系统提交通知后立即得到 `202 Accepted`，服务将请求持久化到 PostgreSQL，再由后台 worker 可靠地投递到供应商 API。

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

---

## 目录

- [快速上手（5 分钟）](#快速上手5-分钟)
- [方式一：IDEA 里 Run / Debug](#方式一idea-里-run--debug)
- [方式二：终端用本机 PostgreSQL 跑](#方式二终端用本机-postgresql-跑)
- [方式三：终端用 Docker Compose 一键起](#方式三终端用-docker-compose-一键起)
- [三种启动方式的对比与选型](#三种启动方式的对比与选型)
- [配置项](#配置项)
- [常见问题排查](#常见问题排查)
- [API 与设计文档](#api-与设计文档)
- [AI 使用说明](#ai-使用说明)

---

## 快速上手（5 分钟）

第一次接触这个项目？按下面顺序操作，**任选一条**就能跑起来：

| 你现在想做的事 | 用哪种方式 |
| --- | --- |
| 在 IDEA 里改代码、调试、断点 | [方式一](#方式一idea-里-run--debug) |
| 命令行一行命令拉起整套服务，不装任何数据库 | [方式三](#方式三终端用-docker-compose-一键起) |
| 本机已有 PostgreSQL，想用 mvn 命令跑 | [方式二](#方式二终端用本机-postgresql-跑) |

最短路径（**方式三**）：

```bash
# 1. 确认已安装 Docker Desktop（Windows / macOS）或 Docker Engine（Linux）
docker --version

# 2. 一键启动 Postgres + 应用（首次会下载镜像 + 构建，耐心等几分钟）
docker compose up -d --build

# 3. 验证
curl http://localhost:8080/actuator/health
# 返回 {"status":"UP"} 即成功

# 4. 看日志
docker compose logs -f app

# 5. 停止（数据保留在 volume 里）
docker compose down
```

启动成功后请阅读 [功能验证.md](FUNCTION_VALIDATION.md)，里面给出了端到端验证 API 的完整步骤。

---

## 方式一：IDEA 里 Run / Debug

适合日常开发：改代码、热加载、断点调试都顺手。

### 1.1 前置条件

| 工具 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 25（pom 里 `<java.version>25</java.version>`） | 用 `java -version` 验证 |
| IntelliJ IDEA | 2024.2+ | Community 即可 |
| PostgreSQL | 15+ | 见下面两个二选一 |
| 网络 | 能访问 Maven Central | IDEA 默认就行 |

PostgreSQL 二选一：

- **A. 本机已有 PostgreSQL**：直接用，跳到 [1.2](#12-准备数据库本机-postgresql)。
- **B. 本机没装**：用我提供的 compose 单起一个 Postgres 容器，跳到 [1.3](#13-用-docker-单起-postgres容器再在-idea-里跑-app)。

### 1.2 准备数据库（本机 PostgreSQL）

如果本机已经有 Postgres，但用户/库/密码跟项目默认值不一致，按下面准备；否则直接看 [1.4](#14-在-idea-里打开项目)。

**方式 A1：用 psql 创建（推荐）**

打开「命令提示符」或 PowerShell：

```bash
psql -U postgres
```

输入 postgres 用户密码后执行：

```sql
CREATE USER notifications WITH PASSWORD 'notifications';
CREATE DATABASE notifications OWNER notifications;
\q
```

**方式 A2：用 pgAdmin**

pgAdmin 里连到本机 Postgres → 左上角 `Object` → `Create` → `Login/Group Role`，Name 填 `notifications`，`Definition` 页签填密码 `notifications` → 保存。再 `Create` → `Database`，Name 填 `notifications`，Owner 选 `notifications` → 保存。

> 如果你的默认用户名/密码不一样（比如实际是 `postgres` / `postgres`，库名 `mydb`），后面 [1.5](#15-配置-run-参数) 里改成对应的环境变量就行，不用对齐 `notifications`。

### 1.3 用 Docker 单起 Postgres 容器，再在 IDEA 里跑 App

如果你本机没装 Postgres、又想在 IDEA 里 Run / Debug 应用，这条路最干净。

```bash
# 在项目根目录执行
docker compose up -d postgres

# 确认 Postgres 起来了
docker compose ps
# 看到 notification-postgres 状态 healthy
```

数据落在这个容器里，App 进程仍然在你 IDEA 里跑。之后整个工作流：

```bash
# 第一次/改了 Docker 相关文件后
docker compose up -d postgres

# IDEA 里 Run / Debug NotificationApplication

# 工作结束
docker compose down        # 停容器，volume 保留
docker compose down -v     # 停容器并清数据
```

### 1.4 在 IDEA 里打开项目

1. 启动 IntelliJ IDEA。
2. `File` → `Open` → 选项目根目录 `c:\Users\Administrator\IdeaProjects\rc_yifei`（或你的项目路径）。
3. 首次打开 IDEA 会问「Trust Project」→ 选 `Trust`。
4. 右下角出现进度条，Maven 在下载依赖。**首次需要 1~3 分钟**，等进度条跑完。
5. 如果右下角弹出 `Load Maven Project` / `Enable Auto-Import`，点 `Enable Auto-Import`，这样改 `pom.xml` 会自动重新解析。

### 1.5 配置 Run 参数

右上角点绿色运行按钮左边的下拉 → `Edit Configurations...`：

- 如果下拉里已经有 `NotificationApplication`，直接选它。
- 没有就点 `+` → `Application`：
  - `Name`: `NotificationApplication`
  - `Main class`: `com.example.notification.NotificationApplication`
  - `Working directory`: 项目根目录（默认值就行）
  - `JRE`: 25
  - `Environment variables`: **只有当你用的不是默认 `notifications/notifications/notifications` 时才加**，比如：
    ```
    DB_URL=jdbc:postgresql://localhost:5432/notifications;DB_USERNAME=postgres;DB_PASSWORD=postgres
    ```
    Windows 下多条之间用 `;` 分隔。

> 如果你用的是 [1.3](#13-用-docker-单起-postgres容器再在-idea-里跑-app) 的 docker postgres，`DB_URL` 仍写 `localhost:5432`（Docker 把容器端口映射到了本机）。

### 1.6 启动 & 调试

- **Run**（普通运行）：`Shift + F10`
- **Debug**（断点调试）：`Shift + F9`，在代码左侧行号区点一下出红点

启动成功的标志（Run 窗口输出）：

```
Started NotificationApplication in 3.421 seconds (process running for 3.842)
Tomcat started on port 8080 (http)
Successfully applied 1 migration to schema "public" (execution time 00:00.045)
```

### 1.7 验证

打开浏览器或执行 curl：

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

完整功能测试请看 [功能验证.md](FUNCTION_VALIDATION.md)。

---

## 方式二：终端用本机 PostgreSQL 跑

适合不需要 IDE、CI / 远程服务器、自动化脚本等场景。

### 2.1 前置条件

| 工具 | 版本 |
| --- | --- |
| JDK | 25（`java -version`） |
| Maven | 3.9+（`mvn -v`） |
| PostgreSQL | 15+（`psql --version`），且能连接 `localhost:5432` |

### 2.2 准备数据库

参考 [1.2](#12-准备数据库本机-postgresql)，确保存在：

- 用户 `notifications` / 密码 `notifications`（或你自定义的）
- 库 `notifications`

### 2.3 启动应用

在项目根目录：

```bash
mvn spring-boot:run
```

Flyway 启动时会自动执行 `db/migration/V1__create_notifications.sql`。

### 2.4 自定义数据库连接

默认值：`jdbc:postgresql://localhost:5432/notifications`（用户/密码 `notifications/notifications`）。需要改时用环境变量：

```bash
# PowerShell
$env:DB_URL="jdbc:postgresql://localhost:5432/mydb"
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="postgres"
mvn spring-boot:run

# Git Bash / Linux / macOS
DB_URL=jdbc:postgresql://localhost:5432/mydb \
DB_USERNAME=postgres \
DB_PASSWORD=postgres \
mvn spring-boot:run
```

### 2.5 验证

另开一个终端：

```bash
curl http://localhost:8080/actuator/health
```

### 2.6 跑测试

```bash
mvn test       # 单元测试
mvn verify     # 单元 + 集成测试（含 Testcontainers）
```

### 2.7 打包成 jar 运行

```bash
mvn -DskipTests package
java -jar target/notification-service-0.1.0-SNAPSHOT.jar
```

---

## 方式三：终端用 Docker Compose 一键起

适合任何机器上跑（CI、远程、新机器），**不需要本机装 JDK / Maven / PostgreSQL**，只要有 Docker。

### 3.1 前置条件

只需要 Docker：

| 平台 | 安装 |
| --- | --- |
| Windows | [Docker Desktop](https://www.docker.com/products/docker-desktop/) |
| macOS | [Docker Desktop](https://www.docker.com/products/docker-desktop/) |
| Linux | `apt install docker.io docker-compose-plugin` 或对应发行版的命令 |

验证：

```bash
docker --version
docker compose version
```

### 3.2 一键启动

```bash
# 在项目根目录
docker compose up -d --build
```

- 首次会拉取 `postgres:16`、`maven:3.9-eclipse-temurin-25`、`eclipse-temurin:25-jre` 三个镜像并构建 App 镜像。视网络情况 3~10 分钟。
- 之后 `docker compose up -d` 秒开（App 镜像已缓存）。

`docker compose ps` 应该看到两个容器都 `healthy` / `running`：

```
NAME                   STATUS
notification-postgres  Up (healthy)
notification-service   Up
```

### 3.3 看日志

```bash
# 跟踪 App 日志（Flyway 迁移、worker 投递都在这里）
docker compose logs -f app

# 看 Postgres 日志
docker compose logs -f postgres

# 最近 100 行
docker compose logs --tail=100 app
```

### 3.4 验证

```bash
curl http://localhost:8080/actuator/health
```

### 3.5 常用命令

```bash
docker compose ps                  # 查看容器状态
docker compose restart app         # 只重启 App
docker compose logs -f app         # 跟踪 App 日志
docker compose down                # 停止，volume 保留（数据不丢）
docker compose down -v             # 停止并清掉 volume（数据清空）
docker compose exec postgres psql -U notifications -d notifications   # 进 Postgres 终端
```

### 3.6 代码改了怎么重建

```bash
# 只重建 App 镜像（postgres 不用动）
docker compose build app
docker compose up -d app

# 或者一步到位
docker compose up -d --build app
```

---

## 三种启动方式的对比与选型

| 场景 | 推荐方式 | 原因 |
| --- | --- | --- |
| 日常开发、改代码、断点调试 | 方式一（IDEA） | 热重载、变量查看、Debug 全支持 |
| 新机器 / 同事 onboarding / 没装 JDK 和 PG | 方式三（Docker Compose） | 零依赖，一条命令搞定 |
| CI 跑测试 / 部署到服务器 | 方式三 或 方式二 | CI 用 Docker；服务器看运维规范 |
| 不想用 Docker | 方式二（mvn spring-boot:run） | 最朴素 |
| IDEA 里调试 + 本机没装 Postgres | 方式一 + 1.3（docker compose up -d postgres） | 两者结合 |

> **关键提示**：IDEA 跑 App + Docker 起 Postgres 是项目里最常用的组合。`docker compose up -d postgres` 只起数据库，App 进程在你 IDEA 里，跑得飞快。

---

## 配置项

所有配置都通过 `src/main/resources/application.yml` 暴露，可以用环境变量覆盖（变量名见文件中的 `${XXX:default}` 形式）。

| 类别 | 变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| 数据库 | `DB_URL` | `jdbc:postgresql://localhost:5432/notifications` | JDBC 连接串 |
| | `DB_PASSWORD` | `notifications` | 密码 |
| | `DB_USERNAME` | `notifications` | 用户名 |
| | `DB_POOL_SIZE` | `10` | HikariCP 连接池大小 |
| 投递 worker | `NOTIFICATION_WORKER_CONCURRENCY` | `4` | 并发投递线程数 |
| | `NOTIFICATION_WORKER_BATCH_SIZE` | `4` | 每次领取数量 |
| | `NOTIFICATION_WORKER_POLL_INTERVAL_MS` | `500` | 空闲轮询间隔 |
| | `NOTIFICATION_WORKER_LEASE_SECONDS` | `60` | 任务 lease 时长 |
| | `NOTIFICATION_MAX_ATTEMPTS` | `8` | 最大尝试次数 |
| | `NOTIFICATION_BACKOFF_BASE_MS` | `1000` | 退避基础 |
| | `NOTIFICATION_BACKOFF_MAX_MS` | `300000` | 退避上限 |
| | `NOTIFICATION_BACKOFF_JITTER_MS` | `500` | 退避抖动 |
| 出站 HTTP | `NOTIFICATION_HTTP_CONNECT_TIMEOUT_MS` | `3000` | 连接超时 |
| | `NOTIFICATION_HTTP_REQUEST_TIMEOUT_MS` | `10000` | 请求总超时 |
| 限制 | `NOTIFICATION_MAX_URL_LENGTH` | `2048` | targetUrl 长度上限 |
| | `NOTIFICATION_MAX_BODY_BYTES` | `1048576` | body 大小上限（字节） |
| | `NOTIFICATION_MAX_HEADERS` | `32` | header 数量上限 |
| | `NOTIFICATION_MAX_HEADER_VALUE_LENGTH` | `4096` | 单个 header value 长度上限 |

---

## 常见问题排查

### 启动失败：`Connection refused localhost:5432`

Postgres 没在跑。两种处理：

- 本机已有：服务管理器 → 启动 `postgresql-x64-XX`。
- 本机没装：`docker compose up -d postgres`。

### 启动失败：`password authentication failed for user "notifications"`

数据库用户不存在或密码不对：

```sql
-- psql -U postgres 后执行
ALTER USER notifications WITH PASSWORD 'notifications';
```

或改环境变量 `DB_USERNAME` / `DB_PASSWORD` 指向真实用户。

### 启动失败：`database "notifications" does not exist`

```sql
-- psql -U postgres 后执行
CREATE DATABASE notifications OWNER notifications;
```

或改 `DB_URL` 指向已有的库。

### `port 5432 already in use`

本机已经有一个 Postgres 占用了 5432。两种选择：

- 停掉本机的 Postgres（如果不用了）。
- 改 compose 里 `notification-postgres` 服务的端口映射，比如 `"55432:5432"`，同时让 App 连 `localhost:55432`。`docker-compose.yml` 和 `application.yml`/`DB_URL` 同步修改。

### `port 8080 already in use`

改 compose 里 `notification-service` 的 `ports: "8090:8080"`，浏览器访问 `http://localhost:8090`。

### IDEA 启动报 Maven 找不到依赖

- 检查网络能不能访问 `repo.maven.apache.org`。
- 国内网络慢的话，IDEA → `Settings` → `Build, Execution, Deployment` → `Build Tools` → `Maven` → `User settings file` 里加阿里云镜像：
  ```xml
  <mirror>
    <id>aliyun</id>
    <mirrorOf>central</mirrorOf>
    <url>https://maven.aliyun.com/repository/public</url>
  </mirror>
  ```

### IDEA 启动报 `Unsupported class file major version 69`

JDK 版本不够。pom 要求 Java 25，下载安装 JDK 25 后在 IDEA → `Project Structure` → `SDK` 改。

### 投递不到下游

- 确认目标 URL 在公网可达，HTTPS 证书有效。
- 看 App 日志：`docker compose logs -f app` 或 IDEA Run 窗口。
- 用 `curl http://localhost:8080/v1/notifications/{id}` 看 `lastHttpStatus` / `lastError`。

### docker compose 命令找不到

- 老版 Docker 用的是 `docker-compose`（带连字符）：`docker-compose up -d --build`。
- 新版用 `docker compose`（带空格）。

---

## API 与设计文档

- API 速查：见下文 [§ API 概览](#api-概览)
- 详细设计动机与取舍：原 [README.md](README.md) 的 §1~5 与 [DESIGN_INSIGHTS.md](DESIGN_INSIGHTS.md)
- 端到端功能验证步骤：[功能验证.md](FUNCTION_VALIDATION.md)

### API 概览

#### 提交通知

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

返回 `202 Accepted`，响应体含 `id`、`status: "PENDING"`、`statusUrl`、`Location` 响应头。

- 同一 `Idempotency-Key` + 相同 payload → 返回原通知（同 id）。
- 同一 `Idempotency-Key` + 不同 payload → `409 Conflict`。
- `Idempotency-Key` 必填且 ≤128 字符。

#### 查询状态

```http
GET /v1/notifications/{id}
```

返回状态、尝试次数、`lastHttpStatus`、脱敏后的 `lastError`、时间戳等。不返回保存的 body 或敏感 header。

#### 健康检查

| 路径 | 含义 |
| --- | --- |
| `/livez` | 进程存活 |
| `/readyz` | 数据库可连接 + 服务就绪 |
| `/actuator/health` | Spring Boot Actuator 综合健康信息 |

---

## AI 使用说明

详见 [AI_USAGE.md](AI_USAGE.md)。