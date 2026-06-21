package top.sywyar.pixivdownload.mail.controller;

import java.util.List;

/**
 * GUI "发送所有邮件模板" 端点的响应。
 * <p>
 * 逐一发送 {@link top.sywyar.pixivdownload.mail.template.MailTemplateRegistry#templates()} 中的全部模板（使用示例占位符），
 * 按总数 / 成功数 / 失败列表返回。每条 {@link Failure#error} 已由 {@code MailService.safeMessage} 截断并脱敏，
 * 绝不含密码 / cookie。
 */
public record MailTestAllResponse(
        boolean success,
        int total,
        int succeeded,
        List<Failure> failures
) {

    public record Failure(String templateId, String error) {
    }

    public static MailTestAllResponse ok(int total) {
        return new MailTestAllResponse(true, total, total, List.of());
    }

    public static MailTestAllResponse partial(int total, int succeeded, List<Failure> failures) {
        return new MailTestAllResponse(false, total, succeeded, failures);
    }

    public static MailTestAllResponse fail(String error) {
        return new MailTestAllResponse(false, 0, 0, List.of(new Failure("-", error)));
    }
}
