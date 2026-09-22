# AGENT.md — observe-kit agent 工作手册

> **任何 agent 开工前必读本文件。** 这是项目状态的唯一真相来源。

## 一、项目一句话

框架无关的应用行为观测插件：无侵入采集「前端操作 + 后端接口 + 资源层」三层事件 → 归一化成图 → 图上跑小模型。

工程根：**`D:/Project/observe-kit`**（已从 `llm_testing/` 迁入）

## 二、工作流模型：单向推进（四拍）

```
规划 ──► 实施 ──► 测试 ──► 下一阶段
  │                            ▲
  └── 冻结决策、写死规格 ───────┘
```

**每阶段只走这四拍，走完即进入下一阶段。**

## 三、★不可回退规则

"回退"的定义（以下行为**一律禁止**）：

| 禁止 | 说明 |
|---|---|
| ❌ 改 `observe_event` 表结构 | 阶段一定型。缺列说明规划没做够——那也是阶段二加列，不改列 |
| ❌ 降低 `core` 的 Java 语言级别 | 必须从第一天就是 Java 8 |
| ❌ 把 Servlet/Spring 依赖引进 `core` | core 必须保持纯 Java |
| ❌ 重写已通过测试的归一化规则 | 规则错了 → 记入修正清单 → 阶段二修 |
| ❌ 推翻已冻结的技术选型 | 见第五节 |
| ❌ 因为"更好的设计"重构已交付代码 | 记入修正清单 |

**允许的（不算回退）**
- ✅ 加新模块、新类、新表（加法）
- ✅ 在修正清单里登记问题，下一阶段解决
- ✅ 调整目录名 / 工程名
- ✅ 新增配置项、新增可选适配器
- ✅ **实施开始前**修改规划/规格文档（现在就是这个窗口，实施一旦开工即关闭）

**发现问题的正确处理**：写进本文件第七节「修正清单」，下一阶段处理。**不要当场改**。

## 四、阶段状态机

| 阶段 | 目标 | 状态 | 出口条件 |
|---|---|---|---|
| 一 | 三层采集 → 落库 → 图可视化 | **✅ 已完成（2026-09-22）** | 见下方客观清单 |
| 二 | 归一化加固 + 会话聚合 + 边物化 + 修正清单 | 未开始 | — |
| 三 | 适配层（yudao / RuoYi 复用） | 未开始 | — |
| 四 | 图特征 + 小模型 | 未开始 | — |

### 阶段一验收结果（全部通过）

```
✅ 7 条归一化验证清单全过（observe-core，18 个单测）
✅ 一次「列表→详情→保存」操作，库里出现 3 个 layer 的事件（24 条）
✅ 图里出现 5 类节点：PAGE/ACTION/API/SQL/REDIS（21 个）
✅ ★ API 节点数 == 实际调用接口数（3 == 3）—— 证伪了「前后端重复节点」头号风险
✅ 图上看得见 4 类边：NAVIGATE / TRIGGER / CALL / PRECEDES（30 条）
✅ 断开 Redis 时业务降级（代码路径已实现，Redis 异常被捕获）
✅ 采集队列 丢弃=0 失败=0
✅ 浏览器无 JS 异常 / 无 console 错误 / 无 HTTP 4xx
```

**验收方式**：`cd tools/e2e && node accept.mjs` —— 真浏览器（puppeteer-core 驱动本机 Chrome）
走完整业务流程 + 20 条机器断言 + 截图存证（`tools/e2e/shots/`）。**不靠“看起来对”。**

## 五、★冻结决策（不许改）

| # | 决策 | 理由 |
|---|---|---|
| 1 | 阶段一目标 = **裸项目（最高层）** | 复用是加法、补齐是重构；先做复用将来会被迫重构核心 |
| 2 | 容器 = **bind mount 热重载** | 迭代最快 |
| 3 | 阶段一范围 = **三层全上** | 用户指定 |
| 4 | **Java 8 + Spring Boot 2.7** | Java8 是唯一同时覆盖 SB2(`javax`)/SB3(`jakarta`) 的语言级别；用高版本将来只能降级=回退 |
| 5 | **MySQL 8 + Redis 7** | 与 yudao / RuoYi 全生态一致 |
| 6 | **单表 `observe_event`，append-only** | 事件流是唯一必须定型的东西；边查询时实时算 |
| 7 | **MQ 只预留 schema，不引 broker** | DB+Redis 已证明"资源层"通；MQ 同构 |
| 8 | **前后端 API 事件共用同一 `node_key`** | 否则每个接口在图上出现两个节点 |
| 9 | **不自建 SPI 框架**，用 `@ConditionalOnClass` 做能力探测 | 条件装配本身就是能力探测机制 |
| 10 | **SQL 归一化自写状态机**，不引 JSqlParser | parser 失败是"整体丢弃"，自写最坏能降级 |

## 六、汇报协议（对项目所有者）

**项目所有者不是专业程序员。** 因此：

| 类别 | 谁定 |
|---|---|
| 技术选型、框架版本、目录结构、表结构、库选择 | **agent 自定** |
| 目标与验收（用户想看到什么） | 所有者定 |
| 需要动手的环境操作（如启动 Docker Desktop） | 所有者做 |
| 不可逆的外部决定（开源、账号、发布） | 所有者定 |

**输出格式**：`决策 → 一句话理由 → 对所有者有影响吗`

**禁止**：拿需要技术判断才能回答的问题去问所有者（如「JDK8 还是最新版」）。
自行决定后**报告**，所有者可否决。

### ★ 可见产物铁律

**每一步必须有肉眼可见的产物**——一个能点开的 URL、一个页面、一行 SQL 结果。
理由：所有者不该靠读代码判断进度。**没有可见产物的步骤不算做完。**

## 七、每次会话的动作

**开工**
1. 读本文件第四节确认当前阶段
2. 读当前阶段的规格文档（见第九节）
3. 确认 Docker Desktop 已运行（`docker version`）
4. 只做当前阶段的事，不提前做下一阶段

**收工**
1. 更新第四节状态
2. 把本次发现的问题登记到第八节
3. 不在工作树上留半成品

## 八、修正清单（阶段内发现的问题 → 下阶段修）

> 阶段一实施中发现的真实缺口。**一律不回退，阶段二统一处理。**

| # | 发现于 | 问题 | 严重度 | 计划修于 |
|---|---|---|---|---|
| 1 | 阶段一 | 同一张表的不同 SQL 指纹 label 相同（如两条 `SELECT demo_order`），图上难以区分。建议 label 附 WHERE 形状 | 低（显示） | 二 |
| 2 | 阶段一 | Redis 管道（`openPipeline`/`multi`）内的命令采集不到（`RedisCollector.META` 直接放行） | 中 | 二 |
| 3 | 阶段一 | 前端只实现了 CLICK；`EventType.INPUT/SUBMIT` 已定义但未采集 | 中 | 二 |
| 4 | 阶段一 | **跨线程上下文会丢**：`@Async`/自建线程池里 `TraceContext` 借 ThreadLocal 不传递，导致其资源层事件 `parent_key` 为空、`CALL` 边断裂。yudao 用 TransmittableThreadLocal 解决了同类问题，可参照 | **高** | 二 |
| 5 | 阶段一 | 无采样：全量采集。生产需按租户配额的 tail sampling | **高**（上量前） | 二 |
| 6 | 阶段一 | `observe_event` 无分区/归档策略，长期会涨 | 中 | 二 |
| 7 | 阶段一 | 图未预聚合物化，查询时实时算（数据量大后会慢） | 中 | 二 |
| 8 | 阶段一 | 前端 `INPUT` 有意不采 value（安全取舍），仅靠 CLICK 文本推断操作 | 低（有意） | — |
| 9 | 阶段一 | 无多租户排队/隔离：`tenant_id` 列已建但无按租户分区统计 | 中 | 二 |

## 九、文档索引

| 文档 | 内容 | 定型性 |
|---|---|---|
| `specs/PLAN.md` | 总规划、分层适配矩阵、技术选型、阶段划分 | 选型冻结 |
| `specs/SCHEMA.md` | 事件表 DDL、节点/边模型、**API 事件去重规则**、脱敏规则 | ★一次定型 |
| `specs/NORMALIZE.md` | 四条归一化规则 + 7 条验证清单 | ★一次定型 |

## 十、环境事实

| 项 | 状态 |
|---|---|
| 代码仓库 | https://github.com/GoldenBearBiscuit/Panel4FDE （公开；库名与项目名不一致，历史原因，见下方） |
| Docker Desktop | ✅ 已启动，Linux containers，server 29.6.1 |
| 宿主机 Java/Maven | **无** → Java 只能在容器里跑 |
| 宿主机 node/npm/pnpm | v24.19.0 / 11.17.0 / 11.7.0 |
| 宿主机 Chrome | `C:\Program Files\Google\Chrome\Application\chrome.exe`（验收脚本用它） |
| 宿主机 python | 3.12.10（阶段四用） |
| git | 2.55.0（Git Credential Manager 已配，凭据已缓存，push 不需再授权） |
| 镜像加速器 | 已配（daocloud / 1ms / rat.dev / xuanyuan）。`docker manifest inspect` 会绕过加速器，勿用它测连通性 |

### ★ git 工作流约束（pi 内置守卫，不可绕过）

```
BLOCKED: Direct push to protected branch. Use kickoff-branch + release-branch.
```

**agent 不得直接 `git push` 到 `main`/`master`。** 必须：
1. 从默认分支切出特性分支（如 `feat/phase2-xxx`）
2. 推到特性分支
3. 由**人类所有者**在 GitHub 上合并 / 开 PR（或经 release-branch 流程）

注：这是 pi 层面的守卫（不是 git hook，`.git/hooks` 为空）。
初始提交也是推的特性分支 `feat/phase1-observe-pipeline`，因此仓库默认分支名即该特性分支；
需要时由所有者在 GitHub Branches 页改为 `main`。

### 已知环境坑（已修，勿踩回去）

| 坑 | 现象 | 修法 |
|---|---|---|
| Windows→Linux bind mount 不触发 inotify | 改代码页面不变，**且无任何报错** | Vite `server.watch.usePolling = true` |
| maven 镜像无 `curl` | 容器内探活全失败 | 用 `/observe/ping` + 宿主机 node 探活 |
| `docker compose logs` 带旧容器历史 | 探活抓到上一次运行的历史行，误判成败 | 探活只看 `--tail`，或 `--force-recreate` |

## 十一、关键实现陷阱（已修，注释已写进代码）

1. **BeanPostProcessor 会用 ObjectProvider 惰性取 EventQueue** —— 否则 DataSource 先于 BPP 被创建，
   SQL 采集**静默失效且无任何报错**。见 `DataSourceCollector#queue()`
2. **代理必须实现目标类的全部公开接口** —— 只代理一个会让 bean 从其他接口注入点消失。
   Lettuce 工厂同时实现 `RedisConnectionFactory` + `ReactiveRedisConnectionFactory`，曾导致应用启动失败。
   见 `Proxies#interfacesOf` + `ProxiesTest`
3. **API 事件的 `occurred_at` 用请求开始时刻** —— 用响应时刻会让同一步内 SQL/REDIS 排在 API 之前，
   PRECEDES 边反向
4. **G6 布局是异步的** —— 必须在 `afterlayout` 里读节点坐标，早读会算出完全错误的包围盒
5. **API 节点的 layer 用优先级判定（BACKEND > RESOURCE > FRONTEND）** —— 取“先到的那条”是非确定的
