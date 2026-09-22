package cn.observe.starter.store;

import cn.observe.core.model.ObserveEvent;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 事件落库。append-only，只 INSERT（SCHEMA.md 冻结：单表 observe_event）。
 */
public class EventWriter {

    private static final String INSERT_SQL =
            "INSERT INTO observe_event "
                    + "(event_id, trace_id, session_id, step_no, layer, event_type, node_key, node_label,"
                    + " parent_key, edge_type, occurred_at, duration_ms, status, error_msg,"
                    + " tenant_id, user_id, app_name, raw_payload) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbc;

    public EventWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 批量写入。单条失败不影响其余（用 batchUpdate 的容忍模式） */
    @SuppressWarnings("unchecked")
    public void write(List<ObserveEvent> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<Object[]>(batch.size());
        for (ObserveEvent e : batch) {
            rows.add(new Object[]{
                    e.getEventId(),
                    e.getTraceId(),
                    e.getSessionId(),
                    e.getStepNo(),
                    e.getLayer() == null ? null : e.getLayer().name(),
                    e.getEventType() == null ? null : e.getEventType().name(),
                    e.getNodeKey(),
                    trim(e.getNodeLabel(), 255),
                    e.getParentKey(),
                    e.getEdgeType() == null ? null : e.getEdgeType().name(),
                    e.getOccurredAt() == null ? new Timestamp(System.currentTimeMillis()) : new Timestamp(e.getOccurredAt().getTime()),
                    e.getDurationMs(),
                    e.getStatus() == null ? "OK" : e.getStatus(),
                    trim(e.getErrorMsg(), 512),
                    e.getTenantId(),
                    e.getUserId(),
                    e.getAppName(),
                    e.getRawPayload()
            });
        }
        jdbc.batchUpdate(INSERT_SQL, rows);
    }

    /** 查询最近有事件的会话（供观察页选择） */
    public List<Map<String, Object>> recentSessions(int limit) {
        return jdbc.queryForList(
                "SELECT session_id,"
                        + "       COUNT(*)                                     AS event_count,"
                        + "       COUNT(DISTINCT node_key)                     AS node_count,"
                        + "       COUNT(DISTINCT trace_id)                     AS call_count,"
                        + "       MIN(occurred_at)                             AS started_at,"
                        + "       MAX(occurred_at)                             AS ended_at"
                        + "  FROM observe_event"
                        + " GROUP BY session_id"
                        + " ORDER BY MAX(occurred_at) DESC"
                        + " LIMIT " + Math.max(1, Math.min(limit, 100)));
    }

    /** 取整个会话的事件，按 step_no + 时间排序（构图输入） */
    public List<Map<String, Object>> eventsOfSession(String sessionId, int limit) {
        return jdbc.queryForList(
                "SELECT id, event_id, trace_id, session_id, step_no, layer, event_type,"
                        + "       node_key, node_label, parent_key, edge_type, occurred_at,"
                        + "       duration_ms, status, error_msg, app_name, raw_payload"
                        + "  FROM observe_event"
                        + " WHERE session_id = ?"
                        + " ORDER BY step_no ASC, occurred_at ASC, id ASC"
                        + " LIMIT " + Math.max(1, Math.min(limit, 20000)),
                sessionId);
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
