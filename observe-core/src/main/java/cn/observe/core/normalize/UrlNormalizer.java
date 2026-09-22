package cn.observe.core.normalize;

/**
 * R1 — URL 归一化。query string 不进 node_key（进 raw_payload）。
 *
 * <pre>
 *   /api/order/123/detail  → /api/order/{id}/detail
 *   /api/order/list        → /api/order/list      （语义段必须保留）
 * </pre>
 */
public final class UrlNormalizer {

    private UrlNormalizer() {
    }

    /** 只归一化 path：去掉 scheme/host、query、fragment，逐段应用 {@link SegmentNormalizer} */
    public static String normalizePath(String url) {
        if (url == null || url.isEmpty()) {
            return "/";
        }
        String path = url;

        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int h = path.indexOf('#');
        if (h >= 0) {
            path = path.substring(0, h);
        }
        // 去掉 scheme://host
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash >= 0 ? path.substring(slash) : "/";
        }
        if (path.isEmpty()) {
            return "/";
        }
        if (path.charAt(0) != '/') {
            path = "/" + path;
        }
        // 去掉结尾多余斜杠（保留根）
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        String[] segs = path.split("/", -1);
        StringBuilder sb = new StringBuilder(path.length());
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(SegmentNormalizer.normalize(segs[i]));
        }
        return sb.toString();
    }

    /** ★ 图节点 ID。前后端同一次调用必须落到同一个值（见 SCHEMA.md 第三节去重规则） */
    public static String nodeKey(String url) {
        return "api:" + normalizePath(url);
    }

    /** 同名语义的 page 节点（前端路由） */
    public static String pageKey(String path) {
        return "page:" + normalizePath(path);
    }

    public static String nodeLabel(String method, String url) {
        String m = (method == null || method.isEmpty()) ? "GET" : method.toUpperCase();
        return m + " " + normalizePath(url);
    }

    public static String pageLabel(String path) {
        return normalizePath(path);
    }
}
