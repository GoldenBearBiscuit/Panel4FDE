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

1. 打开 http://localhost:5173 → 进「订单列表」
2. 点某一行的 **详情** → 改客户名 / 改状态 → 点 **保存**
3. 点顶部 **操作路线图**

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
