package cn.observe.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * demo 业务：订单列表 / 详情 / 保存。
 *
 * <p>★ 这个类里<b>没有一行观测代码</b> —— 没有埋点、没有注解、没有 tracing API。
 * 三层事件全部由 observe-kit 通过字节码/动态代理层面自动采集。
 * 这是「最高层目标项目零侵入」承诺的实证。
 *
 * <p>三个接口刻意覆盖了资源层的不同类型：
 * <ul>
 *   <li>{@code GET /api/order/list}  → SQL SELECT + Redis GET/SET</li>
 *   <li>{@code GET /api/order/{id}}  → SQL SELECT + Redis GET</li>
 *   <li>{@code POST /api/order/{id}/save} → SQL UPDATE + Redis DEL</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/order")
public class DemoOrderController {

    private static final Logger log = LoggerFactory.getLogger(DemoOrderController.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public DemoOrderController(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(value = "page", defaultValue = "1") int page) {
        String cacheKey = "order:list:page:" + page;
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                log.info("列表命中缓存 {}", cacheKey);
            }
        } catch (Exception e) {
            // ★ Redis 不可用不影响业务：这是阶段一验收清单最后一条的前提
            log.warn("Redis 不可用，降级直查数据库: {}", e.getMessage());
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, order_no, customer, amount, status FROM demo_order ORDER BY id LIMIT 50");

        try {
            redis.opsForValue().set(cacheKey, String.valueOf(rows.size()), 60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis 写入失败: {}", e.getMessage());
        }
        return rows;
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable("id") Long id) {
        String cacheKey = "order:detail:" + id;
        try {
            redis.opsForValue().get(cacheKey);
        } catch (Exception e) {
            log.warn("Redis 不可用: {}", e.getMessage());
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, order_no, customer, amount, status FROM demo_order WHERE id = ?", id);
        Map<String, Object> result = rows.isEmpty() ? new LinkedHashMap<String, Object>() : rows.get(0);

        try {
            redis.opsForValue().set(cacheKey, String.valueOf(id), 60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis 写入失败: {}", e.getMessage());
        }
        return result;
    }

    @PostMapping("/{id}/save")
    public Map<String, Object> save(@PathVariable("id") Long id,
                                    @RequestBody Map<String, Object> body) {
        String customer = body.get("customer") == null ? null : String.valueOf(body.get("customer"));
        String status = body.get("status") == null ? null : String.valueOf(body.get("status"));

        int updated = jdbc.update(
                "UPDATE demo_order SET customer = ?, status = ? WHERE id = ?",
                customer, status, id);

        try {
            redis.delete("order:detail:" + id);
            redis.delete("order:list:page:1");
            redis.opsForValue().set("order:saved:" + id, status == null ? "" : status, 60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis 不可用: {}", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("updated", updated);
        result.put("id", id);
        return result;
    }

    /** 故意抛错，用于验证 ERROR 状态与异常路径的采集 */
    @GetMapping("/boom")
    public List<Map<String, Object>> boom() {
        jdbc.queryForList("SELECT not_exist_column FROM demo_order WHERE id = 1");
        return new ArrayList<Map<String, Object>>();
    }
}
