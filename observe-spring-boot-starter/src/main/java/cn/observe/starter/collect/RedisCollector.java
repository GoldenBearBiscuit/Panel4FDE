package cn.observe.starter.collect;

import cn.observe.core.mask.Masker;
import cn.observe.core.model.EdgeType;
import cn.observe.core.model.EventType;
import cn.observe.core.model.Layer;
import cn.observe.core.model.ObserveEvent;
import cn.observe.core.normalize.NodeKeys;
import cn.observe.core.normalize.RedisKeyNormalizer;
import cn.observe.core.util.Json;
import cn.observe.core.util.Proxies;
import cn.observe.starter.ObserveProperties;
import cn.observe.starter.context.TraceContext;
import cn.observe.starter.store.EventQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * L6 资源层采集之二：Redis。
 *
 * <p>★ 无侵入原理：{@link RedisConnection} 是<b>接口</b>，所以在
 * {@link RedisConnectionFactory#getConnection()} 返回值上套一层 JDK 动态代理，
 * 用一个类就能拦到<b>全部</b> Redis 命令（约 300 个方法），无需逐个适配。
 *
 * <p>已知缺口（记入 AGENT.md 修正清单，阶段二处理）：
 * {@code multi()} / {@code openPipeline()} 返回的连接对象不做二次包装，
 * 因此管道/pipeline 内的命令采集不到。
 */
public class RedisCollector implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RedisCollector.class);

    /** 非命令的元操作，直接放行不产出事件 */
    private static final Set<String> META = new HashSet<String>(Arrays.asList(
            "close", "isClosed", "getNativeConnection", "getConfig",
            "isQueueing", "isPipelined", "setTimeout", "getTimeout",
            "select", "ping", "multi", "exec", "discard", "watch", "unwatch",
            "openPipeline", "closePipeline", "getSentinelConnection",
            "subscribe", "pSubscribe", "getSubscription", "publish"
    ));

    private final org.springframework.beans.factory.ObjectProvider<EventQueue> queueProvider;
    private final ObserveProperties props;
    private final String appName;
    private volatile EventQueue queueRef;

    public RedisCollector(org.springframework.beans.factory.ObjectProvider<EventQueue> queueProvider,
                         ObserveProperties props, String appName) {
        this.queueProvider = queueProvider;
        this.props = props;
        this.appName = appName;
    }

    /**
     * ★ 惰性解析 EventQueue。同 {@code DataSourceCollector} 的理由：
     * 若在构造时就触发 EventQueue → JdbcTemplate → DataSource 链式创建，
     * RedisConnectionFactory 会先于本 BPP 注册被创建 → Redis 采集静默失效。
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
        if (!props.isCollectRedis() || !(bean instanceof RedisConnectionFactory)) {
            return bean;
        }
        if (Proxy.isProxyClass(bean.getClass())) {
            return bean;
        }
        try {
            Object wrapped = Proxies.wrap(bean, RedisConnectionFactory.class,
                    new FactoryHandler((RedisConnectionFactory) bean));
            log.info("[observe] Redis 采集已挂载: bean={} impl={}", beanName, bean.getClass().getSimpleName());
            return wrapped;
        } catch (Throwable t) {
            log.warn("[observe] RedisConnectionFactory 包装失败，Redis 采集关闭（不影响业务）: {}", t.toString());
            return bean;
        }
    }

    // ── 事件产出 ──────────────────────────────────────────────────

    private void record(String command, String key, int ms, String status, String error) {
        try {
            if (TraceContext.isSuppressed() || command == null) {
                return;
            }
            String payload = Masker.truncate(
                    "{\"command\":" + Json.str(command) + ",\"key\":" + Json.str(Masker.mask(key)) + "}",
                    props.getRawPayloadMaxLength());

            ObserveEvent e = ObserveEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .traceId(TraceContext.getTraceId())
                    .sessionId(TraceContext.getSessionId() == null ? "-" : TraceContext.getSessionId())
                    .stepNo(TraceContext.getStepNo())
                    .layer(Layer.RESOURCE)
                    .eventType(EventType.REDIS)
                    .nodeKey(NodeKeys.redis(command, key))
                    .nodeLabel(RedisKeyNormalizer.nodeLabel(command, key))
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
            log.debug("[observe] Redis 事件产出失败: {}", t.toString());
        }
    }

    // ── 代理层 ────────────────────────────────────────────────────

    private static Object call(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ite) {
            throw ite.getCause() != null ? ite.getCause() : ite;
        }
    }

    private static ClassLoader loaderFor(Object o) {
        return Proxies.loaderFor(o);
    }

    private final class FactoryHandler implements InvocationHandler {
        private final RedisConnectionFactory target;

        private FactoryHandler(RedisConnectionFactory target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = call(method, target, args);
            if (result instanceof RedisConnection) {
                return Proxies.wrap(result, RedisConnection.class,
                        new ConnectionHandler((RedisConnection) result));
            }
            return result;
        }
    }

    private final class ConnectionHandler implements InvocationHandler {
        private final RedisConnection target;

        private ConnectionHandler(RedisConnection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (TraceContext.isSuppressed()) {
                return call(method, target, args);
            }
            String name = method.getName();
            if (META.contains(name)) {
                return call(method, target, args);
            }

            String command = name.toUpperCase();
            String key = null;
            if ("execute".equals(name) && args != null && args.length >= 1 && args[0] instanceof String) {
                command = ((String) args[0]).toUpperCase();
                key = extractKey(args.length > 1 ? new Object[]{args[1]} : null);
            } else {
                key = extractKey(args);
            }

            long t0 = System.nanoTime();
            try {
                Object r = call(method, target, args);
                record(command, key, millis(t0), "OK", null);
                return r;
            } catch (Throwable t) {
                record(command, key, millis(t0), "ERROR", t.getMessage());
                throw t;
            }
        }
    }

    private static int millis(long startNano) {
        return (int) ((System.nanoTime() - startNano) / 1000000L);
    }

    /** 取第一个 byte[]（或 byte[][] 的首元素）作为 key —— 各 Redis 命令的 key 都在首位 */
    private static String extractKey(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a instanceof byte[]) {
                return new String((byte[]) a, StandardCharsets.UTF_8);
            }
            if (a instanceof byte[][]) {
                byte[][] arr = (byte[][]) a;
                if (arr.length > 0) {
                    return new String(arr[0], StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
