package cn.observe.core.util;

/**
 * 极小 JSON 构造器。零依赖 —— observe-core 不允许引入任何库（见 observe-core/pom.xml 冻结约束）。
 *
 * <p>只做「构造合法 JSON 字符串」这一件事，不做解析。
 */
public final class Json {

    private Json() {
    }

    /** 转义字符串并加引号。null → null（JSON 字面量） */
    public static String str(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 数值/布尔/null 原样输出；字符串自动转义 */
    public static String val(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return str(String.valueOf(v));
    }

    /**
     * 构造对象，参数为 key,value 交替。
     * <pre>Json.obj("sql", sql, "rows", 3)  →  {"sql":"...","rows":3}</pre>
     */
    public static String obj(Object... kvs) {
        if (kvs == null || kvs.length == 0) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(str(String.valueOf(kvs[i]))).append(':').append(val(kvs[i + 1]));
        }
        sb.append('}');
        return sb.toString();
    }
}
