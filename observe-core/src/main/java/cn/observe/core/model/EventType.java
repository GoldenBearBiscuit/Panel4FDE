package cn.observe.core.model;

/**
 * 事件类型。与 observe_event.event_type 列的取值一一对应。
 */
public enum EventType {

    // ── 前端 ──
    PAGE_VIEW,
    CLICK,
    INPUT,
    SUBMIT,

    // ── 后端 ──
    API,

    // ── 资源层 ──
    SQL,
    REDIS,
    /** 阶段一仅预留，不引 broker */
    MQ
}
