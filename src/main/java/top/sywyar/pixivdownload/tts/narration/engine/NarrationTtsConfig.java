package top.sywyar.pixivdownload.tts.narration.engine;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 多角色朗读（AI 听小说）TTS 引擎配置。映射 {@code config.yaml} 中的 {@code narration-tts.*} 前缀。
 * <p>
 * {@link #engine} 选定当前使用的引擎（自动发现的 {@code List<NarrationVoiceEngine>} 按 id 匹配）；各引擎的连接
 * 参数放在以引擎 id 命名的子前缀下（如 {@code narration-tts.voxcpm.*}）。是否走 HTTP 代理由各引擎自身的
 * {@code use-proxy} 决定（per-config，仿 {@code ai.use-proxy} / {@code push.*.use-proxy}），独立于全局
 * {@code proxy.enabled}。
 * <p>
 * 字段全部 {@code volatile}，与 {@link top.sywyar.pixivdownload.ai.AiConfig} /
 * {@link top.sywyar.pixivdownload.config.ProxyConfig} 风格一致，便于热重载时安全地被多线程读取。本类只承载
 * 配置数据，请求 / 解析逻辑见各 {@link NarrationVoiceEngine} 实现。
 */
@Data
@Component
@ConfigurationProperties(prefix = "narration-tts")
public class NarrationTtsConfig {

    /** config.yaml 中的 key 常量，供首次安装 / 模板生成 / 测试代码复用。 */
    public static final String KEY_ENGINE = "narration-tts.engine";
    public static final String KEY_VOXCPM_BASE_URL = "narration-tts.voxcpm.base-url";
    public static final String KEY_VOXCPM_API_KEY = "narration-tts.voxcpm.api-key";
    public static final String KEY_VOXCPM_MODEL = "narration-tts.voxcpm.model";
    public static final String KEY_VOXCPM_RESPONSE_FORMAT = "narration-tts.voxcpm.response-format";
    public static final String KEY_VOXCPM_USE_PROXY = "narration-tts.voxcpm.use-proxy";

    /** 引擎 id 常量：VoxCPM 外部 OpenAI 兼容引擎。 */
    public static final String ENGINE_VOXCPM = "voxcpm";

    /** 当前使用的朗读引擎 id（默认 {@code voxcpm}）。 */
    private volatile String engine = ENGINE_VOXCPM;

    /** VoxCPM 引擎连接参数。 */
    private volatile Voxcpm voxcpm = new Voxcpm();

    /**
     * VoxCPM（vLLM-Omni 的 OpenAI 兼容音频接口）连接参数。VoxCPM 是<b>外部服务</b>（用户自行
     * {@code vllm serve}），后端只作 HTTP 客户端，绝不内嵌 Python / GPU 模型。
     */
    @Data
    public static class Voxcpm {

        /**
         * OpenAI 兼容端点的基础地址，如 {@code http://127.0.0.1:8000/v1}。请求时由引擎在其后拼接
         * {@code /audio/speech}（自动处理结尾斜杠）。<b>留空表示引擎不可用</b>。
         */
        private volatile String baseUrl = "";

        /** API Key（vLLM 默认可空）。绝不写入日志、失败摘要或任何响应正文。 */
        private volatile String apiKey = "";

        /** 模型名（默认 {@code openbmb/VoxCPM2}）。 */
        private volatile String model = "openbmb/VoxCPM2";

        /** 音频输出格式（{@code wav} / {@code pcm}，默认 {@code wav}）。 */
        private volatile String responseFormat = "wav";

        /**
         * 本引擎的对外请求是否走已配置的 HTTP 代理（地址取
         * {@link top.sywyar.pixivdownload.config.ProxyConfig} 的 host:port）。VoxCPM 通常是本机 / 内网服务，
         * 默认 {@code false} 直连；独立于全局 {@code proxy.enabled}。
         */
        private volatile boolean useProxy = false;
    }
}
