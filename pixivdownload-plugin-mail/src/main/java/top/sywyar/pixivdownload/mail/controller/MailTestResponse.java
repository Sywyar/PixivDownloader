package top.sywyar.pixivdownload.mail.controller;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

/**
 * GUI "发送测试邮件" 端点的响应。失败时 {@link #error} 已脱敏（绝不含密码）。
 */
public record MailTestResponse(
        boolean success,
        @HttpApiNullable String error
) {
    public static MailTestResponse ok() {
        return new MailTestResponse(true, null);
    }

    public static MailTestResponse fail(String error) {
        return new MailTestResponse(false, error);
    }
}
