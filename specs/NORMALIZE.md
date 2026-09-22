# 归一化规则（★一次定型，不可回退）

> 归一化决定图的**规模**与**泛化能力**。不做归一化，节点数 = 数据行数，模型直接废掉。
> 这是整个项目最重要的一层，比模型选择重要十倍。

## 原则

1. **归一化结果 = 图节点 ID**（`node_key`）。同一件事无论发生多少次，必须是同一个 `node_key`。
2. **原始值不丢**：归一化只用于 `node_key`；真实值全部进 `raw_payload`（脱敏后）。否则出了问题无法排查。
3. **`node_label` 用于显示**，`node_key` 用于聚类。两者都要存。

---

## R1 — URL 归一化

**规则**：只看 path，逐个路径段判断；`query string` 不进 `node_key`（进 `raw_payload`）。

| 路径段形态 | 替换为 | 例 |
|---|---|---|
| 纯数字 | `{id}` | `/api/order/123/detail` → `/api/order/{id}/detail` |
| UUID (`8-4-4-4-12` hex) | `{uuid}` | `/api/task/8f3a…-9` → `/api/task/{uuid}` |
| 长度 ≥ 16 的 hex/base64url | `{hash}` | `/api/file/a1b2c3…` → `/api/file/{hash}` |
| 含 `@` | `{email}` | `/api/user/a@b.com` → `/api/user/{email}` |

**不做归一化的情况**：路径段是**已知固定词**（如 `/api/order/list`、`/api/order/create`）——`list`/`create` 是语义，必须保留，**不能被误判成 hash**。

**反例（必须避免）**：`/api/order/list` → `/api/order/{hash}` ❌
→ 实现时必须要求 `{hash}` 的字符集限制在 `[0-9a-f]` 且长度 ≥ 16，`list` 只有 4 位且含非 hex 字符，天然不会命中。

---

## R2 — SQL 归一化

**目标**：`WHERE id = 12345` 与 `WHERE id = 67890` 必须归一化到同一条指纹。

**处理顺序（顺序不能变）**：

1. 去注释：`-- …` 到行尾、`/* … */`
2. 折叠空白：连续空白/换行 → 单个空格，去首尾
3. 字符串字面量 → `?`（注意 MySQL 的 `''` 转义与 `\"`）
4. 数字字面量 → `?`（**必须跳过标识符内的数字**，如 `t1`、`utf8mb4`、`v2`）
5. 括号内逗号列表 → 单项：`IN (?, ?, ?)` → `IN (?)`
6. 关键字统一大写
7. 生成 `node_key`：`sql:` + `sha1(指纹).substring(0,16)`

**示例**
```
输入: SELECT * FROM orders WHERE user_id = 88231 AND status = 3 AND name = 'zhang'
指纹: SELECT * FROM orders WHERE user_id = ? AND status = ? AND name = ?
```

**为什么要第 4 步的例外**：`FROM orders_2024 t1` 里的 `2024` 和 `t1` 是标识符，替换成 `?` 会得到 `FROM orders_? t?`，导致**不同表被合并成同一个节点**——这是静默的错误数据，比不归一化更糟。

**`node_label`**：取指纹的 `SELECT/UPDATE/INSERT/DELETE` + 第一张表名，如 `SELECT orders`。

**`raw_payload`**：存**完整原始 SQL**（含真实值，按脱敏规则处理）。

---

## R3 — DOM selector 归一化

**优先级（命中即用，不再往下走）**：

1. `data-observe-id` 属性 — 显式声明，最稳（推荐业务方手动加，但**不加也能用**）
2. `#id` — 稳定 id
3. `[name=…]` — 表单元素
4. 语义类的 `button`/`a` 的 `data-*` 或稳定 class
5. 兜底：`tag` + 稳定 class 组合（**过滤掉含随机后缀的 class**，如 `css-1x2y3z`、`_abc123`）

**严禁使用 `:nth-child()` / 绝对 DOM 路径** — 位置不稳定，同一按钮在不同数据下位置会变，会把一个节点炸成几十个。

**`node_label`**：元素可见文本，截断 32 字符。

---

## R4 — Redis key 归一化

**规则**：`:` 分隔的段逐个判断，与 R1 同构。

```
user:88231:profile   → user:{id}:profile
token:8f3a…-…-9      → token:{uuid}
session:a1b2c3d4e5f6… → session:{hash}
```

**不归一化**：固定前缀段（`user`、`token`、`session`）必须原样保留 —— 它们才是语义。

**`node_label`**：取命令名 + key 前缀，如 `GET user:*`。

---

## 验证清单（阶段一测试必过）

| # | 输入 | 期望 |
|---|---|---|
| 1 | `/api/order/123` 与 `/api/order/456` | 同一 `node_key` |
| 2 | `/api/order/list` | **不等于** 任何 `{hash}` 归一化结果 |
| 3 | `WHERE id=1` 与 `WHERE id=2` | 同一 `node_key` |
| 4 | `FROM orders_2024 t1` | 表名与别名**未被替换** |
| 5 | `IN (1,2,3)` 与 `IN (4,5)` | 同一 `node_key` |
| 6 | `user:1:profile` 与 `user:2:profile` | 同一 `node_key` |
| 7 | `#save-btn` 的按钮 | `node_key` 不含 `nth-child` |
