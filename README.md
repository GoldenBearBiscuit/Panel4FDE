# observe-kit

框架无关的应用行为观测插件：**无业务侵入**地采集「前端操作 + 后端接口 + 资源层（SQL/Redis/MQ）」三层事件，
归一化成图，最终在图上跑小模型。

> 当前进度：**阶段一已完成** —— 网页操作能被画成三层图。
> 规划细节见 `specs/`，agent 工作手册见 `AGENT.md`。

---

## 一、怎么跑起来（三条命令）

```bash
cd docker
docker compose up -d
```

然后浏览器打开 **http://localhost:5173**

| 地址 | 是什么 |
|---|---|
| http://localhost:5173 | demo 业务页面（订单列表 / 详情）+ 观察图 |
| http://localhost:8080/api/order/list | 后端接口直连自检 |
| http://localhost:8080/observe/ping | 插件健康检查 |
| http://localhost:8080/observe/stats | 采集自身健康度（丢弃数/失败数） |

首次启动约 1~2 分钟（Maven 要下依赖、npm 要装包）。之后改代码只需 `docker compose restart app` 或 `restart web`。

**依赖**：Docker Desktop 必须处于运行状态。宿主机不需要装 Java。

---

## 二、怎么看效果

### 推荐姿势：开两个标签页

| 标签页 | 地址 | 干什么 |
|---|---|---|
| ① 操作页 | http://localhost:5173/order/list | 点「详情」→ 改客户名/状态 → 「保存」 |
| ② 观测台 | http://localhost:5173/graph | **盯者看** |

观测台是**两栏实时控制台**：

```
┌─ 控制条：会话选择 ▾  自动刷新 ✓  刷新  看当前会话  适应画布   事件22 · 节点19 · 边28 ─┐
├──────────────────────────┬──────────────────────────────────────────┤
│ 实时记录     [22 条新]       │ 操作路线图      ● 前端 ● 后端 ● 资源层      │
│                          │                                          │
│ 21:52:27.356 ● 资源 REDIS │              订单列表                    │
│ 21:52:27.359 ● 资源 SQL   │                │ TRIGGER                   │
│ 21:52:27.353 ● 后端 API   │                ▼                          │
│ 21:52:28.511 ● 前端 CLICK │        ┌─ GET /api/order/list ─┐            │
│ 21:52:28.512 ● 前端 PAGE  │        │    └─CALL─► GET ...    │            │
│ ...                      │        └─ SELECT demo_order     │            │
└──────────────────────────┴──────────────────────────────────────────┘
```

- **左栏**：每一条采集到的事件，毫秒时间戳 + 层级色点 + 事件类型 + 节点名 + 边类型 + 耗时。
  新事件会**蓝底高亮 1.5 秒**，左上角显示“N 条新”。自动滚到底部（手动往上滚就暂停跟随）。
- **右栏**：图，**数据变了才重绘**（不是每秒重排，否则会闪）。
- 两栏各自独立滚动，整个页面填满窗口高度。

**观测台自己不采集自己**（前端 `ignorePaths` + 后端排除 `/observe/**`），
否则“看图的动作”会把被看的图污染成一个不断长大的怪东西。

### 关于事件流的顺序

左栏是**到达顺序**（按自增 id），不是时间顺序。所以同一次请求里，
SQL/REDIS 会显示在它所属的 API 事件**前面**——因为资源调用先执行完，
API 事件在响应返回时才落库（`occurred_at` 记的仍是请求开始时刻）。
这是实时 tail 的正确语义，不是 bug。要按时间排序请看右栏的图。

### 单页方式

只想看静态结果：打开 http://localhost:5173 → 操作 → 点顶部「实时观测台」。

你会看到刚才的操作被画成一张图：

```
蓝圆 = 前端（页面 / 点击）      黄方 = 后端（接口）      绿菱 = 资源层（SQL / Redis）

订单列表 ──TRIGGER──► GET /api/order/list
                          ├──CALL──► GET order:list:page:*
                          ├──CALL──► SELECT demo_order
                          └──CALL──► SETEX order:list:page:*
       └─PRECEDES─► 详情(点击) ──NAVIGATE──► 订单详情 ──► … ──► 保存
                                                                  └──TRIGGER──► POST /api/order/{id}/save
                                                                                    ├─CALL─► UPDATE demo_order
                                                                                    └─CALL─► DEL / SETEX …
```

- 实线 = 有语义的边（TRIGGER / CALL / NAVIGATE）
- 虚线 = PRECEDES（时序兜底，只表示“先后”）
- 图很长是正常的：**一条操作路线本来就是一条时间线**，纵向滚动着看

页面上的图可以拖拽平移、滚轮缩放；点「适应画布」可一屏看全（会被缩小）。

### 其它接口

| 接口 | 用途 |
|---|---|
| `GET /observe/sessions` | 最近有事件的会话列表 |
| `GET /observe/graph?sessionId=` | 整个会话的图（节点+边） |
| `GET /observe/events?sessionId=&afterId=` | **增量**事件流（`afterId` 游标，观测台靠它实时刷新） |
| `GET /observe/stats` | 采集健康度（接收/落库/丢弃/失败） |

---

## 三、想确认数据是真的（不看界面）

```bash
# 看事件是否落库
docker exec ok-mysql mysql -uroot -proot observe -e "
SELECT layer, event_type, COUNT(*) FROM observe_event GROUP BY layer, event_type;"

# 看采集健康度（★ 丢弃数不为 0 说明有数据缺口）
curl http://localhost:8080/observe/stats
```

---

## 四、怎么验证（自动验收）

```bash
cd tools/e2e
npm install          # 只装 puppeteer-core（用本机 Chrome，不下载 Chromium）
node accept.mjs
```

它会用真浏览器把业务流程走一遍，跑 **20 条机器断言**（含最关键的一条：
**API 节点数必须等于实际调用接口数** —— 若前后端事件没合并，这个数会翻倍），
并把截图存到 `tools/e2e/shots/`。

当前状态：**20/20 通过**。

---

## 五、项目结构

```
observe-kit/
├── AGENT.md                     ← agent 工作手册（阶段状态、冻结决策、修正清单）
├── specs/
│   ├── PLAN.md                  总规划、分层适配矩阵、技术选型
│   ├── SCHEMA.md                事件表 DDL、节点/边模型、★API 事件去重规则、脱敏规则
│   └── NORMALIZE.md             四条归一化规则 + 7 条验证清单
├── docker/
│   ├── docker-compose.yml       app + web + mysql + redis
│   └── mysql-init/              建表 SQL
├── observe-core/                ★纯 Java8、零依赖：事件模型 + 归一化 + 脱敏
├── observe-spring-boot-starter/ 自动装配 + 三层采集器 + 落库 + 构图接口
├── demo/
│   ├── demo-app/                「最高层」裸 Spring Boot（业务代码零埋点）
│   └── demo-web/                Vue3 + Vite + AntV G6
└── tools/e2e/                   puppeteer 验收脚本 + 截图
```

---

## 六、接入一个已有项目要改什么

| 层 | 改动量 |
|---|---|
| 后端 | **加一条 Maven 依赖** + `application.yml` 里几行配置。业务代码一行不动 |
| 资源层（SQL / Redis） | **零改动**（自动挂载，看启动日志 `[observe] SQL 采集已挂载`） |
| 前端 | **两行**：`import { initObserve } from './observe.js'` + `initObserve({ router })` |
| 数据库 | 建一张表 `observe_event`（SQL 在 `docker/mysql-init/01-observe-schema.sql`） |

**前端那两行是物理下限** —— 浏览器里没有别的地方能自动拿到路由和 fetch。

---

## 七、三层是怎么做到无侵入的（原理一句话版）

| 层 | 机制 |
|---|---|
| 前端 | `document` 捕获阶段监听点击（不用给每个组件挂监听）+ 包装 `window.fetch` |
| 后端 | `OncePerRequestFilter` 读 W3C `traceparent` → traceId → MDC，请求结束落一条 API 事件 |
| 资源层 SQL | 在 `DataSource` 上套 **JDK 动态代理**，逐层代理到 `PreparedStatement`，拦 `execute*` 拿真实 SQL + 参数 + 耗时 |
| 资源层 Redis | `RedisConnection` 是**接口**，代理它一个类就能拦全部约 300 个命令 |

三层靠 **`trace_id`** 缝合成同一次调用；靠 **`node_key`（归一化指纹）** 让前端发起的请求
和后端执行的请求**塌成同一个节点**。

---

## 八、下一阶段要修的问题

见 `AGENT.md` 第八节「修正清单」。最要紧的两条：

- **跨线程上下文会丢**（`@Async` / 线程池里 `CALL` 边会断）
- **无采样**：上量前必须加按租户配额的 tail sampling
