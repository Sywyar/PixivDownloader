package top.sywyar.pixivdownload.mail.controller;

/**
 * GUI "发送测试邮件" 端点的响应。失败时 {@link #error} 已脱敏（绝不含密码）。
 */
public record MailTestResponse(
        boolean success,
        String error
) {
    public static MailTestResponse ok() {
        return new MailTestResponse(true, null);
    }

    public static MailTestResponse fail(String error) {
        return new MailTestResponse(false, error);
    }
}
