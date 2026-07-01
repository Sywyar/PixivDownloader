package top.sywyar.pixivdownload.setup.guest;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 邀请访客（持有 {@code pixiv_invite_token} 会话）的限流配置。
 *
 * <p>访客邀请独立于 {@code solo} / {@code multi} 运行模式——这些限制在两种模式下都作用于邀请访客，
 * 因此放在独立的 {@code guest-invite.*} 前缀下，而不是 {@code multi-mode.*}（后者只服务多人模式游客）。
 * 管理员与 {@code solo} 拥有者永不受这些限制约束。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "guest-invite")
public class GuestInviteConfig implements GuestInviteRateLimitSettings {

    /** 邀请访客每邀请码每分钟最大 API 请求次数（0 = 不限制） */
    private volatile int requestLimitMinute = 300;

    /** 邀请访客每邀请码每分钟最大静态资源请求次数（0 = 不限制） */
    private volatile int staticResourceRequestLimitMinute = 1200;

    /** 邀请访客每邀请码每分钟最大在线 TTS（语音合成）请求次数（0 = 不限制） */
    private volatile int ttsRequestLimitMinute = 30;
}
