package top.sywyar.pixivdownload.core.narration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Core narration TTS selection state.
 */
@Data
@Component
@ConfigurationProperties(prefix = "narration-tts")
public class NarrationTtsConfig {

    public static final String KEY_ENGINE = "narration-tts.engine";

    public static final String ENGINE_VOXCPM = "voxcpm";
    public static final String ENGINE_MIMO = "mimo";
    public static final String ENGINE_COSYVOICE = "cosyvoice";
    public static final String ENGINE_FISH = "fish";
    public static final String ENGINE_MINIMAX = "minimax";
    public static final String ENGINE_ELEVENLABS = "elevenlabs";
    public static final String ENGINE_QWEN = "qwen";
    public static final String ENGINE_DOUBAO = "doubao";

    private volatile String engine = ENGINE_VOXCPM;
}
