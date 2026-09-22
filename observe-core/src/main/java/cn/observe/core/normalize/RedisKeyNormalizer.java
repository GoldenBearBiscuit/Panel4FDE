package cn.observe.core.normalize;

/**
 * R4 — Redis key 归一化。规则与 R1 同构（复用 {@link SegmentNormalizer}）。
 *
 * <pre>
 *   user:88231:profile → user:{id}:profile
 *   token:&lt;uuid&gt;       → token:{uuid}
 * </pre>
 *
 * 固定前缀段（user / token / session）必须原样保留——它们才是语义。
 */
public final class RedisKeyNormalizer {

    private RedisKeyNormalizer() {
    }

    public static String normalizeKey(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        String[] segs = key.split(":", -1);
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(SegmentNormalizer.normalize(segs[i]));
        }
        return sb.toString();
    }

    /** ★ 图节点 ID：redis:命令 归一化key，如 {@code redis:GET user:{id}:profile} */
    public static String nodeKey(String command, String key) {
        String cmd = (command == null || command.isEmpty()) ? "UNKNOWN" : command.toUpperCase();
        String k = normalizeKey(key);
        return "redis:" + cmd + (k == null || k.isEmpty() ? "" : " " + k);
    }

    /** 图上显示名，如 {@code GET user:*:profile}。SCHEMA.md 规定用 * 通配 */
    public static String nodeLabel(String command, String key) {
        String cmd = (command == null || command.isEmpty()) ? "UNKNOWN" : command.toUpperCase();
        String k = SegmentNormalizer.wildcard(normalizeKey(key));
        return cmd + (k == null || k.isEmpty() ? "" : " " + k);
    }
}
