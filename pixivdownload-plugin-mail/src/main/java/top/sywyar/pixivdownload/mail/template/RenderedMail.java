package top.sywyar.pixivdownload.mail.template;

/**
 * 模板渲染结果：subject 已含本地化标题（但**不含** {@code mail.subject-prefix} 前缀，
 * 由 {@link top.sywyar.pixivdownload.mail.MailService#send} 拼接），body 为最终可发送的 HTML 串。
 */
public record RenderedMail(String subject, String htmlBody) {
}
