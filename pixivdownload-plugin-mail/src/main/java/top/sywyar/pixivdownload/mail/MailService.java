package top.sywyar.pixivdownload.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * SMTP 邮件发送服务。
 * <p>
 * 不依赖 Spring Boot 的 {@code spring.mail.*} 自动装配；每次发信按当前 {@link MailConfig}（或 GUI 传入的临时
 * {@link MailSenderSettings}）即时构造 {@link JavaMailSenderImpl}。这样既能支持热重载（修改配置不必重启），
 * 又允许 GUI 在尚未保存配置时直接发送测试邮件。
 * <p>
 * 两个入口：
 * <ul>
 *   <li>{@link #send(String, String)} —— best-effort，失败仅 {@code log.error} 不抛；调度器自发通知用</li>
 *   <li>{@link #sendTest(MailSenderSettings)} —— 抛 {@link MailSendException}，返回失败摘要给 GUI</li>
 * </ul>
 * 邮件正文以 HTML 形式发出，subject 加 {@link MailConfig#getSubjectPrefix()} 前缀。
 * 全程 UTF-8；正文 / subject / 失败摘要绝不含 cookie / PHPSESSID / 密码。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailService {

    private final MailConfig mailConfig;
    private final MessageResolver messages;

    /**
     * Best-effort 发信：使用当前 {@link MailConfig} 设置发送一封 HTML 邮件。
     * <p>
     * 当 {@link MailConfig#isEnabled()} 为 false、未配置主机或收件人时直接跳过。任何异常仅记录日志，
     * 不抛出（绝不能因发信失败影响调度）。
     *
     * @param subject  邮件标题（已含模板本地化标题；本方法会自动追加 {@code mail.subject-prefix} 前缀）
     * @param htmlBody 邮件正文 HTML
     */
    public void send(String subject, String htmlBody) {
        if (!mailConfig.isEnabled()) {
            log.debug(logMessage("mail.log.send.skipped.disabled"));
            return;
        }
        MailSenderSettings settings = mailConfig.toSenderSettings();
        if (settings.host() == null || settings.host().isBlank()) {
            log.warn(logMessage("mail.log.send.skipped.no-host"));
            return;
        }
        List<String> recipients = parseRecipients(settings.to());
        if (recipients.isEmpty()) {
            log.warn(logMessage("mail.log.send.skipped.no-recipients"));
            return;
        }

        try {
            deliver(settings, recipients, subject, htmlBody);
            log.info(logMessage("mail.log.send.success", joinRecipients(recipients), settings.host()));
        } catch (MailException | MessagingException e) {
            log.error(logMessage("mail.log.send.failed", safeMessage(e, settings.password())));
        } catch (RuntimeException e) {
            log.error(logMessage("mail.log.send.failed", safeMessage(e, settings.password())), e);
        }
    }

    /**
     * 发送测试邮件：使用调用方传入的临时设置（不读取 {@link MailConfig}），失败时抛 {@link MailSendException}
     * 让 GUI 显示失败原因。失败摘要由 {@link MailSendException#getMessage()} 提供，**绝不**回显密码。
     *
     * @param settings GUI 当前表单 / 单测准备的 SMTP 设置；密码字段仅在发信过程中使用，不写日志
     * @param subject  邮件标题
     * @param htmlBody 邮件正文 HTML
     * @throws MailSendException 发送失败时抛，message 已脱敏
     */
    public void sendTest(MailSenderSettings settings, String subject, String htmlBody) throws MailSendException {
        if (settings == null) {
            throw new MailSendException(localized("mail.error.settings-missing"));
        }
        if (settings.host() == null || settings.host().isBlank()) {
            throw new MailSendException(localized("mail.error.host-missing"));
        }
        List<String> recipients = parseRecipients(settings.to());
        if (recipients.isEmpty()) {
            throw new MailSendException(localized("mail.error.recipients-missing"));
        }
        try {
            deliver(settings, recipients, subject, htmlBody);
            log.info(logMessage("mail.log.test.success", joinRecipients(recipients), settings.host()));
        } catch (MessagingException | MailException e) {
            String msg = safeMessage(e, settings.password());
            log.warn(logMessage("mail.log.test.failed", msg));
            throw new MailSendException(msg, e);
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private void deliver(MailSenderSettings settings, List<String> recipients,
                         String subject, String htmlBody) throws MessagingException {
        JavaMailSender sender = createSender(settings);
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        helper.setFrom(resolveFromAddress(settings));
        helper.setTo(recipients.toArray(new String[0]));
        helper.setSubject(applySubjectPrefix(settings, subject));
        helper.setText(htmlBody == null ? "" : htmlBody, true);
        sender.send(message);
    }

    /** {@code mail.subject-prefix} 拼接。前缀为空时直接返回原 subject。 */
    private static String applySubjectPrefix(MailSenderSettings settings, String subject) {
        String prefix = settings.subjectPrefix() == null ? "" : settings.subjectPrefix().trim();
        String body = subject == null ? "" : subject;
        if (prefix.isEmpty()) {
            return body;
        }
        return prefix + " " + body;
    }

    private static InternetAddress resolveFromAddress(MailSenderSettings settings) throws MessagingException {
        String from = settings.from();
        if (from == null || from.isBlank()) {
            from = settings.username();
        }
        try {
            return new InternetAddress(from);
        } catch (jakarta.mail.internet.AddressException e) {
            throw new MessagingException("invalid sender address: " + from, e);
        }
    }

    /**
     * 按设置即时构造 {@link JavaMailSender}。包级可见以便单测重写为返回 mock。
     */
    JavaMailSender createSender(MailSenderSettings settings) {
        return buildSender(settings);
    }

    private static JavaMailSenderImpl buildSender(MailSenderSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        sender.setUsername(settings.username());
        sender.setPassword(settings.password());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth",
                (settings.username() != null && !settings.username().isBlank()) ? "true" : "false");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");

        switch (settings.security()) {
            case SSL -> {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.ssl.trust", settings.host());
            }
            case STARTTLS -> {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
                props.put("mail.smtp.ssl.trust", settings.host());
            }
            case NONE -> {
                // 明文连接：不开启任何 TLS 选项
            }
        }

        String socks = settings.socksProxy();
        if (socks != null && !socks.isBlank()) {
            int colon = socks.lastIndexOf(':');
            if (colon > 0 && colon < socks.length() - 1) {
                String host = socks.substring(0, colon).trim();
                String port = socks.substring(colon + 1).trim();
                if (!host.isEmpty() && port.chars().allMatch(Character::isDigit)) {
                    props.put("mail.smtp.socks.host", host);
                    props.put("mail.smtp.socks.port", port);
                }
            }
        }
        return sender;
    }

    private static List<String> parseRecipients(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static String joinRecipients(List<String> recipients) {
        return String.join(", ", recipients);
    }

    /**
     * 截取异常的可读摘要供日志 / GUI 显示。**绝不**回显密码：先遍历整个 cause 链合并 message，
     * 把传入的 SMTP 密码 / 用户名 / 授权码逐字替换为 {@code [redacted]}（JavaMail 与一些 SMTP 错误
     * 文案会原样回显客户端提交的认证字段），再截断到 500 字符。
     */
    static String safeMessage(Throwable t, String password) {
        if (t == null) {
            return "unknown";
        }
        String msg = collectMessages(t);
        msg = redactSecret(msg, password);
        if (msg.length() > 500) {
            msg = msg.substring(0, 500) + "…";
        }
        return msg;
    }

    /** 拼接异常链上所有 cause 的 message，便于一次性脱敏（JavaMail 嵌套异常的密码往往出现在 cause 上）。 */
    private static String collectMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 8) {
            String m = cur.getMessage();
            if (m == null || m.isBlank()) {
                m = cur.getClass().getSimpleName();
            }
            if (sb.length() > 0) sb.append("; ");
            sb.append(m);
            cur = cur.getCause();
            depth++;
        }
        return sb.length() == 0 ? t.getClass().getSimpleName() : sb.toString();
    }

    /** 把秘密原文逐字替换为 {@code [redacted]}；空 / 过短的秘密不替换以免误伤普通文本。 */
    private static String redactSecret(String text, String secret) {
        if (text == null || text.isEmpty()) return text;
        if (secret == null || secret.length() < 4) return text;
        return text.replace(secret, "[redacted]");
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    private String localized(String code, Object... args) {
        return messages.get(code, args);
    }

    /** 发送测试时透传给 GUI 的异常；message 已脱敏，可安全展示。 */
    public static class MailSendException extends Exception {
        public MailSendException(String message) {
            super(message);
        }

        public MailSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
