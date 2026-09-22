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
| 10 | 阶段一 | 前端点击非交互区域（topbar、卡片空白）时 selector 会向上走到泛容器（`#app`），把所有空白点击塌成一个假节点。已加 `TOO_GENERIC` 名单 + `findTarget()` 放弃泛容器，但“是否可交互元素”的判断仍偏粗糙 | 低 | 二 |
| 11 | 阶段一 | `initObserve` 缺幂等保护：HMR 重跑 `main.js` 会重复注册 click 监听 → 一次点击产生 N 条事件（各自新建 traceId，极难排查）。已加 `initialized` 标志 | ✅ 已修 | — |
| 12 | 阶段一 | 事件流（左栏）按**到达顺序**而非 `occurred_at` 排序，同一步内 API 事件显示在 SQL/REDIS 之后。有意设计（实时 tail 语义，文档已说明），勿当 bug 修 | 低（有意） | — |
| 15 | 阶段一 | **G6 的 `graph.translate()` 是「相对当前变换」而非绝对设置**：布局收敛期调了 3 次，偏移累积 3 倍，内容越跑越偏。已改为用 `getGroup().getCanvasBBox()` 反推位移，**调多少次都收敛到同一位置**（自校正） | ✅ 已修 | — |
| 16 | 阶段一 | 节点用「小图形 + 下方标签」时，dagre 按节点尺寸排布、不知道标签会溢出节点框 → 相邻节点标签互相压住读不了。已改为「宽框 + 文字放框内」，并把标签宽度作为节点尺寸交给布局 | ✅ 已修 | — |
| 17 | 阶段一 | ★ 流程图的布局收敛定时器（400/1000/1800ms）**没有取消机制**：切到泳道图后它们才触发，会在**泳道图**上跑 `changeSize`/`translate` 把布局搞歪，并覆盖 `__graphInfo`。已加 `clearFlowTimers()` + `fitCanvasToContent` 的 `viewMode` 守卫。（由验收脚本发现：`laneViolations` 变 undefined） | ✅ 已修 | — |
| 13 | 阶段一 | `docker/mysql-init/*.sql` 未声明字符集：mysql 客户端默认字符集取自容器 locale，POSIX/C 下退到 latin1 → 中文种子数据被双写编码，走 JDBC 读出来是 `æŽå››`。已加 `SET NAMES utf8mb4`。**教训：验证中文必须走 JDBC 路径，mysql 客户端是对称的、会骗人** | ✅ 已修 | — |
| 14 | 阶段一 | 会话下拉框在“当前会话尚未落库进列表”时显示空白。已加占位项 + 降频刷新会话列表 | ✅ 已修 | — |

### 阶段一的 L9 增强（阶段一范围内）

观测能力从「静态图」→「同页两栏实时控制台」→「通用不污染机制」：

**1. 同页两栏外壳**
- 左栏是**真正的路由页面**（`<router-view>`），所以页面切换仍产生 `NAVIGATE` 边。
  ★ 若做成纯同页 tab 切换，会丢掉一整类边——这是选路由而非 tab 的原因。
- 右栏是实时观测面板（`components/LivePanel.vue`，`mode="stack"` 上事件流 / 下图；
  `mode="side"` 左事件流 / 右图，供全屏页用）

**2. 增量事件流接口**
`GET /observe/events?afterId=` 用自增 id 做游标（同毫秒多条也不丢不重，比时间戳可靠）

**3. ★ “观测不污染被观测对象”三层机制**
| 层 | 机制 | 防什么 |
|---|---|---|
| 后端 | 排除 `/observe/**` | 取图/取事件的请求不产生 API 事件 |
| 前端 | `ignorePaths: ['/graph','/observe']` | 全屏观测台页本身不被采集 |
| 前端 | **`data-observe-ignore` 属性**（通用能力） | 同页两栏时右侧面板的按钮不被采集 |

验收脚本会连点面板按钮 3 次并断言事件总数不变（已通过）。这是本项目最易忽略、
但一旦漏了就会让图变成一个不断长大的怪东西的隐式约束。

**4. SDK 幂等与选择器防护**
- `initObserve` 幂等（HMR / 重复调用不会再叠监听器）
- `findTarget()` + 泛容器名单，避免空白点击塌成 `action:#app` 假节点

**5. ★ 人与自动的区分（EdgeType.AUTO，阶段一中发现并修正的重大语义缺陷）**

发现：用户提问「为啥一开始就有请求啊，页面上分不清人类的点击和默认的请求吗」。
旧实现把「人触发的接口」和「页面挂载自动拉的接口」**都标成了 TRIGGER**，
图上无法区分。这是建模的致命伤：后续模型要学的是**人的操作**，不区分就只能学噪声。

修法（**零新增字段**，信息本来就存在）：边语义完全由「子事件类型 + parent 节点类型」推导：

| 子事件 | parent | → 边 |
|---|---|---|
| PAGE_VIEW | `page:` | NAVIGATE |
| PAGE_VIEW | `action:` | TRIGGER（人点击导致的跳转） |
| API | `action:` | **TRIGGER**（人干的） |
| API | `page:` | **AUTO**（页面自动干的） |
| 其余 | — | PRECEDES |

前端判定用**意图窗口**：`intentWindowMs`（默认 1200ms）内有用户交互、
且该交互尚未被别的请求消费 → 算人为；且**导航会把那次点击标记为已消费**，
所以新页面挂载发的请求不会错归给“点了详情”那个 action。

验收断言（关键三条）：
```
action:#btn-save --TRIGGER--> api:/api/order/{id}/save   ✅
page:/order/list --AUTO-----> api:/api/order/list        ✅
「只看人为」：19节点/28边 → 16节点/12边                    ✅
```

注：`EdgeType` 加值是**加法**，`edge_type` 列宽 VARCHAR(16) 容得下 `AUTO`，
不构成“改表结构”，不违反冻结约束。

**6. ★ 泳道图（第二种渲染布局，阶段一中新增）**

需求：用户要“泳道图”，并明确“渲染的时候加一些处理，记录的格式还是图”。

这是**纯渲染层**的改动，不动表、不动图模型：
- 数据里本来就有两个坐标：`step_no`（时序）与 `layer`（层）
- 泳道图把它俩当作 x / y 用：**横 = 步序列，纵 = 层**
- 不用 dagre（固定布局，位置是算出来的）→ 无异步收敛问题，图不会跳
- 构图接口新增返回 `step`（节点首次出现的步序）—— 纯响应字段加法

实现要点：
- 列宽 = 该列最宽节点 + 间距；泳道高 = 该层最多的单元格内节点数（自适应）
- 同格内多节点自动纵向堆叠
- 泳道名用独立左栏 + `translateY` 跟随纵向滚动（不随横向滚动跑掉）
- 泳道图里 `PRECEDES` 是纯噪声（x 轴已是时序）→ 自动隐藏
- **泳道自检**：每个节点必须落在它所属层的泳道带内，违规数写入 `__graphInfo.laneViolations`，
  验收断言必须为 0（泳道图的核心正确性约束）

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
