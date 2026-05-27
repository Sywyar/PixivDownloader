package top.sywyar.pixivdownload.mail;

import java.util.Locale;

/**
 * SMTP 加密方式。
 * <ul>
 *   <li>{@link #NONE} —— 明文连接（仅内网测试用）</li>
 *   <li>{@link #SSL} —— 整条 TCP 由 TLS 包裹（俗称 SMTPS，常用 465 端口）</li>
 *   <li>{@link #STARTTLS} —— 明文连接后通过 STARTTLS 升级（常用 587 端口）</li>
 * </ul>
 */
public enum MailSecurity {
    NONE,
    SSL,
    STARTTLS;

    /** 以 config.yaml / DTO 中使用的小写连字符形式返回。 */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 安全解析；输入为 null / 空 / 未知值时回退 {@link #STARTTLS}。 */
    public static MailSecurity parse(String raw) {
        if (raw == null) {
            return STARTTLS;
        }
        String token = raw.trim().toLowerCase(Locale.ROOT);
        return switch (token) {
            case "ssl", "smtps" -> SSL;
            case "starttls", "tls" -> STARTTLS;
            case "none", "plain" -> NONE;
            default -> STARTTLS;
        };
    }
}
