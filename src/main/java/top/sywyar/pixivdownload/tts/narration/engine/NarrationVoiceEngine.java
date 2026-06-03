package top.sywyar.pixivdownload.tts.narration.engine;

/**
 * 多角色朗读的<b>可插拔 TTS 引擎适配器</b>。每个引擎把一条 {@link NarrationVoiceRequest} 合成为音频字节。
 *
 * <p>Spring 自动发现全部实现并以 {@code List<NarrationVoiceEngine>} 注入（对齐 {@code List<PushChannel>} /
 * {@code List<NotificationSink>} 惯例），由 {@code NarrationAudioService} 按 {@code narration-tts.engine} 选用——
 * 新增引擎只需实现本接口并标注为 Spring 组件，<b>不必</b>改派发器。
 *
 * <p>引擎<b>只认值对象</b>（{@link NarrationVoiceRequest} / {@link NarrationAudio}），不依赖分析层或数据库，
 * 失败一律抛 {@link NarrationVoiceException}（消息已脱敏、绝不含密钥）。
 */
public interface NarrationVoiceEngine {

    /** 引擎稳定标识（如 {@code voxcpm}），与 {@code narration-tts.engine} 取值对应。 */
    String id();

    /** 引擎当前是否可用（必要配置是否就绪，如已配置服务地址）。 */
    boolean isAvailable();

    /**
     * 合成一句话为音频。
     *
     * @throws NarrationVoiceException 引擎不可用 / 连接失败 / 非 2xx / 空响应等（消息已脱敏）
     */
    NarrationAudio synthesize(NarrationVoiceRequest req);
}
