package cn.observe.core.model;

/**
 * 事件所属层。三层是全系统的骨架。
 */
public enum Layer {

    /** 前端：页面、点击、输入、请求发起 */
    FRONTEND,

    /** 后端：接口执行 */
    BACKEND,

    /** 资源层：SQL / Redis / MQ */
    RESOURCE
}
