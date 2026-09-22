package cn.observe.core.util;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * ★ 回归测试：代理必须实现目标的<b>全部</b>公开接口。
 *
 * <p>真实故障（2026-09-22）：Lettuce 的 {@code LettuceConnectionFactory} 同时实现
 * {@code RedisConnectionFactory} 与 {@code ReactiveRedisConnectionFactory}。
 * 早期实现只代理前者，导致 Spring Boot 的 {@code ReactiveRedisAutoConfiguration}
 * 注入失败，应用直接启动不起来。
 */
public class ProxiesTest {

    public interface A {
        String a();
    }

    public interface B {
        String b();
    }

    public static class Both implements A, B {
        @Override
        public String a() {
            return "a";
        }

        @Override
        public String b() {
            return "b";
        }
    }

    @Test
    public void proxyImplementsAllPublicInterfaces() {
        Both target = new Both();
        Object proxy = Proxies.wrap(target, A.class, new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] args) throws Throwable {
                return m.invoke(target, args);
            }
        });

        assertTrue("必须可注入回 A 接口", proxy instanceof A);
        assertTrue("★ 必须也可注入回 B 接口，否则该接口的注入点会失败", proxy instanceof B);
        assertEquals("a", ((A) proxy).a());
        assertEquals("b", ((B) proxy).b());
    }

    @Test
    public void requiredInterfaceAlwaysIncluded() {
        // target 没实现 C，但 required 强制加上（DataSource 场景）
        Object proxy = Proxies.wrap(new Both(), A.class, new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method m, Object[] args) {
                return null;
            }
        });
        assertTrue(proxy instanceof A);
        assertTrue(Proxies.isOurProxy(proxy));
    }

    @Test
    public void interfacesOfIsNonEmpty() {
        Class<?>[] ifaces = Proxies.interfacesOf(new Both(), null);
        assertTrue(ifaces.length >= 2);
    }
}
