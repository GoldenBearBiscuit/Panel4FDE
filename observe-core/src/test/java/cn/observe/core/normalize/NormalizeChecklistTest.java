package cn.observe.core.normalize;

import cn.observe.core.mask.Masker;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ★ NORMALIZE.md 的 7 条验证清单 + 边界用例。
 *
 * <p>这份测试是阶段一验收的第一条：7 条全过才允许往下走。
 * 归一化出错属于「静默错误数据」——比不归一化更糟，所以必须有机器校验。
 */
public class NormalizeChecklistTest {

    // ══ 清单 #1: /api/order/123 与 /api/order/456 → 同一 node_key ══════
    @Test
    public void c1_sameIdDifferentValueSameNode() {
        assertEquals(UrlNormalizer.nodeKey("/api/order/123"),
                UrlNormalizer.nodeKey("/api/order/456"));
        assertEquals("api:/api/order/{id}", UrlNormalizer.nodeKey("/api/order/123"));
    }

    // ══ 清单 #2: /api/order/list ≠ 任何 {hash} 归一化结果 ═════════════
    @Test
    public void c2_semanticSegmentNotMistakenForHash() {
        assertEquals("api:/api/order/list", UrlNormalizer.nodeKey("/api/order/list"));
        assertEquals("api:/api/order/detail", UrlNormalizer.nodeKey("/api/order/detail"));
        assertEquals("api:/api/order/create", UrlNormalizer.nodeKey("/api/order/create"));
        // 对照：真 hash 必须被替换
        assertEquals("api:/api/file/{hash}", UrlNormalizer.nodeKey("/api/file/a1b2c3d4e5f6a7b8"));
        // 短 hex 不认定为 hash（保守）
        assertNotEquals("api:/api/x/{hash}", UrlNormalizer.nodeKey("/api/x/deadbeef"));
    }

    // ══ 清单 #3: WHERE id=1 与 WHERE id=2 → 同一 node_key ════════════
    @Test
    public void c3_sqlLiteralsNormalized() {
        String a = SqlNormalizer.nodeKey("SELECT * FROM orders WHERE id = 1");
        String b = SqlNormalizer.nodeKey("SELECT * FROM orders WHERE id = 2");
        assertEquals(a, b);
        assertEquals("SELECT * FROM ORDERS WHERE ID = ?",
                SqlNormalizer.fingerprint("SELECT * FROM orders WHERE id = 1"));
    }

    // ══ 清单 #4: FROM orders_2024 t1 → 表名与别名未被替换 ═════════════
    @Test
    public void c4_identifiersContainingDigitsPreserved() {
        String fp = SqlNormalizer.fingerprint("SELECT * FROM orders_2024 t1 WHERE id = 5");
        assertTrue("表名被破坏了: " + fp, fp.contains("ORDERS_2024"));
        assertTrue("别名被破坏了: " + fp, fp.contains("T1"));
        assertFalse("标识符内数字被误替换: " + fp, fp.contains("ORDERS_?"));
        // ★ 最严重的后果：不同表被合并成同一节点
        assertNotEquals(
                SqlNormalizer.nodeKey("SELECT * FROM orders_2024 WHERE id = 1"),
                SqlNormalizer.nodeKey("SELECT * FROM orders_2025 WHERE id = 1"));
        // utf8mb4 这类也是标识符
        assertTrue(SqlNormalizer.fingerprint("SELECT a FROM t WHERE b = 1")
                .contains("FROM"));
    }

    // ══ 清单 #5: IN (1,2,3) 与 IN (4,5) → 同一 node_key ═════════════
    @Test
    public void c5_inListCollapsed() {
        assertEquals(SqlNormalizer.nodeKey("SELECT * FROM orders WHERE id IN (1,2,3)"),
                SqlNormalizer.nodeKey("SELECT * FROM orders WHERE id IN (4,5)"));
        assertTrue(SqlNormalizer.fingerprint("SELECT * FROM orders WHERE id IN (1,2,3)")
                .contains("IN (?)"));
    }

    // ══ 清单 #6: user:1:profile 与 user:2:profile → 同一 node_key ════
    @Test
    public void c6_redisKeyNormalized() {
        assertEquals(RedisKeyNormalizer.nodeKey("GET", "user:1:profile"),
                RedisKeyNormalizer.nodeKey("GET", "user:2:profile"));
        assertEquals("redis:GET user:{id}:profile",
                RedisKeyNormalizer.nodeKey("GET", "user:1:profile"));
        // 前缀段必须保留（才是语义）
        assertNotEquals(RedisKeyNormalizer.nodeKey("GET", "user:1:profile"),
                RedisKeyNormalizer.nodeKey("GET", "token:1:profile"));
        assertEquals("GET user:*:profile",
                RedisKeyNormalizer.nodeLabel("GET", "user:1:profile"));
    }

    // ══ 清单 #7: selector 不含 nth-child ════════════════════════════
    @Test
    public void c7_selectorNoNthChild() {
        String key = SelectorNormalizer.nodeKey("div.list > button:nth-child(3).save-btn");
        assertFalse("含 nth-child: " + key, key.contains("nth-child"));
        assertTrue(key.startsWith("action:"));
        // 同一按钮在不同位置必须同节点
        assertEquals(SelectorNormalizer.nodeKey("button#save-btn:nth-child(1)"),
                SelectorNormalizer.nodeKey("button#save-btn"));
    }

    // ══ 额外的边界：字符串 / 注释 / 不误并 ═══════════════════════════

    @Test
    public void stringLiterals() {
        assertEquals(SqlNormalizer.nodeKey("SELECT * FROM t WHERE name = 'a'"),
                SqlNormalizer.nodeKey("SELECT * FROM t WHERE name = 'b'"));
        // '' 双写转义不能提前结束字符串
        String fp = SqlNormalizer.fingerprint("SELECT * FROM t WHERE name = 'it''s' AND id = 9");
        assertEquals("SELECT * FROM T WHERE NAME = ? AND ID = ?", fp);
        // 反斜杠转义
        assertEquals("SELECT * FROM T WHERE NAME = ?",
                SqlNormalizer.fingerprint("SELECT * FROM t WHERE name = 'a\\'b'"));
    }

    @Test
    public void commentsRemoved() {
        assertEquals(SqlNormalizer.nodeKey("SELECT * FROM orders WHERE id = 1"),
                SqlNormalizer.nodeKey("SELECT * /* 备注 */ FROM orders -- 尾部注释\n WHERE id = 1"));
        assertEquals("SELECT * FROM ORDERS WHERE ID = ?",
                SqlNormalizer.fingerprint("SELECT * FROM orders # mysql 注释\n WHERE id = 1"));
    }

    @Test
    public void distinctQueriesMustNotMerge() {
        // ★ 不同表 / 不同列 决不能被合并，否则图失去意义
        assertNotEquals(SqlNormalizer.nodeKey("SELECT * FROM orders WHERE id = 1"),
                SqlNormalizer.nodeKey("SELECT * FROM customers WHERE id = 1"));
        assertNotEquals(SqlNormalizer.nodeKey("SELECT * FROM orders WHERE id = 1"),
                SqlNormalizer.nodeKey("SELECT * FROM orders WHERE no = 1"));
        assertNotEquals(SqlNormalizer.nodeKey("UPDATE orders SET status = 'PAID' WHERE id = 1"),
                SqlNormalizer.nodeKey("DELETE FROM orders WHERE id = 1"));
    }

    @Test
    public void nodeLabelReadable() {
        assertEquals("SELECT demo_order",
                SqlNormalizer.nodeLabel("select * from demo_order where id = 1"));
        assertEquals("UPDATE demo_order",
                SqlNormalizer.nodeLabel("/* c */ update demo_order set status = 'X' where id = 1"));
        assertEquals("GET /api/order/{id}", UrlNormalizer.nodeLabel("get", "/api/order/1?x=2"));
    }

    // ══ 脱敏 ════════════════════════════════════════════════════════

    @Test
    public void masker() {
        // 字段名黑名单
        assertEquals("{\"password\":\"***\"}", Masker.mask("{\"password\":\"P@ssw0rd!\"}"));
        assertEquals("{\"accessToken\":\"***\"}", Masker.mask("{\"accessToken\":\"eyJhbGci\"}"));
        // 值特征掩码
        assertEquals("138****8888", Masker.mask("13812348888"));
        assertEquals("z***@example.com", Masker.mask("zhangsan@example.com"));
        assertEquals("110101********1234", Masker.mask("110101199001011234"));
        // 普通值不动
        assertEquals("hello world", Masker.mask("hello world"));
        assertNull(Masker.mask(null));
    }

    /**
     * ★ 回归用例：手机号正则曾在身份证内部误匹配（2026-09-22 由本测试捕获）。
     * 边界守卫 (?<!\d)/(?!\d) + 先身份证后手机号，两个修改都靠这几条锁住。
     */
    @Test
    public void maskerNestedDigitsRegression() {
        assertEquals("110101********1234", Masker.mask("110101199001011234"));
        // 长数字串内部不得被当成手机号
        assertEquals("123138123488889", Masker.mask("123138123488889"));
        // 前后有分隔符时仍要正常识别
        assertEquals("tel:138****8888,", Masker.mask("tel:13812348888,"));
    }

    @Test
    public void maskerDoesNotBreakOnWeirdInput() {
        assertEquals("", Masker.mask(""));
        // 短数字不能被当成手机号
        assertEquals("order 12345", Masker.mask("order 12345"));
    }

    // ══ 打印全部结果，供人工核对 ════════════════════════════════════

    @Test
    public void dump() {
        System.out.println("\n══════ 归一化结果一览（人工核对）══════");
        String[] urls = {"/api/order/list", "/api/order/123", "/api/order/456/detail",
                "/api/file/a1b2c3d4e5f6a7b8", "/api/order/list?page=1&size=10"};
        for (String u : urls) {
            System.out.println("URL   " + u + "  →  " + UrlNormalizer.nodeKey(u));
        }
        String[] sqls = {
                "SELECT id,order_no FROM demo_order ORDER BY id",
                "SELECT * FROM demo_order WHERE id = 1",
                "UPDATE demo_order SET status = 'PAID' WHERE id = 1",
                "SELECT * FROM orders_2024 t1 WHERE user_id IN (1,2,3)"
        };
        for (String s : sqls) {
            System.out.println("SQL   " + s
                    + "\n      fp=" + SqlNormalizer.fingerprint(s)
                    + "\n      key=" + SqlNormalizer.nodeKey(s)
                    + "  label=" + SqlNormalizer.nodeLabel(s));
        }
        System.out.println("REDIS GET user:1:profile → " + RedisKeyNormalizer.nodeKey("GET", "user:1:profile"));
        System.out.println("══════════════════════════════════════\n");
    }
}
