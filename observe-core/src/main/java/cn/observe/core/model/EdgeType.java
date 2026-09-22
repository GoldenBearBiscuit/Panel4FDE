package cn.observe.core.model;

/**
 * 边类型。
 *
 * <p>注意 {@link #PRECEDES} 不由采集器产生，而是在构图时按 (step_no, occurred_at) 顺序
 * 在相邻的不同节点之间补齐。其余三种由采集器写在事件的 edge_type 列上。
 */
public enum EdgeType {

    /** page → page，前端路由切换 */
    NAVIGATE,

    /** action → api，前端发起的请求 */
    TRIGGER,

    /** api → sql / api → redis，后端资源层调用 */
    CALL,

    /** 同会话相邻节点，构图时兜底补 */
    PRECEDES
}
