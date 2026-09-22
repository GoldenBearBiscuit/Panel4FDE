# PLAN — observe-kit 总规划

## 一、项目是什么

一个**框架无关**的应用行为观测插件：无侵入采集「前端操作 + 后端接口 + 资源层」三层事件，
归一化成图，最终在图上跑小模型。

**定位**：能力阶梯式适配 —— 目标项目缺什么补什么，有什么用什么。

## 二、分层适配矩阵（这就是插件的规格）

| 层 | 能力 | 探测方式 | 缺失时插件提供 | 存在时 |
|---|---|---|---|---|
| L1 | 前端操作采集 | 配置开关 | **JS SDK**（路由/点击/请求） | 关掉，只接收事件 |
| L2 | 会话/步骤 ID | — | 生成 + 请求头传递 | 复用 |
| L3 | traceId | `@ConditionalOnClass` | 自建 `TraceFilter` + MDC | 复用 OTel/Sleuth/SkyWalking |
| L4 | 后端 API 事件 | `@ConditionalOnClass` | 自建 `AccessLogFilter` | **读它已有的表** |
| L5 | 用户/租户上下文 | `@ConditionalOnClass` | 从 header/JWT 取 | 复用 |
| L6 | 资源层 SQL/Redis/MQ | 配置开关 | DataSourceProxy / Connection 代理 | 复用 OTel span |
| L7 | 存储 | 配置 | 自带事件表 | — |
| L8 | 归一化 + 构图 | **永远是我们** | | |
| L9 | 可视化 | **永远是我们** | | |

**L1 / L8 / L9 永远是我们**；L2–L7 可适配。

**能力探测用 Spring Boot 条件装配，不自建 SPI 框架**：
`@ConditionalOnClass` / `@ConditionalOnMissingBean` / `@ConditionalOnProperty` 本身就是能力探测机制。
接口只切一次、每层只写一个实现，等第二个真实适配目标出现再抽。

## 三、技术选型与理由（★冻结）

| 项 | 选型 | 理由 |
|---|---|---|
| 语言级别（core） | **Java 8** | ★见下方论证 |
| 后端 | **Spring Boot 2.7** | 与 yudao（下一个真实适配目标）一致 |
| 数据库 | **MySQL 8** | 与 yudao / RuoYi 全生态一致 |
| 缓存 | **Redis 7** | 资源层第二类采集目标 |
| 前端 | **Vue 3 + Vite** | 与 demo 轻量化 |
| 图渲染 | **AntV G6** | 专做图，有 layout/方向性/边动画；ECharts graph 表达力不足 |
| SQL 采集 | **datasource-proxy**（包 DataSource） | 单点拦截，零业务侵入，能拿到真实 SQL + 参数 + 耗时 |
| Redis 采集 | **JDK 动态代理 `RedisConnection`** | `RedisConnection` 是接口，代理它可拦**全部** Redis 命令，一个类搞定 |
| SQL 归一化 | **自写词法状态机**（core 内，约 100 行） | ★见下方论证 |
| MQ | **仅预留 schema，不引 broker** | 阶段一只需证明"资源层"这一层通，DB+Redis 已足够；MQ 同构 |
| 容器 | 源码 bind mount 热重载 | 迭代最快 |

### ★为什么 JDK8 而不是最新的

`core` 必须同时服务：
- Spring Boot 2.7 + JDK8（yudao，`javax.servlet`）
- Spring Boot 3.x + JDK17/21（RuoYi-Vue-Plus，`jakarta.servlet`）

**Java 8 语言级别是唯一能同时覆盖两者的选择。** 若 core 用了高版本语言特性（`var`、`record`、`sealed`），
将来适配时必须降级 —— 那就是**回退**。所以从第一天就要用 Java 8。

Servlet/Spring 依赖**只允许出现在 starter**，core 保持纯 Java。

### ★为什么不用 JSqlParser 做 SQL 归一化

SQL parser 遇到不认识的方言/语法会**整体解析失败**，结果是"这条 SQL 完全没归一化"或"被丢弃" —— 静默的错误数据。
自写状态机扫描器（跳引号、跳注释、跳标识符）最坏情况是"部分归一化"，**能降级**。

## 四、阶段划分（单向推进，每阶段不可回退）

| 阶段 | 目标 | 状态 |
|---|---|---|
| **一** | 端到端管道打通：三层采集 → 落库 → 图可视化 | **进行中** |
| 二 | 归一化加固 + 会话聚合 + 边预聚合物化 | 未开始 |
| 三 | 适配层：yudao / RuoYi 复用路径（`@ConditionalOnClass`） | 未开始 |
| 四 | 图特征 + 小模型（Markov 基线 → node2vec+XGBoost） | 未开始 |

**错误处理策略**：阶段内发现的问题，记录到下一阶段的修正清单，**不回头改**。

## 五、阶段一 交付物

**验收标准（客观，全过才算完成）**

```
□ 7 条归一化验证清单全过（见 NORMALIZE.md）
□ 一次「列表→详情→保存」操作，库里出现 3 个 layer 的事件
□ 图里出现 4 类节点：PAGE / ACTION / API / SQL（+REDIS）
□ ★ API 节点数 == 实际调用的接口数     ← 证伪「前后端重复节点」头号风险
□ 图上看得见 3 类边：NAVIGATE / TRIGGER / CALL
□ 断开 Redis 后页面仍能出图（采集失败不拖垮业务）
```

**采集器落地顺序**：先前端 + API 两层跑通整条管道 → 看到图 → 再加资源层。
交付物不变（仍为三层），仅顺序更安全（避免同时调试三个采集器）。

**目录结构**
```
observe-kit/
├── AGENT.md                      # agent 工作流 + 状态机
├── specs/                        # 规划与规格（本目录）
├── docker/
│   ├── docker-compose.yml        # app + mysql8 + redis7
│   └── app/Dockerfile            # JDK8 + Maven + Node（挂载用）
├── observe-core/                 # ★纯 Java8：事件模型 + 归一化 + 脱敏
├── observe-spring-boot-starter/  # 自动装配 + 三层采集器
├── observe-ui/                   # 前端 SDK + G6 图组件（npm 包）
└── demo/
    ├── demo-app/                 # 裸 Spring Boot 2.7（"最高层"验证目标）
    └── demo-web/                 # 裸 Vue3 + Vite
```

**实施顺序（阶段内）**
1. 容器环境：compose + Dockerfile，确认 `app` 容器内能 `mvn` / `npm`
2. 骨架：core / starter / demo-app / demo-web 能起，前端能打到后端
3. L1 前端 SDK：路由 + 点击 + 请求采集，生成 `traceparent`，批量上报
4. L4 后端：`TraceFilter` 读 `traceparent` → MDC → 落 API 事件
5. L6 资源层：DataSourceProxy 采 SQL；`RedisConnection` 代理采 Redis
6. 归一化（按 `NORMALIZE.md` 7 条验证清单）+ 脱敏 + 落库
7. 构图接口 `GET /observe/graph?sessionId=`
8. L9 前端 G6 渲染三层图
9. 验收

## 六、已知风险与取舍

| 风险 | 处理 |
|---|---|
| SQL 归一化正则误伤标识符（`orders_2024`、`t1`） | 已列为验证清单第 4 条，必测 |
| 前后端 API 事件产生重复节点 | 去重规则已写进 `SCHEMA.md` 第三节，是阶段一最容易翻车处 |
| 前端 `traceparent` 注入是唯一"必须改 2 行"的地方 | 明确告知：不是零改动，是"改 2 行初始化" |
| 挂载热重载下 Maven 首次构建慢 | 容器内置 Maven 仓库卷缓存 |
| Docker Desktop 当前未运行 | 启动 Docker Desktop 是实施前置步骤（用户手动） |
| 项目名/目录 | 已迁到 `D:/Project/observe-kit`（原 `llm_testing/`） |
