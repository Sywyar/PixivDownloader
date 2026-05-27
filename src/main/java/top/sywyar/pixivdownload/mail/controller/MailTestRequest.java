package top.sywyar.pixivdownload.mail.controller;

import top.sywyar.pixivdownload.mail.MailConfig;
import top.sywyar.pixivdownload.mail.MailSecurity;
import top.sywyar.pixivdownload.mail.MailSenderSettings;

/**
 * GUI 配置页"发送测试邮件"按钮的请求 DTO。
 * <p>
 * 由 {@link top.sywyar.pixivdownload.mail.controller.MailTestController#test} 接收；
 * 字段对应 GUI 邮件分组的当前表单值。包含密码（用户尚未保存配置，需通过本地端点传给后端），仅在
 * {@link top.sywyar.pixivdownload.common.NetworkUtils#isTrustedLocalRequest} + GUI token 双校验后通过同进程 localhost
 * 流转。
 */
public record MailTestRequest(
        String host,
        Integer port,
        String security,
        String username,
        String password,
        String from,
        String to,
        String socksProxy,
        String subjectPrefix
) {

    /** 转为不可变 {@link MailSenderSettings}；缺字段回退 MailConfig 默认值。 */
    public MailSenderSettings toSenderSettings() {
        int effectivePort = (port == null || port <= 0) ? MailConfig.DEFAULT_PORT : port;
        String effectiveFrom = (from == null || from.isBlank()) ? username : from;
        String effectivePrefix = (subjectPrefix == null || subjectPrefix.isBlank())
                ? MailConfig.DEFAULT_SUBJECT_PREFIX
                : subjectPrefix;
        return new MailSenderSettings(
                nullToEmpty(host),
                effectivePort,
                MailSecurity.parse(security),
                nullToEmpty(username),
                nullToEmpty(password),
                nullToEmpty(effectiveFrom),
                nullToEmpty(to),
                nullToEmpty(socksProxy),
                effectivePrefix);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
