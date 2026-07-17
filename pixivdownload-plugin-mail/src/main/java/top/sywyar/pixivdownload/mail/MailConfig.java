package top.sywyar.pixivdownload.mail;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SMTP 邮件发送配置。映射 {@code config.yaml} 中的 {@code mail.*} 前缀。
 * <p>
 * 字段全部使用 {@code volatile}，与 {@link top.sywyar.pixivdownload.config.OutboundProxySettings} 风格一致，
 * 以便热重载时安全地被多线程读取。本类只承载配置数据，发信逻辑见 {@link MailService}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mail")
public class MailConfig {

    /** config.yaml 中的 key 常量，供首次安装 / 模板生成 / 测试代码复用。 */
    public static final String KEY_ENABLED = "mail.enabled";
    public static final String KEY_HOST = "mail.host";
    public static final String KEY_PORT = "mail.port";
    public static final String KEY_SECURITY = "mail.security";
    public static final String KEY_USERNAME = "mail.username";
    public static final String KEY_PASSWORD = "mail.password";
    public static final String KEY_FROM = "mail.from";
    public static final String KEY_TO = "mail.to";
    public static final String KEY_SOCKS_PROXY = "mail.socks-proxy";
    public static final String KEY_SUBJECT_PREFIX = "mail.subject-prefix";

    public static final int DEFAULT_PORT = 587;
    public static final String DEFAULT_SECURITY = "starttls";
    public static final String DEFAULT_SUBJECT_PREFIX = "[PixivDownloader]";

    /** 是否启用邮件发送总开关；关闭时 {@link MailService#send} 直接跳过。 */
    private volatile boolean enabled = false;

    private volatile String host = "";

    private volatile int port = DEFAULT_PORT;

    /** 加密方式：{@code none} / {@code ssl} / {@code starttls}。 */
    private volatile String security = DEFAULT_SECURITY;

    private volatile String username = "";

    /** SMTP 密码 / 授权码。绝不写入日志或失败摘要。 */
    private volatile String password = "";

    /** 发件人地址；为空时由 {@link MailService} 回退到 {@link #username}。 */
    private volatile String from = "";

    /** 收件人地址；支持逗号分隔多个。 */
    private volatile String to = "";

    /** 可选 SOCKS 代理，格式 {@code host:port}；为空表示直连。 */
    private volatile String socksProxy = "";

    /** 邮件主题前缀；发信时拼接在模板 subject 前。 */
    private volatile String subjectPrefix = DEFAULT_SUBJECT_PREFIX;

    /** 把当前配置打包成不可变 {@link MailSenderSettings}，用于发信时按需自建 JavaMailSender。 */
    public MailSenderSettings toSenderSettings() {
        return new MailSenderSettings(
                host,
                port,
                MailSecurity.parse(security),
                username,
                password,
                effectiveFrom(),
                to,
                socksProxy,
                subjectPrefix == null ? DEFAULT_SUBJECT_PREFIX : subjectPrefix);
    }

    private String effectiveFrom() {
        return (from == null || from.isBlank()) ? username : from;
    }
}
