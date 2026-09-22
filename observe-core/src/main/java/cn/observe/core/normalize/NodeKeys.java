package cn.observe.core.normalize;

/**
 * 节点键门面 —— ★ 全系统唯一的 node_key 生成入口。
 *
 * <p>前端只上报「原始值」（path / selector / method），归一化一律在后端做。
 * 这样前后端不可能生成不一致的 node_key，{@code SCHEMA.md} 第三节的 API 事件去重规则才成立。
 */
public final class NodeKeys {

    // 节点类型（kind），前端上报 parent 时使用
    public static final String KIND_PAGE = "PAGE";
    public static final String KIND_ACTION = "ACTION";
    public static final String KIND_API = "API";
    public static final String KIND_SQL = "SQL";
    public static final String KIND_REDIS = "REDIS";

    private NodeKeys() {
    }

    public static String page(String path) {
        return UrlNormalizer.pageKey(path);
    }

    public static String action(String selector) {
        return SelectorNormalizer.nodeKey(selector);
    }

    public static String api(String url) {
        return UrlNormalizer.nodeKey(url);
    }

    public static String sql(String rawSql) {
        return SqlNormalizer.nodeKey(rawSql);
    }

    public static String redis(String command, String key) {
        return RedisKeyNormalizer.nodeKey(command, key);
    }

    /**
     * 通用分发：把 (kind, value) 转成 node_key。
     *
     * @param kind  PAGE / ACTION / API / SQL / REDIS
     * @param value 对应的原始值；REDIS 时形如 "GET user:1:profile"
     * @return node_key；kind 为空或无法识别时返回 null
     */
    public static String of(String kind, String value) {
        if (kind == null || kind.isEmpty() || value == null || value.isEmpty()) {
            return null;
        }
        String k = kind.trim().toUpperCase();
        if (KIND_PAGE.equals(k)) {
            return page(value);
        }
        if (KIND_ACTION.equals(k)) {
            return action(value);
        }
        if (KIND_API.equals(k)) {
            return api(value);
        }
        if (KIND_SQL.equals(k)) {
            return sql(value);
        }
        if (KIND_REDIS.equals(k)) {
            int sp = value.indexOf(' ');
            return sp < 0 ? redis(value, "") : redis(value.substring(0, sp), value.substring(sp + 1));
        }
        return null;
    }
}
