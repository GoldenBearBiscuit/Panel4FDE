package cn.observe.core.normalize;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 单个「段」的归一化规则。URL 路径段与 Redis key 段共用同一套规则（NORMALIZE.md R1 / R4）。
 *
 * <p>★ 保守优先：只替换有把握的四种形态。宁可少归一化（图更细），
 * 也不要误判（把语义段并成一个节点 → 静默错误数据）。
 */
public final class SegmentNormalizer {

    private static final Pattern UUID_RE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private SegmentNormalizer() {
    }

    /**
     * @param seg 单个段（不含分隔符）
     * @return 归一化后的段；无把握时原样返回
     */
    public static String normalize(String seg) {
        if (seg == null || seg.isEmpty()) {
            return seg == null ? "" : seg;
        }
        if (isAllDigits(seg)) {
            return "{id}";
        }
        if (seg.indexOf('@') >= 0) {
            return "{email}";
        }
        if (UUID_RE.matcher(seg).matches()) {
            return "{uuid}";
        }
        // ★ 长度 >= 16 且全 hex 才认定为哈希。
        //   该约束保证 `list` / `detail` / `create` 这类语义段不会被误判：
        //   `list` 含非 hex 字符且只有 4 位 → 天然不命中。见 NORMALIZE.md 验证清单 #2
        if (seg.length() >= 16 && isHex(seg)) {
            return "{hash}";
        }
        return seg;
    }

    /** 把归一化占位符换成通配符，用于 node_label（如 user:{id}:profile → user:*:profile） */
    public static String wildcard(String normalized) {
        if (normalized == null) {
            return null;
        }
        return normalized
                .replace("{id}", "*")
                .replace("{uuid}", "*")
                .replace("{hash}", "*")
                .replace("{email}", "*");
    }

    public static boolean isUuid(String s) {
        return s != null && UUID_RE.matcher(s).matches();
    }

    /** 生成一个无横线的 uuid（用作 eventId / sessionId） */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
