package cn.observe.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 插件配置。全部有默认值，接入方零配置即可工作。
 */
@ConfigurationProperties(prefix = "observe")
public class ObserveProperties {

    /** 总开关 */
    private boolean enabled = true;

    /** 后端 API 事件采集 */
    private boolean collectApi = true;
    /** SQL 事件采集（资源层第一类） */
    private boolean collectSql = true;
    /** Redis 事件采集（资源层第二类） */
    private boolean collectRedis = true;

    /** 不采集的 URL 模式。★ 默认排除 /observe/** —— 否则「看图的动作」会污染被看的图 */
    private List<String> excludeUrls = new ArrayList<>(Arrays.asList(
            "/observe/**", "/favicon.ico", "/error", "/actuator/**"));

    /** 事件队列容量。满则丢弃并计数，绝不让采集拖垮业务 */
    private int queueCapacity = 5000;
    /** 单批落库条数 */
    private int batchSize = 200;
    /** 队列空时的轮询间隔(ms) */
    private int flushIntervalMs = 300;

    /** raw_payload 最大长度，超出截断 */
    private int rawPayloadMaxLength = 2000;

    /**
     * 是否采集请求/响应体。★ 默认 false。
     * 打开会显著增加敏感信息泄露面，只在排查期临时开启。
     */
    private boolean captureBody = false;

    /** 是否把事件也打到日志（排查用） */
    private boolean logEvents = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    public boolean isCollectApi() { return collectApi; }
    public void setCollectApi(boolean v) { this.collectApi = v; }

    public boolean isCollectSql() { return collectSql; }
    public void setCollectSql(boolean v) { this.collectSql = v; }

    public boolean isCollectRedis() { return collectRedis; }
    public void setCollectRedis(boolean v) { this.collectRedis = v; }

    public List<String> getExcludeUrls() { return excludeUrls; }
    public void setExcludeUrls(List<String> v) { this.excludeUrls = v; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int v) { this.queueCapacity = v; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int v) { this.batchSize = v; }

    public int getFlushIntervalMs() { return flushIntervalMs; }
    public void setFlushIntervalMs(int v) { this.flushIntervalMs = v; }

    public int getRawPayloadMaxLength() { return rawPayloadMaxLength; }
    public void setRawPayloadMaxLength(int v) { this.rawPayloadMaxLength = v; }

    public boolean isCaptureBody() { return captureBody; }
    public void setCaptureBody(boolean v) { this.captureBody = v; }

    public boolean isLogEvents() { return logEvents; }
    public void setLogEvents(boolean v) { this.logEvents = v; }
}
