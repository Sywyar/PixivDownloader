package top.sywyar.pixivdownload.tts.narration.engine;

import java.util.EnumSet;
import java.util.Set;

/**
 * 多角色朗读的<b>可插拔 TTS 引擎适配器</b>。每个引擎把一条 {@link NarrationVoiceRequest} 在指定
 * {@link NarrationVoiceMode 模式} 下合成为音频字节。
 *
 * <p>Spring 自动发现全部实现并以 {@code List<NarrationVoiceEngine>} 注入（对齐 {@code List<PushChannel>} /
 * {@code List<NotificationSink>} 惯例），由 {@code NarrationEngineRegistry} 建立 id→引擎索引，
 * {@code NarrationAudioService} 按 {@code narration-tts.engine} 选用——新增引擎只需实现本接口并标注为 Spring 组件，
 * <b>不必</b>改派发器。
 *
 * <p>引擎<b>只认值对象</b>（{@link NarrationVoiceRequest} / {@link NarrationAudio}），不依赖分析层或数据库，
 * 失败一律抛 {@link NarrationVoiceException}（消息已脱敏、绝不含密钥）。
 */
public interface NarrationVoiceEngine {

    /** 引擎稳定标识（如 {@code voxcpm}），与 {@code narration-tts.engine} 取值对应。 */
    String id();

    /** 引擎当前是否可用（必要配置是否就绪，如已配置服务地址）。<b>纯配置检查、不触网</b>，用作合成前的快速门禁。 */
    boolean isAvailable();

    /**
     * 引擎当前是否<b>真实可达</b>：在 {@link #isAvailable() 配置就绪} 的基础上，再对后端服务做一次<b>轻量、短超时</b>
     * 的存活探测（如对 OpenAI 兼容服务 GET {@code /models}），确认服务在线、可服务。供「富感情朗读」入口的可用性
     * 判定使用——区别于 {@link #isAvailable()} 的纯配置检查，本方法可能产生一次网络请求。
     *
     * <p>默认实现回退到 {@link #isAvailable()}（不触网），未实现探测的引擎据此降级为「仅看配置」。
     */
    default boolean isReachable() {
        return isAvailable();
    }

    /**
     * 本引擎支持的合成模式集合（能力声明，对齐 {@code PushChannel.supportedFormats()} 惯例）。派发器据此把不支持的
     * 请求模式降级为 {@link NarrationVoiceMode#VOICE_DESIGN}。默认仅支持 {@link NarrationVoiceMode#VOICE_DESIGN}——
     * 最小引擎只需实现内联 voice-design；支持参考音克隆的引擎覆写本方法补上 {@link NarrationVoiceMode#CLONE} 等。
     */
    default Set<NarrationVoiceMode> supportedModes() {
        return EnumSet.of(NarrationVoiceMode.VOICE_DESIGN);
    }

    /**
     * 在指定 {@link NarrationVoiceMode 模式} 下合成一句话为音频。{@code req} 的文本已由派发器归一（非空），{@code mode}
     * 已在 {@link #supportedModes()} 内（不支持者已被降级）。
     *
     * @throws NarrationVoiceException 引擎不可用 / 连接失败 / 非 2xx / 空响应等（消息已脱敏）
     */
    NarrationAudio synthesize(NarrationVoiceMode mode, NarrationVoiceRequest req);
}
