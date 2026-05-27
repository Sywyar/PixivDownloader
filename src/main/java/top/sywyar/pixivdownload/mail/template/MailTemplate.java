package top.sywyar.pixivdownload.mail.template;

/**
 * 邮件模板元数据。
 *
 * @param id         模板 id（与磁盘文件名一致；i18n key 形如 {@code mail.template.{id}.subject}）
 * @param subjectKey subject i18n key
 */
public record MailTemplate(String id, String subjectKey) {

    public static MailTemplate of(String id) {
        return new MailTemplate(id, "mail.template." + id + ".subject");
    }
}
