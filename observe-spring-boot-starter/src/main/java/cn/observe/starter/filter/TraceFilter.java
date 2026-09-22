package cn.observe.starter.filter;

import cn.observe.core.mask.Masker;
import cn.observe.core.model.EventType;
import cn.observe.core.model.Layer;
import cn.observe.core.model.ObserveEvent;
import cn.observe.core.normalize.UrlNormalizer;
import cn.observe.core.util.Json;
import cn.observe.starter.ObserveProperties;
import cn.observe.starter.context.TraceContext;
import cn.observe.starter.store.EventQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * L3 + L4：traceId 缝合 + 后端 API 事件采集。
 *
 * <p>★ 这是整个系统的缝合点。它做四件事：
 * <ol>
 *   <li>读 W3C {@code traceparent} → traceId，使浏览器发起的行为与后端执行归属同一次调用</li>
 *   <li>读 {@code X-Session-Id} / {@code X-Step-No} → 会话与步序</li>
 *   <li>读 {@code tenant-id} / {@code X-User-Id} → ★ 多租户维度。
 *       这是 trace 与租户上下文的会合点，必须在<b>业务线程</b>上做
 *       （见 SCHEMA.md：绝不能在 SpanProcessor / 异步导出线程里读租户，会串租户）</li>
 *   <li>把当前 API 的 node_key 写进上下文，供 SQL/Redis 采集器作为 parent 建 CALL 边</li>
 * </ol>
 */
public class TraceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    private final ObserveProperties props;
    private final EventQueue queue;
    private final String appName;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public TraceFilter(ObserveProperties props, EventQueue queue, String appName) {
        this.props = props;
        this.queue = queue;
        this.appName = appName;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.isCollectApi()) {
            return true;
        }
        String uri = request.getRequestURI();
        for (String pattern : props.getExcludeUrls()) {
            if (matcher.match(pattern, uri)) {
                // ★ 排除 /observe/** —— 否则「看图的动作」会污染被看的图
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        long startMs = System.currentTimeMillis();

        HttpServletRequest req = request;
        if (props.isCaptureBody()) {
            req = new ContentCachingRequestWrapper(request);
        }

        String uri = request.getRequestURI();
        String method = request.getMethod();
        String nodeKey = UrlNormalizer.nodeKey(uri);

        // ── 建立上下文 ──
        TraceContext.setTraceId(parseTraceId(request.getHeader("traceparent")));
        TraceContext.setSessionId(request.getHeader("X-Session-Id"));
        TraceContext.setStepNo(parseInt(request.getHeader("X-Step-No")));
        TraceContext.setUserId(parseLong(request.getHeader("X-User-Id")));
        TraceContext.setTenantId(parseLong(request.getHeader("tenant-id")));
        TraceContext.setCurrentNode(nodeKey);

        putMdc();

        try {
            chain.doFilter(req, response);
        } finally {
            try {
                emitApiEvent(req, response, method, uri, nodeKey, startMs);
            } catch (Throwable t) {
                log.debug("[observe] API 事件产出失败: {}", t.toString());
            } finally {
                // ★ 必须清理：线程池复用会串数据
                TraceContext.clear();
                MDC.remove("traceId");
                MDC.remove("sessionId");
            }
        }
    }

    private void emitApiEvent(HttpServletRequest request, HttpServletResponse response,
                              String method, String uri, String nodeKey, long startMs) {
        int duration = (int) (System.currentTimeMillis() - startMs);
        int httpStatus = response.getStatus();
        String status = httpStatus >= 400 ? "ERROR" : "OK";

        StringBuilder payload = new StringBuilder();
        payload.append('{');
        payload.append("\"method\":").append(Json.str(method));
        payload.append(",\"path\":").append(Json.str(uri));
        if (request.getQueryString() != null) {
            payload.append(",\"query\":").append(Json.str(Masker.mask(request.getQueryString())));
        }
        payload.append(",\"httpStatus\":").append(httpStatus);
        if (props.isCaptureBody() && request instanceof ContentCachingRequestWrapper) {
            byte[] cached = ((ContentCachingRequestWrapper) request).getContentAsByteArray();
            if (cached.length > 0) {
                String body = Masker.mask(new String(cached, StandardCharsets.UTF_8));
                payload.append(",\"body\":").append(Json.str(body));
            }
        }
        payload.append('}');

        ObserveEvent e = ObserveEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .traceId(TraceContext.getTraceId())
                .sessionId(TraceContext.getSessionId() == null ? "-" : TraceContext.getSessionId())
                .stepNo(TraceContext.getStepNo())
                .layer(Layer.BACKEND)
                .eventType(EventType.API)
                .nodeKey(nodeKey)
                .nodeLabel(UrlNormalizer.nodeLabel(method, uri))
                // ★ parent_key / edge_type 留空：TRIGGER 边由前端那条同 node_key 的事件提供
                //   （SCHEMA.md 第三节去重规则）。重复提供会在图上产生重复边。
                .parentKey(null)
                .edgeType(null)
                // ★ occurred_at 必须用「请求开始」时刻，不能用响应返回时刻。
                //   否则同一步内 SQL/REDIS 事件的 occurred_at 早于 API 事件，
                //   PRECEDES 边会反向（已由事件表明细发现）
                .occurredAt(new Date(startMs))
                .durationMs(duration)
                .status(status)
                .tenantId(TraceContext.getTenantId())
                .userId(TraceContext.getUserId())
                .appName(appName)
                .rawPayload(Masker.truncate(payload.toString(), props.getRawPayloadMaxLength()))
                .build();
        queue.offer(e);
    }

    private void putMdc() {
        if (TraceContext.getTraceId() != null) {
            MDC.put("traceId", TraceContext.getTraceId());
        }
        if (TraceContext.getSessionId() != null) {
            MDC.put("sessionId", TraceContext.getSessionId());
        }
    }

    /** 解析 W3C traceparent: 00-&lt;32hex traceId&gt;-&lt;16hex spanId&gt;-&lt;01&gt; */
    static String parseTraceId(String traceparent) {
        if (traceparent == null || traceparent.isEmpty()) {
            return null;
        }
        String[] parts = traceparent.split("-");
        return parts.length >= 2 && parts[1].length() == 32 ? parts[1] : null;
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static Long parseLong(String s) {
        try {
            return s == null || s.isEmpty() ? null : Long.valueOf(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
