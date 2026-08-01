package top.sywyar.pixivdownload.push;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.push.channel.bark.BarkConfig;

/**
 * 推送框架的<b>总开关</b>配置，绑定插件子上下文中的 {@code push.enabled}。普通字段以
 * {@code config/plugins/push.properties} 为权威，密码 / 密钥字段由
 * {@code config/credentials/push.properties} 专属属性源提供。
 * <p>
 * 各通道的配置由各自独立的 {@code @ConfigurationProperties}（前缀 {@code push.<id>}，如
 * {@link BarkConfig}）承载，与本类解耦——本类只决定"推送功能是否启用"。
 * {@code enabled=false} 时通知 sink 直接跳过派发。
 * <p>
 * 字段使用 {@code volatile}，便于热重载时安全地被多线程读取。
 */
@Data
@Component
@ConfigurationProperties(prefix = "push")
public class PushConfig {

    /** 插件配置属性键常量，供 contribution、绑定与测试代码复用。 */
    public static final String KEY_ENABLED = "push.enabled";

    /** 推送总开关；关闭时所有通道都不发送。 */
    private volatile boolean enabled = false;
}
