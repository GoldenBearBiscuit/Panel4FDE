package cn.observe.starter.api;

import cn.observe.starter.graph.GraphBuilder;
import cn.observe.starter.store.EventQueue;
import cn.observe.starter.store.EventWriter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构图与查询接口。前端观察页消费这些接口渲染图。
 */
@RestController
public class ObserveGraphController {

    private final EventWriter writer;
    private final EventQueue queue;

    public ObserveGraphController(EventWriter writer, EventQueue queue) {
        this.writer = writer;
        this.queue = queue;
    }

    /** 最近有事件的会话列表（供观察页选择） */
    @GetMapping("/observe/sessions")
    public List<Map<String, Object>> sessions(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        return writer.recentSessions(limit);
    }

    /**
     * 会话的图。不传 sessionId 时取最近一个会话。
     */
    @GetMapping("/observe/graph")
    public Map<String, Object> graph(@RequestParam(value = "sessionId", required = false) String sessionId) {
        String target = sessionId;
        if (target == null || target.isEmpty()) {
            List<Map<String, Object>> list = writer.recentSessions(1);
            if (list.isEmpty()) {
                Map<String, Object> empty = new LinkedHashMap<String, Object>();
                empty.put("sessionId", null);
                empty.put("eventCount", 0);
                empty.put("nodeCount", 0);
                empty.put("edgeCount", 0);
                empty.put("nodes", java.util.Collections.emptyList());
                empty.put("edges", java.util.Collections.emptyList());
                return empty;
            }
            target = String.valueOf(list.get(0).get("session_id"));
        }
        List<Map<String, Object>> rows = writer.eventsOfSession(target, 20000);
        return GraphBuilder.build(target, rows);
    }

    /**
     * 实时事件流接口（供观察页左栏用）。
     *
     * @param afterId 游标：只返回 id 大于它的新事件。首次传 0
     * @return {sessionId, lastId, events}；下次请求把 lastId 传回 afterId 即可增量拉取
     */
    @GetMapping("/observe/events")
    public Map<String, Object> events(@RequestParam(value = "sessionId", required = false) String sessionId,
                                      @RequestParam(value = "afterId", defaultValue = "0") long afterId,
                                      @RequestParam(value = "limit", defaultValue = "200") int limit) {
        String target = sessionId;
        if (target == null || target.isEmpty()) {
            List<Map<String, Object>> list = writer.recentSessions(1);
            target = list.isEmpty() ? null : String.valueOf(list.get(0).get("session_id"));
        }

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("sessionId", target);
        if (target == null) {
            m.put("lastId", afterId);
            m.put("events", java.util.Collections.emptyList());
            return m;
        }

        List<Map<String, Object>> rows = writer.eventsAfter(target, afterId, limit);
        long lastId = afterId;
        for (Map<String, Object> r : rows) {
            Object id = r.get("id");
            if (id instanceof Number) {
                lastId = Math.max(lastId, ((Number) id).longValue());
            }
        }
        m.put("lastId", lastId);
        m.put("events", rows);
        return m;
    }

    /** 采集自身健康度：★ 队列丢弃数是最重要的运维指标（丢弃意味着数据缺口） */
    @GetMapping("/observe/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("received", queue.getReceived());
        m.put("written", queue.getWritten());
        m.put("dropped", queue.getDropped());
        m.put("writeErrors", queue.getWriteErrors());
        m.put("pending", queue.getPending());
        return m;
    }
}
