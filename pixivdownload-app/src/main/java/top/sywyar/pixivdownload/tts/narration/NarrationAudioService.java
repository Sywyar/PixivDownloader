package top.sywyar.pixivdownload.tts.narration;

import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationEngineRegistry;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationReferenceVoice;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationSilence;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationSpeechText;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationTtsConfig;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceEngine;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceException;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceMode;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceRequest;

/**
 * 多角色朗读音频合成的<b>集中调用层</b>：注入 {@link NarrationEngineRegistry} + {@link NarrationTtsConfig}，按
 * {@code narration-tts.engine} 选用引擎，把一条朗读脚本行（或裸文本 + 音色描述）在<b>显式 {@link NarrationVoiceMode}</b>
 * 下合成为音频字节。
 *
 * <p>核心入口 {@link #synthesize(NarrationVoiceMode, NarrationVoiceRequest)} 统一做三件引擎无关的事：
 * <ol>
 *   <li><b>文本归一</b>（{@link NarrationSpeechText#normalize}）：省略号 / 悬挂标点 → 句号，纯标点 / 无可发音内容 →
 *       空文本，直接抛 {@code empty-text}、<b>绝不调任何引擎</b>；否则用归一后的文本重建请求。</li>
 *   <li><b>选引擎</b>：缺失 → {@code engine-not-found}；{@code isAvailable()} 关 → {@code unavailable}。</li>
 *   <li><b>能力降级</b>：请求模式不在 {@link NarrationVoiceEngine#supportedModes()} 内 → 退回
 *       {@link NarrationVoiceMode#VOICE_DESIGN}（记一条 debug）。</li>
 * </ol>
 * 便捷入口在调用处即显式表达模式：{@link #synthesizeVoiceDesign} 给预览 / 种子音；{@link #synthesizeLine} 按「有无
 * 参考音」数据驱动选 {@code CLONE}/{@code VOICE_DESIGN}（Hi-Fi 由引擎按配置升级）。引擎与分析层 / DB 解耦，
 * 不可用时抛 {@link NarrationVoiceException}（暂无降级链——Edge 等其它引擎是后续步骤）。
 */
@Service
@Slf4j
public class NarrationAudioService {

    private final NarrationEngineRegistry registry;
    private final NarrationTtsConfig config;
    private final AppMessages messages;

    /** 进程内仅记一次的「beta 功能」提示守卫：首次真正发起合成时告知该功能可用但尚不稳定。 */
    private final AtomicBoolean betaNoticeLogged = new AtomicBoolean(false);

    public NarrationAudioService(NarrationEngineRegistry registry,
                                 NarrationTtsConfig config,
                                 AppMessages messages) {
        this.registry = registry;
        this.config = config;
        this.messages = messages;
    }

    /**
     * 合成脚本中的一行：用 line 的文本 / 已合成 Control Instruction / delivery 组 {@link NarrationVoiceRequest}（gender /
     * age 本次留空），带上由上游（持久化层）解析好的参考音 {@code referenceVoice}（可为空）后<b>数据驱动选模式</b>：
     * 有可用参考音 → {@link NarrationVoiceMode#CLONE}，否则 {@link NarrationVoiceMode#VOICE_DESIGN}；是否升级为
     * Hi-Fi 续写由引擎按 {@code clone-mode} 配置自行决定。本服务与引擎都不直接读盘 / 查库，保持解耦。
     *
     * <p>若该行<b>归一后为空文本</b>（纯标点 / 省略号等无可发音内容，如独立成段的「……」），返回
     * {@link NarrationSilence#shortPause() 短静音}作为可跳过的停顿、<b>绝不调引擎</b>，避免这类行抛异常中断整条朗读。
     *
     * @param line           朗读脚本行（{@link NarrationScript.Line}）
     * @param referenceVoice 该说话人的参考音（可为空）；非空且有音频时走克隆
     * @param localeHint     文本 / 界面语言提示（可为空；VoxCPM 内联不使用）
     */
    public NarrationAudio synthesizeLine(NarrationScript.Line line,
                                         NarrationReferenceVoice referenceVoice,
                                         String localeHint) {
        if (line == null) {
            throw new NarrationVoiceException(messages.get("narration.tts.error.empty-text"), null);
        }
        // 纯标点 / 无可发音内容的行（如小说里独立成段的「……」「！？」）归一后为空、无法合成：返回一段短静音作为
        // 可跳过的停顿，让前端播放链路续到下一句，而不是抛 502 中断整条朗读（这类行被断句层按 paragraphIndex 对齐保留）。
        if (NarrationSpeechText.normalize(line.text()).isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug(messages.getForLog("narration.tts.log.line.skip-blank", line.index()));
            }
            return NarrationSilence.shortPause();
        }
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                line.text(), line.controlInstruction(), line.delivery(),
                null, null, 0L, line.speakerId(), localeHint, referenceVoice);
        NarrationVoiceMode mode = referenceVoice != null && referenceVoice.hasAudio()
                ? NarrationVoiceMode.CLONE : NarrationVoiceMode.VOICE_DESIGN;
        return synthesize(mode, req);
    }

    /**
     * 便捷入口：用裸文本 + 音色描述走 {@link NarrationVoiceMode#VOICE_DESIGN}（无参考音）。用于试听预览与角色「标准音 /
     * 种子音」生成。
     *
     * @param text               要朗读的文本
     * @param controlInstruction 音色描述（可为空）
     * @param localeHint         语言提示（可为空）
     */
    public NarrationAudio synthesizeVoiceDesign(String text, String controlInstruction, String localeHint) {
        return synthesize(NarrationVoiceMode.VOICE_DESIGN,
                NarrationVoiceRequest.of(text, controlInstruction, localeHint));
    }

    /**
     * 核心入口：在指定模式下合成一条请求。先归一文本（空 → 抛 {@code empty-text}、不调引擎），再选引擎、按能力降级模式，
     * 最后交给引擎合成。
     */
    public NarrationAudio synthesize(NarrationVoiceMode mode, NarrationVoiceRequest req) {
        if (betaNoticeLogged.compareAndSet(false, true)) {
            log.info(messages.getForLog("narration.tts.log.beta"));
        }
        String normalized = NarrationSpeechText.normalize(req == null ? null : req.text());
        if (normalized.isEmpty()) {
            throw new NarrationVoiceException(messages.get("narration.tts.error.empty-text"), null);
        }
        NarrationVoiceEngine engine = registry.selected(config.getEngine()).orElse(null);
        if (engine == null) {
            log.warn(messages.getForLog("narration.tts.log.engine.not-found", config.getEngine(), registry.count()));
            throw new NarrationVoiceException(
                    messages.get("narration.tts.error.engine-not-found", config.getEngine()), null);
        }
        if (!engine.isAvailable()) {
            log.warn(messages.getForLog("narration.tts.log.engine.unavailable", engine.id()));
            throw new NarrationVoiceException(messages.get("narration.tts.error.unavailable"), null);
        }
        NarrationVoiceMode effectiveMode = mode;
        if (!engine.supportedModes().contains(mode)) {
            if (log.isDebugEnabled()) {
                log.debug(messages.getForLog("narration.tts.log.mode.downgrade", mode, engine.id()));
            }
            effectiveMode = NarrationVoiceMode.VOICE_DESIGN;
        }
        if (log.isDebugEnabled()) {
            log.debug(messages.getForLog("narration.tts.log.engine.selected", engine.id()));
        }
        return engine.synthesize(effectiveMode, req.withText(normalized));
    }

    /**
     * 当前 {@code narration-tts.engine} 选定的引擎是否存在且<b>真实可用</b>：除引擎配置就绪外，还会对后端服务做一次
     * 短超时存活探测（见 {@link NarrationVoiceEngine#isReachable()}，如 VoxCPM 的 GET {@code /models}）。供前端按
     * 可用性显隐 / 禁用「富感情朗读」入口，避免后端未配置 / 已宕机时仍可点开并触发分析。
     */
    public boolean isEngineAvailable() {
        return registry.selected(config.getEngine())
                .map(NarrationVoiceEngine::isReachable)
                .orElse(false);
    }
}
