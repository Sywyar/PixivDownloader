package top.sywyar.pixivdownload.tts.narration.engine;

import java.util.EnumSet;
import java.util.Set;

/**
 * 多角色朗读的<b>可插拔 TTS 引擎适配器</b>。每个引擎把一条 {@link NarrationVoiceRequest} 在指定
 * {@link NarrationVoiceMode 模式} 下合成为音频字节。
 *
 * <p>宿主按稳定 id 注册活动实现并由调用方选择；新增引擎只需实现本接口，不改变契约或派发逻辑。
 *
 * <p>引擎<b>只认值对象</b>（{@link NarrationVoiceRequest} / {@link NarrationAudio}），不依赖分析层或数据库，
 * 失败一律抛 {@link NarrationVoiceException}（消息已脱敏、绝不含密钥）。
 */
public interface NarrationVoiceEngine {

    /** 引擎稳定标识；配置存储与选择机制由消费者拥有。 */
    String id();

    /** 引擎当前是否可用（必要配置是否就绪，如已配置服务地址）。<b>纯配置检查、不触网</b>，用作合成前的快速门禁。 */
    boolean isAvailable();

    /**
     * 引擎当前是否真实可达：在 {@link #isAvailable() 配置就绪} 的基础上，可对后端服务做一次轻量、短超时
     * 的存活探测。区别于 {@link #isAvailable()} 的纯配置检查，本方法可能产生网络请求。
     *
     * <p>默认实现回退到 {@link #isAvailable()}（不触网），未实现探测的引擎据此降级为「仅看配置」。
     */
    default boolean isReachable() {
        return isAvailable();
    }

    /**
     * 本引擎支持的合成模式集合。派发器据此把不支持的
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
