package cn.observe.core.normalize;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R2 — SQL 归一化。自写单遍状态机（不引 SQL parser，理由见 PLAN.md：parser 失败是「整体丢弃」，
 * 自写最坏只是「部分归一化」，能降级）。
 *
 * <p>处理顺序（NORMALIZE.md 规定，不可变）：去注释 → 折叠空白 → 字符串→? → 数字→? → IN 折叠 →
 * 关键字大写 → sha1 截断。
 *
 * <p>★ 数字替换必须跳过标识符内的数字：{@code FROM orders_2024 t1} 里两者都是标识符，
 * 被替换会导致「不同表合并成同一节点」——比不归一化更糟。
 */
public final class SqlNormalizer {

    private static final Pattern TABLE_RE = Pattern.compile(
            "(?i)\\b(?:FROM|INTO|UPDATE|JOIN)\\s+[`\"']?([A-Za-z_][A-Za-z0-9_$]*)");

    private static final Pattern IN_LIST_RE = Pattern.compile(
            "\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)");

    private SqlNormalizer() {
    }

    // ── 对外 ─────────────────────────────────────────────────────

    /** @return 归一化指纹；SQL 为空时返回 "" */
    public static String fingerprint(String rawSql) {
        if (rawSql == null || rawSql.trim().isEmpty()) {
            return "";
        }
        String out = scan(rawSql);
        // IN (?, ?, ?) → IN (?)
        for (int i = 0; i < 5; i++) {
            String next = IN_LIST_RE.matcher(out).replaceAll("(?)");
            if (next.equals(out)) {
                break;
            }
            out = next;
        }
        return out;
    }

    /** ★ 图节点 ID：sql: + sha1(指纹) 前 16 位 */
    public static String nodeKey(String rawSql) {
        String fp = fingerprint(rawSql);
        if (fp.isEmpty()) {
            return "sql:unknown";
        }
        return "sql:" + sha1(fp).substring(0, 16);
    }

    /** 图上显示名，如 {@code SELECT demo_order}。从**原始** SQL 提取以保留大小写可读性。 */
    public static String nodeLabel(String rawSql) {
        if (rawSql == null || rawSql.trim().isEmpty()) {
            return "SQL";
        }
        String cleaned = stripComments(rawSql).trim();
        String kw = leadingKeyword(cleaned);
        Matcher m = TABLE_RE.matcher(cleaned);
        if (m.find()) {
            return kw + " " + m.group(1);
        }
        return kw;
    }

    // ── 状态机 ───────────────────────────────────────────────────

    private static String scan(String s) {
        int n = s.length();
        int i = 0;
        StringBuilder out = new StringBuilder(n);

        while (i < n) {
            char c = s.charAt(i);

            // 1. 块注释 /* ... */
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                appendSpace(out);
                continue;
            }

            // 2. 行注释 -- 或 #
            if ((c == '-' && i + 1 < n && s.charAt(i + 1) == '-') || c == '#') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
                appendSpace(out);
                continue;
            }

            // 3. 字符串字面量 → ?（处理 \\ 转义与 '' 双写转义）
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                while (i < n) {
                    char e = s.charAt(i);
                    if (e == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    if (e == quote) {
                        if (i + 1 < n && s.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
                i++; // 跳过收尾引号
                out.append('?');
                continue;
            }

            // 4. 反引号标识符（表名/列名）→ 原样保留
            if (c == '`') {
                out.append('`');
                i++;
                while (i < n && s.charAt(i) != '`') {
                    out.append(s.charAt(i));
                    i++;
                }
                if (i < n) {
                    out.append('`');
                    i++;
                }
                continue;
            }

            // 5. 数字字面量 → ?。★仅当前一个输出字符不是标识符字符时才替换
            if (c >= '0' && c <= '9' && !isIdentChar(lastChar(out))) {
                while (i < n) {
                    char d = s.charAt(i);
                    if ((d >= '0' && d <= '9') || d == '.') {
                        i++;
                    } else {
                        break;
                    }
                }
                out.append('?');
                continue;
            }

            // 6. 单词（标识符或关键字）→ 整体消费后统一大写。
            //    整体消费天然保证 orders_2024 / utf8mb4 / t1 里的数字不被替换（规则 4 的例外）
            if (Character.isLetter(c) || c == '_' || c == '$') {
                int st = i;
                while (i < n) {
                    char d = s.charAt(i);
                    if (Character.isLetterOrDigit(d) || d == '_' || d == '$') {
                        i++;
                    } else {
                        break;
                    }
                }
                out.append(s.substring(st, i).toUpperCase());
                continue;
            }

            // 7. 其余字符原样
            out.append(c);
            i++;
        }

        return out.toString().replaceAll("\\s+", " ").trim();
    }

    // ── 辅助 ─────────────────────────────────────────────────────

    private static void appendSpace(StringBuilder out) {
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) != ' ') {
            out.append(' ');
        }
    }

    private static char lastChar(StringBuilder sb) {
        int len = sb.length();
        return len == 0 ? '\0' : sb.charAt(len - 1);
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '`' || c == '.';
    }

    /** 轻量去注释，仅供 node_label 使用（不需要完整状态机） */
    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)--[^\n]*", " ")
                .replaceAll("(?m)#[^\n]*", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String leadingKeyword(String cleaned) {
        int i = 0;
        while (i < cleaned.length() && Character.isLetter(cleaned.charAt(i))) {
            i++;
        }
        return i == 0 ? "SQL" : cleaned.substring(0, i).toUpperCase();
    }

    static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-1 是 JDK 必备算法，不会走到这里
            return String.valueOf(s.hashCode());
        }
    }
}
