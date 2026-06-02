package top.sywyar.pixivdownload.push;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.push.channel.bark.BarkConfig;

/**
 * 推送框架的<b>总开关</b>配置，映射 {@code config.yaml} 中的 {@code push.enabled}。
 * <p>
 * 各通道的配置由各自独立的 {@code @ConfigurationProperties}（前缀 {@code push.<id>}，如
 * {@link BarkConfig}）承载，与本类解耦——本类只决定"推送功能是否启用"。
 * {@code enabled=false} 时 {@link PushService} 直接跳过全部通道。
 * <p>
 * 字段使用 {@code volatile}，与 {@link top.sywyar.pixivdownload.config.ProxyConfig} /
 * {@link top.sywyar.pixivdownload.mail.MailConfig} 风格一致，便于热重载时安全地被多线程读取。
 */
@Data
@Component
@ConfigurationProperties(prefix = "push")
public class PushConfig {

    /** config.yaml 中的 key 常量，供首次安装 / 模板生成 / 测试代码复用。 */
    public static final String KEY_ENABLED = "push.enabled";

    /** 推送总开关；关闭时所有通道都不发送。 */
    private volatile boolean enabled = false;
}
