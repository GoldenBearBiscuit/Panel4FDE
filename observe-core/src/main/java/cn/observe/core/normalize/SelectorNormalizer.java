package cn.observe.core.normalize;

import java.util.regex.Pattern;

/**
 * R3 — DOM selector 归一化。
 *
 * <p>★ 严禁 :nth-child() / 绝对 DOM 路径：位置不稳定，同一按钮在不同数据下位置会变，
 * 会把一个节点炸成几十个。前端 SDK 已按优先级生成稳定描述符，这里做防御性清理。
 */
public final class SelectorNormalizer {

    private static final Pattern NTH = Pattern.compile(":nth-child\\(\\d+\\)");
    private static final Pattern NTH_OF_TYPE = Pattern.compile(":nth-of-type\\(\\d+\\)");

    private SelectorNormalizer() {
    }

    public static String normalize(String selector) {
        if (selector == null || selector.trim().isEmpty()) {
            return "unknown";
        }
        String s = selector.trim();
        s = NTH.matcher(s).replaceAll("");
        s = NTH_OF_TYPE.matcher(s).replaceAll("");
        // 折叠可能产生的重复分隔符
        s = s.replaceAll("\\s+", " ").replaceAll("\\.{2,}", ".").trim();
        return s.isEmpty() ? "unknown" : s;
    }

    public static String nodeKey(String selector) {
        return "action:" + normalize(selector);
    }
}
