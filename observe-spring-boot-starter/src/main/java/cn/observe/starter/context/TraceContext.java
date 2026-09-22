package cn.observe.starter.context;

/**
 * 采集上下文。与 MDC 同步维护，供采集器读取。
 *
 * <p>★ 与 yudao 的 {@code TenantContextHolder}（TransmittableThreadLocal）是两套独立机制：
 * 一个传 traceId，一个传 tenantId，互不冲突但也不会自动会合。
 * 会合点就是 {@link #setTenantId} —— 由 TraceFilter 在业务线程上显式搬运。
 * <b>绝不能在 SpanProcessor / 异步导出线程里读租户</b>（见 SCHEMA.md 的致命陷阱一节）。
 */
public final class TraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<String>();
    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<String>();
    private static final ThreadLocal<Integer> STEP_NO = new ThreadLocal<Integer>();
    /** 当前 API 的 node_key，作为其 SQL/Redis 事件的 parent_key */
    private static final ThreadLocal<String> CURRENT_NODE = new ThreadLocal<String>();
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<Long>();
    private static final ThreadLocal<Long> TENANT_ID = new ThreadLocal<Long>();
    /** ★ 抑制采集：采集器自身写库时置位，避免无限递归 */
    private static final ThreadLocal<Boolean> SUPPRESS = new ThreadLocal<Boolean>();

    private TraceContext() {
    }

    public static String getTraceId() { return TRACE_ID.get(); }
    public static void setTraceId(String v) { TRACE_ID.set(v); }

    public static String getSessionId() { return SESSION_ID.get(); }
    public static void setSessionId(String v) { SESSION_ID.set(v); }

    public static int getStepNo() {
        Integer v = STEP_NO.get();
        return v == null ? 0 : v;
    }
    public static void setStepNo(Integer v) { STEP_NO.set(v); }

    public static String getCurrentNode() { return CURRENT_NODE.get(); }
    public static void setCurrentNode(String v) { CURRENT_NODE.set(v); }

    public static Long getUserId() { return USER_ID.get(); }
    public static void setUserId(Long v) { USER_ID.set(v); }

    public static Long getTenantId() { return TENANT_ID.get(); }
    public static void setTenantId(Long v) { TENANT_ID.set(v); }

    public static boolean isSuppressed() { return Boolean.TRUE.equals(SUPPRESS.get()); }
    public static void setSuppress(boolean v) { SUPPRESS.set(v); }

    /** ★ 必须在请求收尾的 finally 里调用，否则线程池复用会串数据 */
    public static void clear() {
        TRACE_ID.remove();
        SESSION_ID.remove();
        STEP_NO.remove();
        CURRENT_NODE.remove();
        USER_ID.remove();
        TENANT_ID.remove();
        SUPPRESS.remove();
    }
}
