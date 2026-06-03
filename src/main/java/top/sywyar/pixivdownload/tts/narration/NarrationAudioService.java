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
     * {@link NarrationVoiceRequest}（gender / age 本次留空）后交给选中引擎。
     *
     * @param line       朗读脚本行（{@link NarrationScript.Line}）
     * @param localeHint 文本 / 界面语言提示（可为空；VoxCPM 内联不使用）
     */
    public NarrationAudio synthesizeLine(NarrationScript.Line line, String localeHint) {
        if (line == null) {
            throw new NarrationVoiceException(messages.get("narration.tts.error.empty-text"), null);
        }
        NarrationVoiceRequest req = new NarrationVoiceRequest(
                line.text(), line.controlInstruction(), line.delivery(),
                null, null, 0L, line.speakerId(), localeHint);
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

    private NarrationAudio synthesize(NarrationVoiceRequest req) {
        NarrationVoiceEngine engine = selectEngine();
        if (engine == null) {
            throw new NarrationVoiceException(
                    messages.get("narration.tts.error.engine-not-found", config.getEngine()), null);
        }
        if (!engine.isAvailable()) {
            throw new NarrationVoiceException(messages.get("narration.tts.error.unavailable"), null);
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
