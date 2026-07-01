package top.sywyar.pixivdownload.mail;

/**
 * 不可变的 SMTP 发信参数快照。
 * <p>
 * {@link MailService} 在 {@link MailService#send} / {@link MailService#sendTest} 中使用此对象按需创建
 * {@link org.springframework.mail.javamail.JavaMailSenderImpl}，从而支持热重载与 GUI 发送测试不写入磁盘
 * 的临时配置。
 *
 * @param host          SMTP 主机
 * @param port          SMTP 端口
 * @param security      加密方式
 * @param username      SMTP 用户名（通常是邮箱地址）
 * @param password      SMTP 密码 / 授权码 / 应用专用密码
 * @param from          发件人邮箱；调用方在此之前已完成"空则回退 username"的处理
 * @param to            收件人邮箱，逗号分隔多个
 * @param socksProxy    可选的 SOCKS 代理 {@code host:port}；为空表示直连
 * @param subjectPrefix 邮件主题前缀
 */
public record MailSenderSettings(
        String host,
        int port,
        MailSecurity security,
        String username,
        String password,
        String from,
        String to,
        String socksProxy,
        String subjectPrefix
) {
}
