package cn.observe.core.util;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * JDK 动态代理辅助。
 *
 * <p>★ 为什么不直接写死接口列表：目标 bean 常常实现<b>多个</b>接口，只代理其中一个会
 * 让 bean 从其他接口的注入点消失。
 *
 * <p>实例（真实踩过）：Lettuce 的 {@code LettuceConnectionFactory} 同时实现
 * {@code RedisConnectionFactory} 与 {@code ReactiveRedisConnectionFactory}。
 * 只代理前者会导致 {@code ReactiveRedisAutoConfiguration} 注入失败，
 * 应用<b>启动直接失败</b>：
 * <pre>
 *   Bean named 'redisConnectionFactory' is expected to be of type
 *   'ReactiveRedisConnectionFactory' but was actually of type 'com.sun.proxy.$Proxy62'
 * </pre>
 * 所以必须把目标的全部公开接口都代理上——未被拦截的方法一律转发，因此多代理几个接口无害。
 */
public final class Proxies {

    private Proxies() {
    }

    /**
     * 为目标对象创建代理，实现其全部公开接口。
     *
     * @param target            被代理对象
     * @param requiredInterface 保证一定包含的接口（目标未实现时也强制加上，如 DataSource）
     */
    public static Object wrap(Object target, Class<?> requiredInterface, InvocationHandler handler) {
        Class<?>[] interfaces = interfacesOf(target, requiredInterface);
        return Proxy.newProxyInstance(loaderFor(target), interfaces, handler);
    }

    /** 收集目标类层次上的全部公开接口，去重 */
    public static Class<?>[] interfacesOf(Object target, Class<?> requiredInterface) {
        Set<Class<?>> set = new LinkedHashSet<Class<?>>();
        if (requiredInterface != null) {
            set.add(requiredInterface);
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (i != null && Modifier.isPublic(i.getModifiers())) {
                    set.add(i);
                }
            }
        }
        return set.toArray(new Class<?>[set.size()]);
    }

    public static ClassLoader loaderFor(Object target) {
        ClassLoader cl = target.getClass().getClassLoader();
        return cl != null ? cl : Thread.currentThread().getContextClassLoader();
    }

    /** 目标是否已经是本工具产生的代理（避免重复包装） */
    public static boolean isOurProxy(Object o) {
        return Proxy.isProxyClass(o.getClass());
    }
}
