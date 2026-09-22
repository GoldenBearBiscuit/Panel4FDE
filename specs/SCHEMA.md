# 数据模型（★一次定型，不可回退）

阶段一范围是**三层全上**，所以事件表必须一次装得下三层。改这张表 = 回退，不允许。

## 一、节点类型（`node_key` 前缀 = 类型）

| 前缀 | 节点类型 | 来源层 | `node_key` 生成 |
|---|---|---|---|
| `page:` | 前端页面/路由 | FRONTEND | 归一化后的路由 path |
| `action:` | 用户操作（点击/输入/提交） | FRONTEND | R3 归一化 selector |
| `api:` | 后端接口 | FRONTEND + BACKEND | R1 归一化 URL |
| `sql:` | 数据库语句 | RESOURCE | R2 归一化 SQL |
| `redis:` | Redis 命令 | RESOURCE | R4 归一化 key |
| `mq:` | 消息（**阶段一仅预留**） | RESOURCE | topic + tag |

## 二、边类型

| 边 | 从 → 到 | 引入者 |
|---|---|---|
| `NAVIGATE` | `page:` → `page:` | 前端页面切换事件 |
| `TRIGGER` | `action:` → `api:` | 前端发出的请求事件 |
| `CALL` | `api:` → `sql:` / `api:` → `redis:` | 后端资源层事件 |
| `PRECEDES` | 同 session 相邻节点 | 构图时按 `step_no` 兜底补 |

## 三、三层 → 图的连接方式（关键设计）

```
前端层   page:list ──NAVIGATE──► page:detail
             ▲                        │
             │                        │ CLICK
        action:btn-save ◄─────────────┘
             │
             │ TRIGGER
             ▼
         api:POST /api/order/{id}/save        ◄── 前后端事件在此节点天然合并
             │
             │ CALL
             ▼
    sql:UPDATE orders … / redis:SET order:{id}
```

### ★ 前后端 API 事件的去重规则（必须一次做对）

同一次请求，前端和后端**各产生一条事件**：前端记"我发起了这个请求"，后端记"这个请求实际执行了"。

**两层的 `node_key` 必须是同一个**（都用 R1 归一化后的 URL）→ 图里自动合并成**一个** `api:` 节点。
- 前端事件提供 `TRIGGER` 边（`action:` → `api:`）
- 后端事件提供 `CALL` 边（`api:` → `sql:`）+ `duration_ms` / `status`

**若不这样做**，图里每个接口都会出现两个节点（前端一个后后端一个），图直接废掉。

**例外**：请求没到后端（404 / 网络失败）时只有前端节点，属正常。

### ★ 四个身份维度，不要混用

| 身份 | 回答什么 | 值 | 能用于去重？ |
|---|---|---|---|
| `node_key` | 这是同一**类**操作吗 | `api:POST /api/order/{id}/save` | ✅ **去重唯一依据** |
| `trace_id` | 这是同**一次**调用吗 | 每次请求一个 32-hex | ✅ 前后端对齐靠它 |
| `session_id`+`step_no` | 会话内排第几 | — | ❌ 画路线用 |
| `user_id`/`tenant_id` | 谁 / 哪个租户 | — | ❌ **维度，不是身份** |

**为什么不用「用户id + 流水号」去重**
- 并发下不唯一：同一用户同一会话可以同时飞多个请求（点了保存 + 页面轮询）
- 流水号两侧对不上：前端生成的号后端不知道，反之亦然 —— 最后还是得靠 `trace_id`

`trace_id` **就是**那个流水号，且是后端能自动读到、并发天然唯一的标准实现。

**★ 禁止把 `user_id`/`tenant_id` 放进 `node_key`**
```sql
node_key = api:u88231:POST /api/order/123/save   -- ❌ 图爆炸：用户数 × 节点数
node_key = api:POST /api/order/{id}/save          -- ✅ 一个节点
-- user_id / tenant_id 是列，用于过滤与切片统计
```
这与「SQL 不归一化」是同一类致命错误。符合「共享拓扑、按租户分区统计量」原则。

**★ 前端必须每次请求新建 `trace_id`，不能会话级复用**
若复用：同一接口被调 5 次会被当成同一次调用，`duration`/`status`/`CALL` 边全糊在一起。
此错误比节点重复更隐蔽。

**一次调用在库中的样子（靠 `trace_id` 串联）**

| layer | event_type | node_key | edge_type |
|---|---|---|---|
| FRONTEND | CLICK | `action:btn-save` | — |
| FRONTEND | API | `api:POST /api/order/{id}/save` | TRIGGER |
| BACKEND | API | `api:POST /api/order/{id}/save` | ← **同 key，塌成一个节点** |
| RESOURCE | SQL | `sql:a1b2…` | CALL |

**构图 = 两个 GROUP BY**（去重天然发生，无需特殊逻辑）
```sql
-- 节点
SELECT node_key, MIN(node_label) label, layer, event_type, COUNT(*) cnt
FROM observe_event WHERE session_id = ? GROUP BY node_key;

-- 边
SELECT parent_key src, node_key dst, edge_type, COUNT(*) weight
FROM observe_event WHERE session_id = ? AND parent_key IS NOT NULL
GROUP BY parent_key, node_key, edge_type;
```

## 四、事件表 DDL

```sql
CREATE TABLE observe_event (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  event_id     CHAR(36)     NOT NULL COMMENT '事件唯一ID(uuid)',
  trace_id     CHAR(32)     DEFAULT NULL COMMENT '★三层缝合线',
  session_id   CHAR(36)     NOT NULL COMMENT '会话ID',
  step_no      INT          NOT NULL COMMENT '会话内步序,从1开始',

  layer        VARCHAR(16)  NOT NULL COMMENT 'FRONTEND/BACKEND/RESOURCE',
  event_type   VARCHAR(24)  NOT NULL COMMENT 'PAGE_VIEW/CLICK/INPUT/SUBMIT/API/SQL/REDIS/MQ',
  node_key     VARCHAR(255) NOT NULL COMMENT '★归一化指纹=图节点ID',
  node_label   VARCHAR(255) DEFAULT NULL COMMENT '图上显示名',

  parent_key   VARCHAR(255) DEFAULT NULL COMMENT '父节点指纹,用于建边',
  edge_type    VARCHAR(16)  DEFAULT NULL COMMENT '本事件引入的边类型',

  occurred_at  DATETIME(3)  NOT NULL COMMENT '发生时间(毫秒精度)',
  duration_ms  INT          DEFAULT NULL,

  status       VARCHAR(16)  NOT NULL DEFAULT 'OK' COMMENT 'OK/ERROR',
  error_msg    VARCHAR(512) DEFAULT NULL,

  tenant_id    BIGINT       DEFAULT NULL COMMENT '多租户维度',
  user_id      BIGINT       DEFAULT NULL,
  app_name     VARCHAR(64)  DEFAULT NULL COMMENT 'spring.application.name',

  raw_payload  JSON         DEFAULT NULL COMMENT '★原始值(已脱敏),用于排查',

  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_event (event_id),
  KEY idx_session (session_id, step_no),
  KEY idx_trace (trace_id),
  KEY idx_node (node_key),
  KEY idx_time (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='观测事件流(append-only)';
```

## 五、为什么只有这一张表

- **append-only 事件流**是唯一必须一次定型的东西。
- 图的边**查询时实时算**（`GROUP BY node_key`, `parent_key`），阶段一数据量小，够用。
- 预聚合表 `observe_edge`、会话表 `observe_session` **等有性能问题再加** —— 加表是加法，不算回退。
- `schema` 已能装三层 + MQ，所以**永远不会因为"少了一列"而回退**。

## 六、脱敏规则（不可简化的安全边界）

**两层防护，缺一不可**：

**前端（源头）**
- `input[type=password]` 的 `value` **永不采集**（不是脱敏，是不采）
- 所有输入框默认只记 `value.length`，不记 value

**后端（落库前）**
- **字段名黑名单**（命中即整体替换为 `***`）：
  `password` `passwd` `pwd` `token` `accessToken` `refreshToken` `authorization`
  `secret` `idCard` `idNumber` `bankCard` `cvv` `smsCode` `captcha`
- **正则掩码**（保留可读性，便于排查）：
  | 类型 | 规则 | 例 |
  |---|---|---|
  | 手机号 | 保留前3后4 | `138****8888` |
  | 身份证 | 保留前6后4 | `110101********1234` |
  | 邮箱 | 保留首字符+域名 | `z***@a.com` |
  | 银行卡 | 保留后4 | `****1234` |
- **HTTP 头**：只留白名单（`Content-Type` `User-Agent`），其余全丢
- 规则**配置驱动**（`observe.mask.*`），因为不同租户敏感字段不同
