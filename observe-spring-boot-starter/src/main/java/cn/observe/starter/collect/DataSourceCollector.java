package cn.observe.starter.collect;

import cn.observe.core.mask.Masker;
import cn.observe.core.model.EdgeType;
import cn.observe.core.model.EventType;
import cn.observe.core.model.Layer;
import cn.observe.core.model.ObserveEvent;
import cn.observe.core.normalize.NodeKeys;
import cn.observe.core.normalize.SqlNormalizer;
import cn.observe.core.util.Json;
import cn.observe.core.util.Proxies;
import cn.observe.starter.ObserveProperties;
import cn.observe.starter.context.TraceContext;
import cn.observe.starter.store.EventQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * L6 资源层采集之一：SQL。
 *
 * <p>★ 无侵入原理：在 {@link DataSource} 上套 JDK 动态代理，逐层代理到
 * {@link Connection} → {@link PreparedStatement}，拦 {@code execute*} 拿到
 * 真实 SQL + 绑定参数 + 耗时。业务代码一行不动，也不依赖 MyBatis/JdbcTemplate 具体实现。
 *
 * <p>不用 datasource-proxy / p6spy 的理由：本方案只需「SQL + 参数 + 耗时」三样，
 * 自写约 100 行即可，且能精确控制「跳过 observe 自身写入」的递归守卫。
 */
public class DataSourceCollector implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DataSourceCollector.class);
    private static final String OBSERVE_TABLE = "observe_event";

    private final org.springframework.beans.factory.ObjectProvider<EventQueue> queueProvider;
    private final ObserveProperties props;
    private final String appName;
    private volatile EventQueue queueRef;

    public DataSourceCollector(org.springframework.beans.factory.ObjectProvider<EventQueue> queueProvider,
                              ObserveProperties props, String appName) {
        this.queueProvider = queueProvider;
        this.props = props;
        this.appName = appName;
    }

    /**
     * ★ 惰性解析 EventQueue。
     *
     * <p>这是本类最关键的细节：BeanPostProcessor 在 registerBeanPostProcessors 阶段就被实例化。
     * 若在构造时就要求 EventQueue → EventWriter → JdbcTemplate → DataSource 链式创建，
     * DataSource 会<b>在本 BPP 注册之前</b>就被创建 → 永远包不上 → <b>SQL 采集静默失效且无任何报错</b>。
     * 所以必须用 ObjectProvider 延后到首次真正需要时才解析。
     */
    private EventQueue queue() {
        EventQueue q = queueRef;
        if (q == null) {
            q = queueProvider.getObject();
            queueRef = q;
        }
        return q;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!props.isCollectSql() || !(bean instanceof DataSource)) {
            return bean;
        }
        if (Proxy.isProxyClass(bean.getClass())) {
            return bean; // 避免重复包装
        }
        try {
            Object wrapped = Proxies.wrap(bean, DataSource.class, new DataSourceHandler((DataSource) bean));
            log.info("[observe] SQL 采集已挂载: bean={} impl={}", beanName, bean.getClass().getSimpleName());
            return wrapped;
        } catch (Throwable t) {
            log.warn("[observe] DataSource 包装失败，SQL 采集关闭（不影响业务）: {}", t.toString());
            return bean;
        }
    }

    // ── 事件产出 ──────────────────────────────────────────────────

    private void recordSql(String sql, List<String> params, int ms, String status, String error) {
        try {
            if (TraceContext.isSuppressed() || sql == null || sql.isEmpty()) {
                return;
            }
            // 双保险：绝不采集 observe 自身的写入（即使 suppress 标记失效）
            if (sql.toLowerCase().contains(OBSERVE_TABLE)) {
                return;
            }
            String maskedSql = Masker.mask(sql);
            StringBuilder pl = new StringBuilder();
            pl.append('[');
            if (params != null) {
                for (int i = 0; i < params.size(); i++) {
                    if (i > 0) {
                        pl.append(',');
                    }
                    pl.append(Json.str(Masker.mask(params.get(i))));
                }
            }
            pl.append(']');
            String payload = Masker.truncate(
                    "{\"sql\":" + Json.str(maskedSql) + ",\"params\":" + pl + "}",
                    props.getRawPayloadMaxLength());

            ObserveEvent e = ObserveEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .traceId(TraceContext.getTraceId())
                    .sessionId(TraceContext.getSessionId() == null ? "-" : TraceContext.getSessionId())
                    .stepNo(TraceContext.getStepNo())
                    .layer(Layer.RESOURCE)
                    .eventType(EventType.SQL)
                    .nodeKey(NodeKeys.sql(sql))
                    .nodeLabel(SqlNormalizer.nodeLabel(sql))
                    .parentKey(TraceContext.getCurrentNode())
                    .edgeType(TraceContext.getCurrentNode() == null ? null : EdgeType.CALL)
                    .occurredAt(new Date())
                    .durationMs(ms)
                    .status(status)
                    .errorMsg(error == null ? null : Masker.truncate(error, 500))
                    .tenantId(TraceContext.getTenantId())
                    .userId(TraceContext.getUserId())
                    .appName(appName)
                    .rawPayload(payload)
                    .build();
            queue().offer(e);
        } catch (Throwable t) {
            // 采集永不影响业务
            log.debug("[observe] SQL 事件产出失败: {}", t.toString());
        }
    }

    // ── 代理层 ────────────────────────────────────────────────────

    private static Object call(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ite) {
            // ★ 必须剥掉 InvocationTargetException，否则真实异常会被包成 UndeclaredThrowableException
            throw ite.getCause() != null ? ite.getCause() : ite;
        }
    }

    private final class DataSourceHandler implements InvocationHandler {
        private final DataSource target;

        private DataSourceHandler(DataSource target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (TraceContext.isSuppressed()) {
                return call(method, target, args);
            }
            if ("getConnection".equals(method.getName())) {
                Object conn = call(method, target, args);
                if (conn instanceof Connection) {
                    return Proxies.wrap(conn, Connection.class, new ConnectionHandler((Connection) conn));
                }
                return conn;
            }
            return call(method, target, args);
        }
    }

    private final class ConnectionHandler implements InvocationHandler {
        private final Connection target;

        private ConnectionHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (TraceContext.isSuppressed()) {
                return call(method, target, args);
            }
            String name = method.getName();
            if ("prepareStatement".equals(name) || "prepareCall".equals(name)) {
                Object ps = call(method, target, args);
                String sql = (args != null && args.length > 0 && args[0] instanceof String)
                        ? (String) args[0] : null;
                if (ps instanceof PreparedStatement && sql != null) {
                    return Proxies.wrap(ps, PreparedStatement.class,
                            new StatementHandler((Statement) ps, sql));
                }
                return ps;
            }
            if ("createStatement".equals(name)) {
                Object st = call(method, target, args);
                if (st instanceof Statement) {
                    return Proxies.wrap(st, Statement.class, new StatementHandler((Statement) st, null));
                }
                return st;
            }
            return call(method, target, args);
        }
    }

    private final class StatementHandler implements InvocationHandler {
        private final Statement target;
        private String sql;
        private final boolean prepared;
        private final List<String> params = new ArrayList<String>();

        private StatementHandler(Statement target, String sql) {
            this.target = target;
            this.sql = sql;
            this.prepared = target instanceof PreparedStatement;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (TraceContext.isSuppressed()) {
                return call(method, target, args);
            }
            String name = method.getName();

            // 绑定参数（PreparedStatement.setXxx）
            if (prepared && name.startsWith("set") && args != null && args.length >= 2
                    && args[0] instanceof Integer) {
                int idx = (Integer) args[0];
                while (params.size() < idx) {
                    params.add(null);
                }
                params.set(idx - 1, stringify(args[1]));
                return call(method, target, args);
            }
            if (prepared && "clearParameters".equals(name)) {
                params.clear();
                return call(method, target, args);
            }

            if (name.startsWith("execute")) {
                if (sql == null && args != null && args.length > 0 && args[0] instanceof String) {
                    sql = (String) args[0];
                }
                long t0 = System.nanoTime();
                try {
                    Object r = call(method, target, args);
                    recordSql(sql, params, millis(t0), "OK", null);
                    return r;
                } catch (Throwable t) {
                    recordSql(sql, params, millis(t0), "ERROR", t.getMessage());
                    throw t;
                }
            }
            return call(method, target, args);
        }
    }

    private static int millis(long startNano) {
        return (int) ((System.nanoTime() - startNano) / 1000000L);
    }

    private static String stringify(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            return "<bytes:" + b.length + ">";
        }
        String s = String.valueOf(v);
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
