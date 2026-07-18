package top.sywyar.pixivdownload.tts;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** TTS 插件读取的邀请访客在线合成限流配置。 */
@Data
@ConfigurationProperties(prefix = "guest-invite")
public class TtsGuestRateLimitConfig {

    /** 每个宿主验证 subject 每分钟允许的在线合成请求数；非正数表示不限制。 */
    private volatile int ttsRequestLimitMinute = 30;
}
