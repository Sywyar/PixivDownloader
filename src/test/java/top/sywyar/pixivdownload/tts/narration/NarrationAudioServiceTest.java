package top.sywyar.pixivdownload.tts.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationTtsConfig;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceEngine;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceException;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("多角色朗读音频合成服务")
class NarrationAudioServiceTest {

    private static final AppMessages MESSAGES = TestI18nBeans.appMessages();

    @Test
    @DisplayName("按 narration-tts.engine 选中引擎并合成")
    void selectsConfiguredEngineAndSynthesizes() {
        NarrationVoiceEngine voxcpm = availableEngine("voxcpm");
        NarrationAudio audio = new NarrationAudio(new byte[]{1}, "audio/wav");
        when(voxcpm.synthesize(any())).thenReturn(audio);
        NarrationAudioService service = new NarrationAudioService(List.of(voxcpm), config("voxcpm"), MESSAGES);

        NarrationAudio out = service.synthesize("text", "ci", null);

        assertThat(out).isSameAs(audio);
        verify(voxcpm).synthesize(any(NarrationVoiceRequest.class));
    }

    @Test
    @DisplayName("选中引擎不可用时抛受控异常且不合成")
    void unavailableEngineThrows() {
        NarrationVoiceEngine voxcpm = mock(NarrationVoiceEngine.class);
        when(voxcpm.id()).thenReturn("voxcpm");
        when(voxcpm.isAvailable()).thenReturn(false);
        NarrationAudioService service = new NarrationAudioService(List.of(voxcpm), config("voxcpm"), MESSAGES);

        assertThatThrownBy(() -> service.synthesize("t", "", null))
                .isInstanceOf(NarrationVoiceException.class);
        verify(voxcpm, never()).synthesize(any());
    }

    @Test
    @DisplayName("配置的引擎 id 不存在时抛受控异常")
    void engineNotFoundThrows() {
        NarrationAudioService service = new NarrationAudioService(List.of(), config("unknown"), MESSAGES);

        assertThatThrownBy(() -> service.synthesize("t", "", null))
                .isInstanceOf(NarrationVoiceException.class);
    }

    @Test
    @DisplayName("synthesizeLine：用脚本行的文本 / Control Instruction / delivery / speakerId 组请求")
    void synthesizeLinePassesLineFields() {
        NarrationVoiceEngine voxcpm = availableEngine("voxcpm");
        when(voxcpm.synthesize(any())).thenReturn(new NarrationAudio(new byte[]{1}, "audio/wav"));
        NarrationAudioService service = new NarrationAudioService(List.of(voxcpm), config("voxcpm"), MESSAGES);
        NarrationScript.Line line = new NarrationScript.Line(
                0, "原句", 1, "哀家", "angry", "An elderly woman, angry");

        service.synthesizeLine(line, null, "zh-CN");

        ArgumentCaptor<NarrationVoiceRequest> captor = ArgumentCaptor.forClass(NarrationVoiceRequest.class);
        verify(voxcpm).synthesize(captor.capture());
        NarrationVoiceRequest req = captor.getValue();
        assertThat(req.text()).isEqualTo("原句");
        assertThat(req.controlInstruction()).isEqualTo("An elderly woman, angry");
        assertThat(req.delivery()).isEqualTo("angry");
        assertThat(req.characterId()).isEqualTo(1);
        assertThat(req.localeHint()).isEqualTo("zh-CN");
        assertThat(req.hasReferenceVoice()).isFalse();
    }

    private static NarrationVoiceEngine availableEngine(String id) {
        NarrationVoiceEngine engine = mock(NarrationVoiceEngine.class);
        when(engine.id()).thenReturn(id);
        when(engine.isAvailable()).thenReturn(true);
        return engine;
    }

    private static NarrationTtsConfig config(String engine) {
        NarrationTtsConfig cfg = new NarrationTtsConfig();
        cfg.setEngine(engine);
        return cfg;
    }
}
