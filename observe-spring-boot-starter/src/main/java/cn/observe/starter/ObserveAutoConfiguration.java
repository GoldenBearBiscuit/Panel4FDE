package cn.observe.starter;

import cn.observe.starter.api.ObserveGraphController;
import cn.observe.starter.api.ObserveReportController;
import cn.observe.starter.collect.DataSourceCollector;
import cn.observe.starter.collect.RedisCollector;
import cn.observe.starter.filter.TraceFilter;
import cn.observe.starter.store.EventQueue;
import cn.observe.starter.store.EventWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.sql.DataSource;

/**
 * 插件自动装配。
 *
 * <p>★ 能力探测用 Spring Boot 条件装配，不自建 SPI 框架
 * （分层适配矩阵 L2–L7，见 specs/PLAN.md）。
 *
 * <p>★ 所有采集器都是 {@link BeanPostProcessor}，其 @Bean 方法声明为 <b>static</b>
 * 且只接收 {@link ObjectProvider}——原因见 {@link DataSourceCollector#queue()} 的注释：
 * 否则 DataSource 会先于 BPP 注册被创建，采集静默失效。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ObserveProperties.class)
@ConditionalOnProperty(prefix = "observe", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ObserveAutoConfiguration {

    // ══ 存储（L7）：有 JdbcTemplate 才启用 ══════════════════════════

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcTemplate.class)
    static class StoreConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public EventWriter observeEventWriter(JdbcTemplate jdbcTemplate) {
            return new EventWriter(jdbcTemplate);
        }

        @Bean(destroyMethod = "destroy")
        @ConditionalOnMissingBean
        public EventQueue observeEventQueue(EventWriter writer, ObserveProperties props) {
            // 启动由 EventQueue 自身的 @PostConstruct 负责，这里不要再调 start()
            // （曾重复启动导致两个 worker 线程，泄漏一个）
            return new EventQueue(writer, props);
        }
    }

    // ══ L6 资源层之一：SQL ═════════════════════════════════════════

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DataSource.class)
    static class SqlCollectionConfiguration {

        @Bean
        public static BeanPostProcessor observeDataSourceCollector(
                ObjectProvider<EventQueue> queue,
                ObserveProperties props,
                @org.springframework.beans.factory.annotation.Value("${spring.application.name:unknown}") String appName) {
            return new DataSourceCollector(queue, props, appName);
        }
    }

    // ══ L6 资源层之二：Redis ═══════════════════════════════════════

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.redis.connection.RedisConnectionFactory")
    static class RedisCollectionConfiguration {

        @Bean
        public static BeanPostProcessor observeRedisCollector(
                ObjectProvider<EventQueue> queue,
                ObserveProperties props,
                @org.springframework.beans.factory.annotation.Value("${spring.application.name:unknown}") String appName) {
            return new RedisCollector(queue, props, appName);
        }
    }

    // ══ L3 + L4 + 查询接口 ════════════════════════════════════════

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(OncePerRequestFilter.class)
    static class WebConfiguration {

        @Bean
        public FilterRegistrationBean<TraceFilter> observeTraceFilter(
                ObserveProperties props,
                EventQueue queue,
                @org.springframework.beans.factory.annotation.Value("${spring.application.name:unknown}") String appName) {
            FilterRegistrationBean<TraceFilter> reg = new FilterRegistrationBean<TraceFilter>(
                    new TraceFilter(props, queue, appName));
            reg.addUrlPatterns("/*");
            // ★ 最高优先级：让它包住后续所有 Filter，API 耗时才是完整耗时
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
            reg.setName("observeTraceFilter");
            return reg;
        }

        @Bean
        @ConditionalOnMissingBean
        public ObserveReportController observeReportController(
                EventQueue queue,
                ObserveProperties props,
                @org.springframework.beans.factory.annotation.Value("${spring.application.name:unknown}") String appName) {
            return new ObserveReportController(queue, props, appName);
        }

        @Bean
        @ConditionalOnMissingBean
        public ObserveGraphController observeGraphController(EventWriter writer, EventQueue queue) {
            return new ObserveGraphController(writer, queue);
        }
    }
}
