package cn.observe.core.model;

/**
 * 边类型。
 *
 * <p>注意 {@link #PRECEDES} 不由采集器产生，而是在构图时按 (step_no, occurred_at) 顺序
 * 在相邻的不同节点之间补齐。其余三种由采集器写在事件的 edge_type 列上。
 */
public enum EdgeType {

    /**
     * action → api / action → page。★「人干的」：由用户交互引发。
     * 模型要学的是这一类——不区分人与自动，模型学到的就是噪声。
     */
    TRIGGER,

    /**
     * page → api。★「页面自动干的」：组件挂载、轮询、预加载等，无人干预。
     * 与 {@link #TRIGGER} 严格区分，区分的依据是 parent 节点的类型，不需要额外字段。
     */
    AUTO,

    /** page → page，前端路由切换 */
    NAVIGATE,

    /** api → sql / api → redis，后端资源层调用 */
    CALL,

    /** 同会话相邻节点，构图时兜底补 */
    PRECEDES
}
