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
public class TtsPluginConfig {

    /** config.yaml 中的 key 常量，供首次安装 / 模板生成 / 测试代码复用。 */
    public static final String KEY_ENGINE = "narration-tts.engine";
    public static final String KEY_VOXCPM_BASE_URL = "narration-tts.voxcpm.base-url";
    public static final String KEY_VOXCPM_API_KEY = "narration-tts.voxcpm.api-key";
    public static final String KEY_VOXCPM_MODEL = "narration-tts.voxcpm.model";
    public static final String KEY_VOXCPM_VOICE = "narration-tts.voxcpm.voice";
    public static final String KEY_VOXCPM_RESPONSE_FORMAT = "narration-tts.voxcpm.response-format";
    public static final String KEY_VOXCPM_USE_PROXY = "narration-tts.voxcpm.use-proxy";
    public static final String KEY_VOXCPM_ENABLE_CLONE = "narration-tts.voxcpm.enable-clone";
    public static final String KEY_VOXCPM_CLONE_MODE = "narration-tts.voxcpm.clone-mode";
    public static final String KEY_VOXCPM_MAX_NEW_TOKENS = "narration-tts.voxcpm.max-new-tokens";

    /** 引擎 id 常量：VoxCPM 外部 OpenAI 兼容引擎。 */
    public static final String ENGINE_VOXCPM = "voxcpm";
    /** 引擎 id 常量：小米 MiMo v2.5 TTS（云 API，chat/completions 形态）。 */
    public static final String ENGINE_MIMO = "mimo";
    /** 引擎 id 常量：CosyVoice（自建，OpenAI 兼容 /audio/speech 形态）。 */
    public static final String ENGINE_COSYVOICE = "cosyvoice";
    /** 引擎 id 常量：Fish Audio（云 API，/v1/tts）。 */
    public static final String ENGINE_FISH = "fish";
    /** 引擎 id 常量：MiniMax（云 API，/t2a_v2）。 */
    public static final String ENGINE_MINIMAX = "minimax";
    /** 引擎 id 常量：ElevenLabs（云 API，/v1/text-to-speech/{voice_id}）。 */
    public static final String ENGINE_ELEVENLABS = "elevenlabs";
    /** 引擎 id 常量：Qwen3-TTS（阿里 DashScope / 百炼，多模态语音合成端点）。 */
    public static final String ENGINE_QWEN = "qwen";
    /** 引擎 id 常量：豆包 / Seed-TTS（字节火山引擎，/api/v1/tts）。 */
    public static final String ENGINE_DOUBAO = "doubao";

    /** {@code clone-mode} 取值：可控克隆（只送参考音、保留逐句情绪），默认值。 */
    public static final String CLONE_MODE_CONTROLLABLE = "controllable";
    /** {@code clone-mode} 取值：Hi-Fi 续写（送参考音 + 转录、最高保真但忽略逐句情绪）。 */
    public static final String CLONE_MODE_HIFI = "hifi";

    /** 当前使用的朗读引擎 id（默认 {@code voxcpm}）。 */
    private volatile String engine = ENGINE_VOXCPM;

    /** VoxCPM 引擎连接参数。 */
    private volatile Voxcpm voxcpm = new Voxcpm();

    /** 小米 MiMo v2.5 引擎连接参数。 */
    private volatile Mimo mimo = new Mimo();

    /** CosyVoice 引擎连接参数。 */
    private volatile Cosyvoice cosyvoice = new Cosyvoice();

    /** Fish Audio 引擎连接参数。 */
    private volatile Fish fish = new Fish();

    /** MiniMax 引擎连接参数。 */
    private volatile Minimax minimax = new Minimax();

    /** ElevenLabs 引擎连接参数。 */
    private volatile Elevenlabs elevenlabs = new Elevenlabs();

    /** Qwen3-TTS 引擎连接参数。 */
    private volatile Qwen qwen = new Qwen();

    /** 豆包 / Seed-TTS 引擎连接参数。 */
    private volatile Doubao doubao = new Doubao();

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

        /**
         * OpenAI 兼容 {@code /audio/speech} 的 {@code voice} 字段。它<b>不决定音色</b>——VoxCPM 音色由内联
         * voice-design 描述 / 参考音承载。VoxCPM 这类 voice-design / 克隆模型通常<b>没有任何预设音色</b>（服务端报
         * {@code Invalid voice '...'. Supported: none} 即指预设列表为空），此时带上任何 {@code voice} 值都会被拒，
         * 故<b>默认留空 → 不下发 {@code voice} 字段</b>；仅当某构建明确要求某个 voice 名时才填该名。
         */
        private volatile String voice = "";

        /** 音频输出格式（{@code wav} / {@code pcm}，默认 {@code wav}）。 */
        private volatile String responseFormat = "wav";

        /**
         * 本引擎的对外请求是否走已配置的 HTTP 代理（地址取
         * {@link top.sywyar.pixivdownload.config.ProxyConfig} 的 host:port）。VoxCPM 通常是本机 / 内网服务，
         * 默认 {@code false} 直连；独立于全局 {@code proxy.enabled}。
         */
        private volatile boolean useProxy = false;

        /**
         * 是否启用<b>参考音克隆</b>（角色配了标准音 / 参考音时用其音色克隆）。默认 {@code true}；置 {@code false} 时
         * 即便角色已有参考音也忽略、退回内联 voice-design（全局开关）。
         */
        private volatile boolean enableClone = true;

        /**
         * 克隆模式（仅在 {@link #enableClone} 开启且角色配了参考音时生效）：
         * <ul>
         *   <li>{@code controllable}（默认）：<b>可控克隆</b>，只送 {@code ref_audio}（不带转录），保住逐句
         *       {@code (delivery)} 情绪控制——克隆音色又能演情绪，行为与历史一致；</li>
         *   <li>{@code hifi}：<b>Hi-Fi 续写</b>，参考音<b>带转录</b>（{@code ref_text}，取自该角色参考音的已存转录）。
         *       最高保真，但 VoxCPM2 在该模式下<b>忽略</b>括号里的 {@code (delivery)} 控制指令；若某角色参考音没有转录
         *       则该句自动降回可控克隆。</li>
         * </ul>
         */
        private volatile String cloneMode = CLONE_MODE_CONTROLLABLE;

        /**
         * 生成 token 上限（兜底自回归停止符不触发导致的无限生成，含已知 bug vllm-omni#2896：{@code ref_audio}
         * 克隆停止符可能不触发）。<b>克隆与 voice-design 请求都下发</b>；{@code <=0} 表示不下发（不设上限）。默认
         * {@code 4096}：足够覆盖单句朗读，又能避免跑飞；若长句被截断可调高。
         */
        private volatile int maxNewTokens = 4096;
    }

    /**
     * 小米 MiMo v2.5 TTS（<b>云 API</b>，OpenAI 风格 {@code chat/completions} 形态）连接参数。MiMo 原生区分三个模型：
     * 预置音色 {@link #model}、自然语言描述音色 {@link #voiceDesignModel}、参考音克隆 {@link #voiceCloneModel}，
     * 由引擎按「是否配预置 voice / 是否有参考音」选用。鉴权用 {@code api-key} 头（非 Bearer），响应为 JSON、音频 base64。
     */
    @Data
    public static class Mimo {

        /** OpenAI 兼容基础地址（默认 {@code https://api.xiaomimimo.com/v1}）；引擎在其后拼接 {@code /chat/completions}。留空则不可用。 */
        private volatile String baseUrl = "https://api.xiaomimimo.com/v1";

        /** API Key（经 {@code api-key} 请求头发送）。绝不写入日志、失败摘要或任何响应正文。 */
        private volatile String apiKey = "";

        /** 预置音色模型名（默认 {@code mimo-v2.5-tts}）：仅在配置了 {@link #voice} 预置音色 id 时用于 VOICE_DESIGN。 */
        private volatile String model = "mimo-v2.5-tts";

        /** 自然语言描述音色模型名（默认 {@code mimo-v2.5-tts-voicedesign}）：VOICE_DESIGN 且未配预置音色时用，user 消息即音色画像。 */
        private volatile String voiceDesignModel = "mimo-v2.5-tts-voicedesign";

        /** 参考音克隆模型名（默认 {@code mimo-v2.5-tts-voiceclone}）：CLONE 模式用，参考音以 {@code data:URI} base64 放入 voice 字段。 */
        private volatile String voiceCloneModel = "mimo-v2.5-tts-voiceclone";

        /** 预置音色 id（如 {@code Chloe}/{@code 茉莉}）：非空 → VOICE_DESIGN 走预置音色模型 + 该 voice；留空 → 走描述音色模型。 */
        private volatile String voice = "";

        /** 音频输出格式（{@code wav} / {@code pcm16}，默认 {@code wav}）。 */
        private volatile String responseFormat = "wav";

        /** 本引擎请求是否走 HTTP 代理（地址取 {@code proxy.host:port}）。MiMo 为云服务，默认 {@code false} 直连，独立于全局 {@code proxy.enabled}。 */
        private volatile boolean useProxy = false;

        /** 是否启用参考音克隆（角色配了参考音时用克隆模型复刻音色）；关闭则即便有参考音也退回描述音色。 */
        private volatile boolean enableClone = true;
    }

    /**
     * CosyVoice（<b>自建外部服务</b>，OpenAI 兼容 {@code /audio/speech} 形态，二进制音频响应）连接参数。覆盖常见的
     * vox-box / Xinference 风格部署：自然语言风格走 {@code instruct} 字段、参考音克隆走 {@code reference_audio} /
     * {@code reference_text}。具体字段名随部署略有差异——后端只作 HTTP 客户端，绝不内嵌 Python / GPU 模型。
     */
    @Data
    public static class Cosyvoice {

        /** OpenAI 兼容基础地址（如 {@code http://127.0.0.1:8000/v1}）；引擎在其后拼接 {@code /audio/speech}。<b>留空表示不可用</b>。 */
        private volatile String baseUrl = "";

        /** API Key（自建服务默认可空）。绝不写入日志、失败摘要或任何响应正文。 */
        private volatile String apiKey = "";

        /** 模型名（默认 {@code CosyVoice2-0.5B}）。 */
        private volatile String model = "CosyVoice2-0.5B";

        /** 预置音色 / 角色（如 {@code 中文女}）：VOICE_DESIGN 时下发；不同部署预置名不同，留空则不下发。 */
        private volatile String voice = "";

        /** 音频输出格式（{@code wav} / {@code mp3} / {@code pcm}，默认 {@code wav}）。 */
        private volatile String responseFormat = "wav";

        /** 本引擎请求是否走 HTTP 代理。CosyVoice 多为本机 / 内网服务，默认 {@code false} 直连，独立于全局 {@code proxy.enabled}。 */
        private volatile boolean useProxy = false;

        /** 是否启用参考音克隆（以 {@code reference_audio} 送参考音 base64 + 可选 {@code reference_text}）；关闭则退回 instruct 描述。 */
        private volatile boolean enableClone = true;
    }

    /**
     * Fish Audio（<b>云 API</b>，{@code POST {base-url}/v1/tts}，二进制音频响应）连接参数。Fish 不做「纯文本描述生成音色」，
     * 音色来自 {@link #referenceId}（预先在 Fish 控制台创建 / 上传的声音模型 id），逐句情绪以行内 {@code (delivery)} 标记
     * 注入正文。鉴权用 {@code Authorization: Bearer}，模型经 {@code model} 请求头指定。
     */
    @Data
    public static class Fish {

        /** 基础地址（默认 {@code https://api.fish.audio}）；引擎在其后拼接 {@code /v1/tts}。留空则不可用。 */
        private volatile String baseUrl = "https://api.fish.audio";

        /** API Key（{@code Authorization: Bearer}）。绝不写入日志、失败摘要或任何响应正文。 */
        private volatile String apiKey = "";

        /** 模型（经 {@code model} 请求头发送，如 {@code s1} / {@code s2-pro}，默认 {@code s1}）。 */
        private volatile String model = "s1";

        /** 声音模型 id（{@code reference_id}）：Fish 控制台预创建的音色；为空则用账户默认音色。决定音色（timbre）。 */
        private volatile String referenceId = "";

        /** 音频输出格式（{@code mp3} / {@code wav} / {@code pcm} / {@code opus}，默认 {@code mp3}）。 */
        private volatile String format = "mp3";

        /** 本引擎请求是否走 HTTP 代理（地址取 {@code proxy.host:port}）。默认 {@code false}，独立于全局 {@code proxy.enabled}。 */
        private volatile boolean useProxy = false;
    }

    /**
     * MiniMax（<b>云 API</b>，{@code POST {base-url}/t2a_v2?GroupId=...}，JSON 响应、音频为 <b>hex</b> 字符串）连接参数。
     * 鉴权需<b>两个</b>凭证：{@code Authorization: Bearer <api-key>} 头 + URL 查询参数 {@link #groupId}（二者缺一会被
     * MiniMax 以鉴权 / group 不匹配，如 {@code 1004} 拒绝）。MiniMax 无逐请求自然语言音色 / 情绪通道：音色来自预置
     * {@link #voiceId}，情绪来自固定 {@link #emotion} 枚举（引擎会尝试按逐句 delivery 关键词映射，命中不到则用配置默认）。
     */
    @Data
    public static class Minimax {

        /** 基础地址（默认 {@code https://api.minimax.io/v1}）；引擎在其后拼接 {@code /t2a_v2}。留空则不可用。 */
        private volatile String baseUrl = "https://api.minimax.io/v1";

        /** API Key（{@code Authorization: Bearer}）。绝不写入日志、失败摘要或任何响应正文。 */
        private volatile String apiKey = "";

        /**
         * 账户 GroupId（MiniMax T2A v2 <b>必填</b>，作为 URL 查询参数 {@code ?GroupId=} 随每次请求下发、与 {@link #apiKey}
         * 配对）：控制台可见的 19 位数字，非密钥但唯一标识账户；留空则该引擎不可用。
         */
        private volatile String groupId = "";

        /** 模型名（默认 {@code speech-2.8-hd}）。 */
        private volatile String model = "speech-2.8-hd";

        /** 预置音色 id（如 {@code English_expressive_narrator}）：MiniMax <b>必填</b>，留空则该引擎不可用。 */
        private volatile String voiceId = "";

        /**
         * 情绪（{@code voice_setting.emotion}）：可选枚举 {@code happy/sad/angry/fearful/disgusted/surprised/calm/fluent/whisper}；
         * 留空则不下发（用模型默认）。引擎会优先按逐句 delivery 关键词映射到该枚举，未命中时回退本配置值。
         */
        private volatile String emotion = "";

        /** 音频输出格式（{@code audio_setting.format}：{@code mp3} / {@code wav} / {@code pcm} / {@code flac}，默认 {@code mp3}）。 */
        private volatile String format = "mp3";

        /** 采样率（{@code audio_setting.sample_rate}，默认 {@code 32000}）。 */
        private volatile int sampleRate = 32000;

        /** 本引擎请求是否走 HTTP 代理（地址取 {@code proxy.host:port}）。默认 {@code false}，独立于全局 {@code proxy.enabled}。 */
        private volatile boolean useProxy = false;
    }

    /**
     * ElevenLabs（<b>云 API</b>，{@code POST {base-url}/v1/text-to-speech/{voice_id}}，二进制音频响应）连接参数。ElevenLabs 不做
     * 「纯文本描述生成音色」，音色来自 {@link #voiceId}（控制台预建 / 克隆的声音），逐句情绪以 Eleven v3 的行内音频标签
     * （方括号自然语言指令）注入正文。鉴权用 {@code xi-api-key} 请求头（非 Bearer），输出格式经 query 参数 {@code output_format}。
     */
    @Data
    public static class Elevenlabs {

        /** 基础地址（默认 {@code https://api.elevenlabs.io}）；引擎在其后拼接 {@code /v1/text-to-speech/{voice_id}}。留空则不可用。 */
        private volatile String baseUrl = "https://api.elevenlabs.io";

        /** API Key（经 {@code xi-api-key} 请求头发送，非 Bearer）。绝不写入日志、失败摘要或任何响应正文。 */
        private volatile String apiKey = "";

        /** 模型 id（{@code model_id}，默认 {@code eleven_v3}；行内音频标签仅 v3 解析，旧模型如 {@code eleven_multilingual_v2} 会读出标签字面）。 */
        private volatile String model = "eleven_v3";

        /** 声音 id（{@code voice_id}）：ElevenLabs 控制台预建 / 克隆的声音，决定音色。<b>必填</b>，留空则该引擎不可用。 */
        private volatile String voiceId = "";

        /** 输出格式（query 参数 {@code output_format}，如 {@code mp3_44100_128} / {@code pcm_24000} / {@code opus_48000_128}，默认 {@code mp3_44100_128}）。 */
        private volatile String outputFormat = "mp3_44100_128";

        /** 本引擎请求是否走 HTTP 代理（地址取 {@code proxy.host:port}）。默认 {@code false}，独立于全局 {@code proxy.enabled}。 */
        private volatile boolean useProxy = false;
    }

    /**
     * Qwen3-TTS（阿里 <b>DashScope / 百炼</b> 云 API，多模态语音合成端点
     * {@code POST {base-url}/services/aigc/multimodal-generation/generation}）连接参数。无逐句自然语言音色通道：音色来自预置
     * {@link #voice}，可选 {@link #languageType} 提示文本语言；音频在响应里以<b>临时 URL</b> 返回（需再取一次）。鉴权用
     * {@code Authorization: Bearer}。
     */
    @Data
    public static class Qwen {

        /** 基础地址（默认 {@code https://dashscope.aliyuncs.com/api/v1}，北京区；国际区为 {@code https://dashscope-intl.aliyuncs.com/api/v1}）。留空则不可用。 */
        private volatile String baseUrl = "https://dashscope.aliyuncs.com/api/v1";

        /** API Key（{@code Authorization: Bearer}，即 DashScope API Key）。绝不写入日志、失败摘要或任何响应正文。 */
        private volatile String apiKey = "";

        /** 模型名（默认 {@code qwen3-tts-flash}）。 */
        private volatile String model = "qwen3-tts-flash";

        /** 预置音色（如 {@code Cherry} / {@code Ryan} / {@code Dylan} 等）；留空则不下发，用模型默认。 */
        private volatile String voice = "Cherry";

        /** 文本语言提示（{@code language_type}，如 {@code Chinese} / {@code English} / {@code Japanese}）；留空则不下发、自动判别。 */
        private volatile String languageType = "";

        /** 本引擎请求是否走 HTTP 代理（地址取 {@code proxy.host:port}）。默认 {@code false}，独立于全局 {@code proxy.enabled}。 */
        private volatile boolean useProxy = false;
    }

    /**
     * 豆包 / Seed-TTS（字节 <b>火山引擎</b>云 API，HTTP 非流式「query」端点 {@code POST {base-url}/api/v1/tts}）连接参数。鉴权 /
     * 音色为多字段：{@link #appId} + {@link #accessToken} + {@link #cluster} + 预置 {@link #voiceType}。无逐句自然语言音色通道：
     * 逐句情绪经火山 {@code emotion} 枚举控制（引擎按 delivery 关键词映射，未命中回退 {@link #emotion}）。鉴权用火山特有的
     * {@code Authorization: Bearer;<access-token>}（带分号）。响应是 JSON、音频为 base64。
     */
    @Data
    public static class Doubao {

        /** 基础地址（默认 {@code https://openspeech.bytedance.com}）；引擎在其后拼接 {@code /api/v1/tts}。留空则不可用。 */
        private volatile String baseUrl = "https://openspeech.bytedance.com";

        /** 应用 appid（火山语音控制台）。 */
        private volatile String appId = "";

        /** 访问令牌 access-token（经 {@code Authorization: Bearer;<token>} 发送）。绝不写入日志、失败摘要或任何响应正文。 */
        private volatile String accessToken = "";

        /** 业务集群（默认 {@code volcano_tts}）。 */
        private volatile String cluster = "volcano_tts";

        /** 预置音色 id（{@code voice_type}，如 {@code zh_female_wanqudashu_moon_bigtts}）。<b>必填</b>，留空则该引擎不可用。 */
        private volatile String voiceType = "";

        /** 音频编码 / 格式（{@code mp3} / {@code wav} / {@code pcm} / {@code ogg_opus}，默认 {@code mp3}）。 */
        private volatile String encoding = "mp3";

        /**
         * 可选固定情绪（如 {@code happy/sad/angry/scare/hate/surprise/comfort} 等火山支持的枚举）；留空则不下发。引擎会优先按逐句
         * delivery 关键词映射到火山情绪枚举，未命中时回退本配置值。情绪仅对支持情绪的音色（如 {@code *_bigtts}）生效。
         */
        private volatile String emotion = "";

        /** 本引擎请求是否走 HTTP 代理（地址取 {@code proxy.host:port}）。默认 {@code false}，独立于全局 {@code proxy.enabled}。 */
        private volatile boolean useProxy = false;
    }
}
