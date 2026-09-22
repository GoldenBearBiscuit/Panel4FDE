package cn.observe.core.mask;

import java.util.regex.Pattern;

/**
 * 脱敏（SCHEMA.md 第六节）。不可简化的安全边界。
 *
 * <p>文本级实现，零依赖：字段名黑名单用 JSON 形态正则，敏感值用值特征正则。
 * 不追求完备的 JSON 解析——采集侧不阻塞业务比形式完美更重要。
 */
public final class Masker {

    /** ★ 字段名黑名单：命中则整体替换为 *** */
    private static final String[] BLACKLIST = {
            "password", "passwd", "pwd", "token", "accessToken", "refreshToken",
            "authorization", "secret", "clientSecret", "idCard", "idNumber",
            "bankCard", "cvv", "smsCode", "captcha", "privateKey", "apiKey"
    };

    private static final Pattern BLACKLIST_RE = Pattern.compile(
            "(?i)(\"(?:password|passwd|pwd|token|accessToken|refreshToken|authorization|secret"
                    + "|clientSecret|idCard|idNumber|bankCard|cvv|smsCode|captcha|privateKey|apiKey)\"\\s*:\\s*)"
                    + "(\"[^\"]*\"|\\d+|true|false|null)");

    /**
     * 手机号：保留前 3 后 4。
     * ★ 边界守卫 (?<!\d) / (?!\d) 必不可少：否则会在身份证这种长数字串内部误匹配。
     */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    /**
     * 18 位身份证：保留前 6 后 4。
     * ★ 必须在 PHONE 之前执行：身份证更长、更具体，先掩掉才不会让手机号正则抢先咬中间一段。
     */
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)(\\d{6})\\d{8}(\\d{3}[0-9Xx])(?![\\dXx])");
    /** 邮箱：保留首字符 + 域名 */
    private static final Pattern EMAIL = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])([A-Za-z0-9])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    private Masker() {
    }

    /** 字段名黑名单 + 值特征掩码。null 安全。 */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = text;
        // 1. 字段名黑名单优先（整体遮蔽）
        s = BLACKLIST_RE.matcher(s).replaceAll("$1\"***\"");
        // 2. 值特征掩码（保留可读性，便于排查）
        // ★ 顺序不可颠倒：身份证(18位) → 手机号(11位) → 邮箱
        //    若手机号在前，会在身份证内部误匹配（已由 NormalizeChecklistTest 捕获过）
        s = ID_CARD.matcher(s).replaceAll("$1********$2");
        s = PHONE.matcher(s).replaceAll("$1****$2");
        s = EMAIL.matcher(s).replaceAll("$1***$2");
        return s;
    }

    /** 供配置使用：判断字段名是否在黑名单里 */
    public static boolean isSensitiveField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        for (String b : BLACKLIST) {
            if (b.equalsIgnoreCase(fieldName)) {
                return true;
            }
        }
        return false;
    }

    /** 截断到指定长度，避免超长 body 撑爆 raw_payload */
    public static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...[truncated]";
    }
}
