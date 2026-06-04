package top.sywyar.pixivdownload.tts.narration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationTtsConfig;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceEngine;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceException;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceRequest;

import java.util.List;

/**
 * 多角色朗读音频合成服务：注入自动发现的 {@code List<NarrationVoiceEngine>} + {@link NarrationTtsConfig}，
 * 按 {@code narration-tts.engine} 选用引擎，把一条朗读脚本行（或裸文本 + 音色描述）合成为音频字节。
 *
 * <p>本服务是消费 {@link NarrationScript.Line} 的<b>引擎适配层入口</b>：把脚本行映射成语言无关的
 * {@link NarrationVoiceRequest} 后交给选中引擎，引擎与分析层 / DB 解耦。选中引擎不可用时抛
 * {@link NarrationVoiceException}（暂无降级链——Edge 等其它引擎是后续步骤）。
 */
@Service
@Slf4j
public class NarrationAudioService {

    private final List<NarrationVoiceEngine> engines;
    private final NarrationTtsConfig config;
    private final AppMessages messages;

    public NarrationAudioService(List<NarrationVoiceEngine> engines,
                                 NarrationTtsConfig config,
                                 AppMessages messages) {
        this.engines = engines;
        this.config = config;
        this.messages = messages;
    }

    /**
     * 合成脚本中的一行：用 line 的文本 / 已合成 Control Instruction / delivery 组
     * {@link NarrationVoiceRequest}（gender / age 本次留空）后交给选中引擎。带上由上游（持久化层）解析好的
     * 参考音 {@code referenceVoice}（可为空）——引擎据其是否存在决定走可控克隆还是内联 voice-design，本服务与
     * 引擎都不直接读盘 / 查库，保持解耦。
     *
     * @param line           朗读脚本行（{@link NarrationScript.Line}）
     * @param referenceVoice 该说话人的参考音（可为空）；非空且有音频时引擎走克隆
     * @param localeHint     文本 / 界面语言提示（可为空；VoxCPM 内联不使用）
     */
    public NarrationAudio synthesizeLine(NarrationScript.Line line,
                                         top.sywyar.pixivdownload.tts.narration.engine.NarrationReferenceVoice referenceVoice,
                                         String localeHint) {
        if (line == null) {
            throw new NarrationVoiceException(messages.get("narration.tts.error.empty-text"), null);
        }
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                line.text(), line.controlInstruction(), line.delivery(),
                null, null, 0L, line.speakerId(), localeHint, referenceVoice);
        return synthesize(req);
    }

    /**
     * 便捷入口：直接用裸文本 + 音色描述合成（便于联调 / 预览）。
     *
     * @param text               要朗读的文本
     * @param controlInstruction 音色描述（可为空）
     * @param localeHint         语言提示（可为空）
     */
    public NarrationAudio synthesize(String text, String controlInstruction, String localeHint) {
        return synthesize(NarrationVoiceRequest.of(text, controlInstruction, localeHint));
    }

    /**
     * 当前 {@code narration-tts.engine} 选定的引擎是否存在且<b>真实可用</b>：除引擎配置就绪外，还会对后端服务做一次
     * 短超时存活探测（见 {@link NarrationVoiceEngine#isReachable()}，如 VoxCPM 的 GET {@code /models}）。供前端按
     * 可用性显隐 / 禁用「富感情朗读」入口，避免后端未配置 / 已宕机时仍可点开并触发分析。
     */
    public boolean isEngineAvailable() {
        NarrationVoiceEngine engine = selectEngine();
        return engine != null && engine.isReachable();
    }

    private NarrationAudio synthesize(NarrationVoiceRequest req) {
        NarrationVoiceEngine engine = selectEngine();
        if (engine == null) {
            log.warn(messages.getForLog("narration.tts.log.engine.not-found", config.getEngine(), engines.size()));
            throw new NarrationVoiceException(
                    messages.get("narration.tts.error.engine-not-found", config.getEngine()), null);
        }
        if (!engine.isAvailable()) {
            log.warn(messages.getForLog("narration.tts.log.engine.unavailable", engine.id()));
            throw new NarrationVoiceException(messages.get("narration.tts.error.unavailable"), null);
        }
        if (log.isDebugEnabled()) {
            log.debug(messages.getForLog("narration.tts.log.engine.selected", engine.id()));
        }
        return engine.synthesize(req);
    }

    /** 按 {@code narration-tts.engine} 在自动发现的引擎中匹配（大小写不敏感）；无匹配返回 null。 */
    private NarrationVoiceEngine selectEngine() {
        String id = config.getEngine();
        if (id == null || id.isBlank()) {
            return null;
        }
        for (NarrationVoiceEngine engine : engines) {
            if (id.equalsIgnoreCase(engine.id())) {
                return engine;
            }
        }
        return null;
    }
}
