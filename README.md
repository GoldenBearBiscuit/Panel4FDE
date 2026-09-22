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

### 默认就是同页两栏（不用切标签页）

打开 **http://localhost:5173**（或 http://localhost:5173/order/list）：

```
┌─ observe-kit demo    订单（同页观测）  全屏观测台                会话 46db0677 ─┐
├─────────────────────┬───────────────────────────────────────────────────┤
│ 订单详情 #1    [返回列表][全屏观测台] │ [会话 ▾]            自动 刷新 当前会话 适应画布 46/21/18 │
│                    │ ┌─ 实时记录 ───────────────────────────────┐   │
│ 客户                 │ │ 21:58:32.448 ● 资源 SQL  UPDATE demo_order ←CALL 27ms │   │
│ [验收-张三        ] │ │ 21:58:32.458 ● 资源 REDIS DEL order:detail:*  ←CALL 7ms  │   │
│                    │ │ 21:58:32.380 ● 后端 API  POST /api/order/{id}/save 86ms │   │
│ 状态                 │ │ 21:58:31.997 ● 前端 CLICK 张三                          │   │
│ [SHIPPED          ▾] │ └────────────────────────────────────────────┘   │
│                    │ ┌─ 操作路线图  ●前端 ●后端 ●资源层 ——虚=PRECEDES ┐  │
│ [保存]  已保存       │ │              （G6 图）                        │  │
│                    │ │                                              │  │
└─────────────────────┴───────────────────────────────────────────────────┘
```

**左栏是真正的业务页面**（走路由，所以点「详情」仍会产生 `NAVIGATE` 边——
如果做成纯同页 tab 切换，就丢了一整类边）。**右栏是实时观测**：上面事件流，下面图。

- **事件流**：毫秒时间戳 + 层级色点 + 事件类型 + 节点名 + 边类型 + 耗时。
  新事件**蓝底高亮 1.5 秒**，显示「N 条新」。自动滚到底部（手动上滚则暂停跟随）。
- **图**：仅在数据签名（事件/节点/边数）变化时重绘，避免每秒重排的闪烁与视口跳回。
- 两栏各自独立滚动，填满窗口高度。

### 三种视图

| 视图 | 地址 | 用途 |
|---|---|---|
| **同页两栏** | http://localhost:5173/order/list | 默认。左边操作、右边实时看 |
| 全屏观测台 | http://localhost:5173/graph | 事件流左 / 图右，给图最大空间 |
| 静态结果 | 同上，关掉「自动」即停止刷新 | 看某一刻的快照 |

### “观测不污染被观测对象”——三层保障

这是本项目最容易被忽略、但必须守住的一条：

| # | 机制 | 防的是什么 |
|---|---|---|
| 1 | 后端排除 `/observe/**` | 取图/取事件的请求自己不产生 API 事件 |
| 2 | 前端 `ignorePaths: ['/graph','/observe']` | 全屏观测台页本身不被采集 |
| 3 | 前端 `data-observe-ignore` 属性 | **同页两栏时，右侧面板的按钮不被采集** |

第 3 条是通用能力：任何元素加上 `data-observe-ignore`，它整个子树内的点击都不采集。
验收脚本会连点面板按钮 3 次并断言事件总数不变（已通过）。

否则你点一次「刷新」，图上就多一个节点——久了就变成一个不断长大的怪东西。

### 关于事件流的顺序

事件流是**到达顺序**（按自增 id），不是时间顺序。所以同一次请求里，
SQL/REDIS 会显示在它所属的 API 事件**前面**——因为资源调用先执行完，
API 事件在响应返回时才落库（`occurred_at` 记的仍是请求开始时刻）。
这是实时 tail 的正确语义，不是 bug。要按时间排序请看图。

### 图长什么样

```
颜色 = 层                                     线型 = 边的语义
蓝 = 前端（页面 / 点击）                    深色实线 = 人为（TRIGGER / NAVIGATE）
黄 = 后端（接口）                            浅灰实线 = 页面自动（AUTO）
绿 = 资源层（SQL / Redis）                   绿灰实线 = 资源调用（CALL）
                                              极浅虚线 = 时序兜底（PRECEDES）
```

### ★ 人和自动是分开的（这是你问过的问题）

页面一加载就会有请求（组件 `onMounted` 拉数据），这是真实发生的事情，**确实应该被记录**。
但如果不区分“人点的”和“页面自动发的”，后面的模型就会把噪声当人的行为学。所以：

| 边 | 含义 | 从 → 到 |
|---|---|---|
| `TRIGGER` | **人**的操作引发 | `action:` → `api:` / `action:` → `page:` |
| `AUTO` | **页面自动**引发（挂载拉数据、轮询、预加载） | `page:` → `api:` |
| `NAVIGATE` | 页面跳转 | `page:` → `page:` |
| `CALL` | 调用资源 | `api:` → `sql:` / `redis:` |
| `PRECEDES` | 时序兜底（只表示“先后”） | 任意相邻 |

判定机制：**意图窗口**。只有「最近 1.2 秒内有过用户交互、且这次交互还没被别的请求消费」
的请求才算人为；页面挂载时的请求不算（导航会把那次点击标记为已消费）。

实测效果（验收脚本客观断言）：

```
action:#btn-save  --TRIGGER-->  api:/api/order/{id}/save     ✅ 人点的「保存」
page:/order/list  --AUTO----->  api:/api/order/list          ✅ 页面自己拉的列表
```

**控制条上的「只看人为」**勾上后，滤掉 `AUTO` 与 `PRECEDES` 边及相关节点，
只留下人的操作路线（实测 19节点/28边 → 16节点/12边）。

### 完整路线长这样

```
订单列表 ──AUTO──► GET /api/order/list          ← 页面挂载自动拉的
                      ├──CALL──► GET order:list:page:*
                      ├──CALL──► SELECT demo_order
                      └──CALL──► SETEX order:list:page:*
       └─PRECEDES─► 详情(点击) ──TRIGGER──► 订单详情 ──► … ──► 保存(点击)
                                                                   └──TRIGGER──► POST /api/order/{id}/save
                                                                                     ├─CALL─► UPDATE demo_order
                                                                                     └─CALL─► DEL / SETEX …
```

图很长是正常的：**一条操作路线本来就是一条时间线**，纵向滚动着看。
宽图（如「只看人为」）则横向滚动；点「适应画布」可一屏看全（会被缩小）。

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
