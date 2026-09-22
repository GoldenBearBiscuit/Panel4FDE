package cn.observe.starter.api;

import cn.observe.core.model.EdgeType;
import cn.observe.core.model.EventType;
import cn.observe.core.model.Layer;
import cn.observe.core.model.ObserveEvent;
import cn.observe.core.normalize.NodeKeys;
import cn.observe.core.mask.Masker;
import cn.observe.core.util.Json;
import cn.observe.starter.ObserveProperties;
import cn.observe.starter.store.EventQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * L1 事件的接收端。
 *
 * <p>★ 前端只上报「原始值」（path / selector / method），归一化一律在这里做。
 * 这样前后端不可能生成不一致的 node_key —— SCHEMA.md 的 API 事件去重规则才成立。
 */
@RestController
public class ObserveReportController {

    private static final Logger log = LoggerFactory.getLogger(ObserveReportController.class);

    private final EventQueue queue;
    private final ObserveProperties props;
    private final String appName;

    public ObserveReportController(EventQueue queue, ObserveProperties props, String appName) {
        this.queue = queue;
        this.props = props;
        this.appName = appName;
    }

    @GetMapping("/observe/ping")
    public Map<String, Object> ping() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("app", appName);
        return m;
    }

    @PostMapping("/observe/report")
    public Map<String, Object> report(@RequestBody ReportDto dto) {
        int accepted = 0;
        int rejected = 0;
        if (dto != null && dto.events != null) {
            for (FrontendEventDto fe : dto.events) {
                try {
                    ObserveEvent e = convert(dto.sessionId, fe);
                    if (e == null) {
                        rejected++;
                    } else {
                        queue.offer(e);
                        accepted++;
                    }
                } catch (Throwable t) {
                    rejected++;
                    log.debug("[observe] 前端事件转换失败: {}", t.toString());
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("accepted", accepted);
        m.put("rejected", rejected);
        return m;
    }

    private ObserveEvent convert(String sessionId, FrontendEventDto fe) {
        if (fe == null || fe.type == null) {
            return null;
        }
        EventType type;
        try {
            type = EventType.valueOf(fe.type.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }

        // ★ 按类型决定 node_key 的来源
        String nodeKey;
        String nodeLabel = fe.label;
        if (type == EventType.PAGE_VIEW) {
            if (isBlank(fe.path)) {
                return null;
            }
            nodeKey = NodeKeys.page(fe.path);
        } else if (type == EventType.API) {
            if (isBlank(fe.path)) {
                return null;
            }
            nodeKey = NodeKeys.api(fe.path);
        } else {
            if (isBlank(fe.selector)) {
                return null;
            }
            nodeKey = NodeKeys.action(fe.selector);
        }

        // ★ parent 由 kind + 原始值 在服务端归一化 → 与后端生成的值必然一致
        String parentKey = NodeKeys.of(fe.parentKind, fe.parentValue);
        EdgeType edgeType = deriveEdge(type, parentKey);
        if (parentKey != null && parentKey.equals(nodeKey)) {
            parentKey = null;
            edgeType = null;
        }

        Date occurred = fe.occurredAt == null ? new Date() : new Date(fe.occurredAt);

        return ObserveEvent.builder()
                .eventId(isBlank(fe.eventId) ? UUID.randomUUID().toString() : fe.eventId)
                .traceId(fe.traceId)
                .sessionId(isBlank(sessionId) ? "-" : sessionId)
                .stepNo(fe.stepNo == null ? 0 : fe.stepNo)
                .layer(Layer.FRONTEND)
                .eventType(type)
                .nodeKey(nodeKey)
                .nodeLabel(Masker.truncate(nodeLabel, 255))
                .parentKey(parentKey)
                .edgeType(edgeType)
                .occurredAt(occurred)
                .durationMs(fe.durationMs)
                .status(isBlank(fe.status) ? "OK" : fe.status)
                .errorMsg(Masker.truncate(fe.errorMsg, 500))
                .tenantId(fe.tenantId)
                .userId(fe.userId)
                .appName(appName)
                .rawPayload(buildRaw(fe))
                .build();
    }

    /**
     * ★ 边语义完全由「子事件类型 + parent 节点类型」推导，不需要新增字段。
     *
     * <pre>
     *  PAGE_VIEW ← page    → NAVIGATE   页面跳转
     *  PAGE_VIEW ← action  → TRIGGER    人点了 link/按钮导致的跳转
     *  API       ← action  → TRIGGER    ★「人干的」
     *  API       ← page    → AUTO       ★「页面自动干的」（挂载拉数据、轮询、预加载）
     *  其余                → PRECEDES   时序兜底
     * </pre>
     */
    static EdgeType deriveEdge(EventType type, String parentKey) {
        if (parentKey == null) {
            return null;
        }
        boolean fromAction = parentKey.startsWith("action:");
        boolean fromPage = parentKey.startsWith("page:");
        if (type == EventType.PAGE_VIEW) {
            if (fromPage) {
                return EdgeType.NAVIGATE;
            }
            return fromAction ? EdgeType.TRIGGER : EdgeType.PRECEDES;
        }
        if (type == EventType.API) {
            if (fromAction) {
                return EdgeType.TRIGGER;
            }
            return fromPage ? EdgeType.AUTO : EdgeType.PRECEDES;
        }
        return EdgeType.PRECEDES;
    }

    private String buildRaw(FrontendEventDto fe) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        first = append(sb, first, "method", fe.method);
        first = append(sb, first, "path", fe.path);
        first = append(sb, first, "selector", fe.selector);
        first = append(sb, first, "page", fe.page);
        if (!first) {
            sb.append(',');
        }
        sb.append("\"extra\":").append(isBlank(fe.raw) ? "null" : safeJson(fe.raw));
        sb.append('}');
        return Masker.truncate(Masker.mask(sb.toString()), props.getRawPayloadMaxLength());
    }

    private static boolean append(StringBuilder sb, boolean first, String key, String value) {
        if (isBlank(value)) {
            return first;
        }
        if (!first) {
            sb.append(',');
        }
        sb.append(Json.str(key)).append(':').append(Json.str(Masker.mask(value)));
        return false;
    }

    /** 前端的 raw 可能是任意 JSON；不合法就退化成字符串，绝不让入库失败 */
    private static String safeJson(String s) {
        String t = s.trim();
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
            return t;
        }
        return Json.str(s);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ── DTO ───────────────────────────────────────────────────────

    public static class ReportDto {
        public String sessionId;
        public List<FrontendEventDto> events = new ArrayList<FrontendEventDto>();
    }

    public static class FrontendEventDto {
        public String eventId;
        /** PAGE_VIEW | CLICK | INPUT | SUBMIT | API */
        public String type;
        public Integer stepNo;
        public Long occurredAt;
        public String traceId;
        public String label;
        public String status;
        public String errorMsg;
        public Integer durationMs;
        public Long tenantId;
        public Long userId;

        /** parent 的原始值，服务端按 parentKind 选对应归一化器 */
        public String parentKind;
        public String parentValue;

        // 按 type 二选一
        public String path;
        public String selector;
        public String method;

        /** 附加信息，JSON 字符串 */
        public String raw;
        public String page;
    }
}
