package cn.observe.core.model;

import java.util.Date;

/**
 * 观测事件。字段与 observe_event 表一一对应（见 specs/SCHEMA.md）。
 *
 * <p>纯 POJO，无依赖。用 {@link #builder()} 构造。
 */
public class ObserveEvent {

    private String eventId;
    /** ★三层缝合线 */
    private String traceId;
    private String sessionId;
    private int stepNo;

    private Layer layer;
    private EventType eventType;
    /** ★归一化指纹 = 图节点 ID */
    private String nodeKey;
    private String nodeLabel;

    /** 父节点指纹，用于建边 */
    private String parentKey;
    private EdgeType edgeType;

    private Date occurredAt;
    private Integer durationMs;

    private String status = "OK";
    private String errorMsg;

    private Long tenantId;
    private Long userId;
    private String appName;

    /** ★原始值（已脱敏），用于排查 */
    private String rawPayload;

    public ObserveEvent() {
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── getters / setters ────────────────────────────────────────

    public String getEventId() { return eventId; }
    public void setEventId(String v) { this.eventId = v; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String v) { this.traceId = v; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }

    public int getStepNo() { return stepNo; }
    public void setStepNo(int v) { this.stepNo = v; }

    public Layer getLayer() { return layer; }
    public void setLayer(Layer v) { this.layer = v; }

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType v) { this.eventType = v; }

    public String getNodeKey() { return nodeKey; }
    public void setNodeKey(String v) { this.nodeKey = v; }

    public String getNodeLabel() { return nodeLabel; }
    public void setNodeLabel(String v) { this.nodeLabel = v; }

    public String getParentKey() { return parentKey; }
    public void setParentKey(String v) { this.parentKey = v; }

    public EdgeType getEdgeType() { return edgeType; }
    public void setEdgeType(EdgeType v) { this.edgeType = v; }

    public Date getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Date v) { this.occurredAt = v; }

    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer v) { this.durationMs = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String v) { this.errorMsg = v; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { this.tenantId = v; }

    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }

    public String getAppName() { return appName; }
    public void setAppName(String v) { this.appName = v; }

    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String v) { this.rawPayload = v; }

    @Override
    public String toString() {
        return "ObserveEvent{" + layer + "/" + eventType + " " + nodeKey
                + (edgeType != null ? " -" + edgeType + "->" : "")
                + " trace=" + traceId + " step=" + stepNo + "}";
    }

    // ── builder ─────────────────────────────────────────────────

    public static class Builder {
        private final ObserveEvent e = new ObserveEvent();

        public Builder eventId(String v) { e.eventId = v; return this; }
        public Builder traceId(String v) { e.traceId = v; return this; }
        public Builder sessionId(String v) { e.sessionId = v; return this; }
        public Builder stepNo(int v) { e.stepNo = v; return this; }
        public Builder layer(Layer v) { e.layer = v; return this; }
        public Builder eventType(EventType v) { e.eventType = v; return this; }
        public Builder nodeKey(String v) { e.nodeKey = v; return this; }
        public Builder nodeLabel(String v) { e.nodeLabel = v; return this; }
        public Builder parentKey(String v) { e.parentKey = v; return this; }
        public Builder edgeType(EdgeType v) { e.edgeType = v; return this; }
        public Builder occurredAt(Date v) { e.occurredAt = v; return this; }
        public Builder durationMs(Integer v) { e.durationMs = v; return this; }
        public Builder status(String v) { e.status = v; return this; }
        public Builder errorMsg(String v) { e.errorMsg = v; return this; }
        public Builder tenantId(Long v) { e.tenantId = v; return this; }
        public Builder userId(Long v) { e.userId = v; return this; }
        public Builder appName(String v) { e.appName = v; return this; }
        public Builder rawPayload(String v) { e.rawPayload = v; return this; }

        public ObserveEvent build() { return e; }
    }
}
