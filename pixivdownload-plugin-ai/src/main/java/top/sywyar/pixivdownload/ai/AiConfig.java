package top.sywyar.pixivdownload.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 大语言模型（LLM）接入配置。映射 {@code config.yaml} 中的 {@code ai.*} 前缀。
 * <p>
 * 框架统一走 <b>OpenAI Chat Completions 兼容协议</b>（{@code POST {base-url}/chat/completions} +
 * {@code Authorization: Bearer <api-key>}）。各家厂商（OpenAI / DeepSeek / 通义 / 智谱 / Claude / Gemini …）
 * 都以各自的 OpenAI 兼容端点接入，由 {@link top.sywyar.pixivdownload.ai.preset.AiPresetRegistry} 提供预设。
 * <p>
 * 字段全部使用 {@code volatile}，与 {@link top.sywyar.pixivdownload.mail.MailConfig} /
 * {@link top.sywyar.pixivdownload.config.OutboundProxySettings} 风格一致，以便热重载时安全地被多线程读取。本类只承载
 * 配置数据，请求 / 解析逻辑见 {@link AiService}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiConfig {

    /** config.yaml 中的 key 常量，供首次安装 / 模板生成 / 测试代码复用。 */
    public static final String KEY_ENABLED = "ai.enabled";
    public static final String KEY_BASE_URL = "ai.base-url";
    public static final String KEY_API_KEY = "ai.api-key";
    public static final String KEY_MODEL = "ai.model";
    public static final String KEY_USE_PROXY = "ai.use-proxy";

    /** 是否启用 AI 调用总开关；关闭时 {@link AiService#chat} 直接拒绝。 */
    private volatile boolean enabled = false;

    /**
     * OpenAI 兼容端点的基础地址，如 {@code https://api.openai.com/v1}、{@code https://api.deepseek.com}。
     * 请求时由 {@link AiService} 在其后拼接 {@code /chat/completions}（自动处理结尾斜杠）。
     */
    private volatile String baseUrl = "";

    /** API Key。绝不写入日志、失败摘要或任何响应正文。 */
    private volatile String apiKey = "";

    /** 模型名，如 {@code gpt-4o-mini}、{@code deepseek-chat}、{@code qwen-plus}。 */
    private volatile String model = "";

    /**
     * 是否让本配置的对外请求走已配置的 HTTP 代理（地址取 {@link top.sywyar.pixivdownload.config.OutboundProxySettings}
     * 的 host:port）。海外厂商通常需要开启、国内厂商无需开启——按预设给出合理默认值，独立于全局 {@code proxy.enabled}。
     */
    private volatile boolean useProxy = false;

    /** 把当前配置打包成不可变 {@link AiClientSettings}，用于请求时按需读取。 */
    public AiClientSettings toClientSettings() {
        return new AiClientSettings(baseUrl, apiKey, model, useProxy);
    }
}
