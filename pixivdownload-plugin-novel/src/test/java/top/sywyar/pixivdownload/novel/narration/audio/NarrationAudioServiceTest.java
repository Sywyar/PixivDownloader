package top.sywyar.pixivdownload.novel.narration.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.novel.narration.analysis.NarrationScript;
import top.sywyar.pixivdownload.novel.narration.analysis.NarrationSentence;
import top.sywyar.pixivdownload.novel.narration.analysis.NarrationSentenceSplitter;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;
import top.sywyar.pixivdownload.core.narration.NarrationEngineRegistry;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationReferenceVoice;
import top.sywyar.pixivdownload.core.narration.NarrationTtsConfig;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityUnavailableException;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceEngine;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceException;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceMode;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceRequest;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("多角色朗读音频合成服务（集中调用 + 显式模式 + 归一 + 能力降级）")
class NarrationAudioServiceTest {

    private static final MessageResolver MESSAGES = TestI18nBeans.messageResolver();

    @Test
    @DisplayName("synthesizeVoiceDesign：按 narration-tts.engine 选中引擎并以 VOICE_DESIGN 合成")
    void selectsConfiguredEngineAndSynthesizes() {
        NarrationVoiceEngine voxcpm = availableEngine("voxcpm", NarrationVoiceMode.VOICE_DESIGN);
        NarrationAudio audio = new NarrationAudio(new byte[]{1}, "audio/wav");
        when(voxcpm.synthesize(any(), any())).thenReturn(audio);
        NarrationAudioService service = service(config("voxcpm"), voxcpm);

        NarrationAudio out = service.synthesizeVoiceDesign("text", "ci");

        assertThat(out).isSameAs(audio);
        verify(voxcpm).synthesize(eq(NarrationVoiceMode.VOICE_DESIGN), any(NarrationVoiceRequest.class));
    }

    @Test
    @DisplayName("归一后为空文本（纯标点 / 省略号）→ 抛受控异常且绝不调引擎")
    void emptyNormalizedTextThrowsWithoutEngine() {
        NarrationVoiceEngine voxcpm = availableEngine("voxcpm", NarrationVoiceMode.VOICE_DESIGN);
        NarrationAudioService service = service(config("voxcpm"), voxcpm);

        assertThatThrownBy(() -> service.synthesizeVoiceDesign("……", ""))
                .isInstanceOf(NarrationVoiceException.class);
        verify(voxcpm, never()).synthesize(any(), any());
    }

    @Test
    @DisplayName("断句保留的纯标点独立段落（……）→ synthesizeLine 返回短静音、可跳过、绝不调引擎")
    void pureePunctuationLineFromSplitterReturnsSilence() {
        // 断句层把独立成段的「……」按 paragraphIndex 对齐保留；合成阶段不应抛 502，而应返回可跳过的短静音。
        List<NarrationSentence> sentences = NarrationSentenceSplitter.split("前段。\n\n……\n\n后段。");
        NarrationSentence punct = sentences.stream()
                .filter(s -> "……".equals(s.text()))
                .findFirst().orElseThrow();
        assertThat(punct.text()).isEqualTo("……");
        NarrationVoiceEngine voxcpm = availableEngine("voxcpm", NarrationVoiceMode.VOICE_DESIGN);
        NarrationAudioService service = service(config("voxcpm"), voxcpm);
        NarrationScript.Line line = new NarrationScript.Line(1, punct.text(), 0, "旁白", "", "Narrator voice");

        NarrationAudio audio = service.synthesizeLine(line, null);

        assertThat(audio).isNotNull();
        assertThat(audio.contentType()).isEqualTo("audio/wav");
        assertThat(audio.data()).isNotEmpty();
        verify(voxcpm, never()).synthesize(any(), any());
    }

    @Test
    @DisplayName("请求模式不在引擎能力集时降级为 VOICE_DESIGN")
    void downgradesUnsupportedModeToVoiceDesign() {
        // 引擎仅支持 VOICE_DESIGN；synthesizeLine 带参考音 → 请求 CLONE，应被降级。
        NarrationVoiceEngine engine = availableEngine("voxcpm", NarrationVoiceMode.VOICE_DESIGN);
        when(engine.synthesize(any(), any())).thenReturn(new NarrationAudio(new byte[]{1}, "audio/wav"));
        NarrationAudioService service = service(config("voxcpm"), engine);
        NarrationScript.Line line = new NarrationScript.Line(0, "原句", 1, "甲", "angry", "An old woman");
        NarrationReferenceVoice ref = new NarrationReferenceVoice(new byte[]{1, 2, 3}, "audio/wav", "种子");

        service.synthesizeLine(line, ref);

        ArgumentCaptor<NarrationVoiceMode> modeCaptor = ArgumentCaptor.forClass(NarrationVoiceMode.class);
        verify(engine).synthesize(modeCaptor.capture(), any());
        assertThat(modeCaptor.getValue()).isEqualTo(NarrationVoiceMode.VOICE_DESIGN);
    }

    @Test
    @DisplayName("synthesizeLine：有可用参考音 → 请求 CLONE 模式")
    void synthesizeLineWithReferenceRequestsClone() {
        NarrationVoiceEngine engine = availableEngine("voxcpm",
                NarrationVoiceMode.VOICE_DESIGN, NarrationVoiceMode.CLONE);
        when(engine.synthesize(any(), any())).thenReturn(new NarrationAudio(new byte[]{1}, "audio/wav"));
        NarrationAudioService service = service(config("voxcpm"), engine);
        NarrationScript.Line line = new NarrationScript.Line(0, "原句", 1, "甲", "angry", "An old woman");
        NarrationReferenceVoice ref = new NarrationReferenceVoice(new byte[]{1, 2, 3}, "audio/wav", "种子");

        service.synthesizeLine(line, ref);

        verify(engine).synthesize(eq(NarrationVoiceMode.CLONE), any());
    }

    @Test
    @DisplayName("选中引擎不可用时抛受控异常且不合成")
    void unavailableEngineThrows() {
        NarrationVoiceEngine voxcpm = mock(NarrationVoiceEngine.class);
        when(voxcpm.id()).thenReturn("voxcpm");
        when(voxcpm.isAvailable()).thenReturn(false);
        NarrationAudioService service = service(config("voxcpm"), voxcpm);

        assertThatThrownBy(() -> service.synthesizeVoiceDesign("t", ""))
                .isInstanceOf(NarrationVoiceException.class);
        verify(voxcpm, never()).synthesize(any(), any());
    }

    @Test
    @DisplayName("引擎撤回后的迟到合成使用已捕获 id 并抛受控不可用异常")
    void withdrawnEngineSynthesisIsControlled() {
        NarrationVoiceEngine engine = mock(NarrationVoiceEngine.class);
        when(engine.id()).thenReturn("voxcpm");
        NarrationAudioService service = service(config("voxcpm"), engine);
        when(engine.id()).thenThrow(new AssertionError("service must use captured engine id"));
        when(engine.isAvailable()).thenThrow(new ExternalCapabilityUnavailableException("withdrawn"));

        assertThatThrownBy(() -> service.synthesizeVoiceDesign("text", ""))
                .isInstanceOf(NarrationVoiceException.class);
    }

    @Test
    @DisplayName("引擎撤回后的可达性探测按不可用降级")
    void withdrawnEngineReachabilityIsFalse() {
        NarrationVoiceEngine engine = mock(NarrationVoiceEngine.class);
        when(engine.id()).thenReturn("voxcpm");
        NarrationAudioService service = service(config("voxcpm"), engine);
        when(engine.isReachable()).thenThrow(new ExternalCapabilityUnavailableException("withdrawn"));

        assertThat(service.isEngineAvailable()).isFalse();
    }

    @Test
    @DisplayName("配置的引擎 id 不存在时抛受控异常")
    void engineNotFoundThrows() {
        NarrationAudioService service = new NarrationAudioService(
                new NarrationEngineRegistry(List.of()), config("unknown"), MESSAGES);

        assertThatThrownBy(() -> service.synthesizeVoiceDesign("t", ""))
                .isInstanceOf(NarrationVoiceException.class);
    }

    @Test
    @DisplayName("synthesizeLine：用脚本行的文本、音色描述和语气组请求")
    void synthesizeLinePassesLineFields() {
        NarrationVoiceEngine voxcpm = availableEngine("voxcpm", NarrationVoiceMode.VOICE_DESIGN);
        when(voxcpm.synthesize(any(), any())).thenReturn(new NarrationAudio(new byte[]{1}, "audio/wav"));
        NarrationAudioService service = service(config("voxcpm"), voxcpm);
        NarrationScript.Line line = new NarrationScript.Line(
                0, "原句", 1, "哀家", "angry", "An elderly woman, angry");

        service.synthesizeLine(line, null);

        ArgumentCaptor<NarrationVoiceRequest> captor = ArgumentCaptor.forClass(NarrationVoiceRequest.class);
        verify(voxcpm).synthesize(eq(NarrationVoiceMode.VOICE_DESIGN), captor.capture());
        NarrationVoiceRequest req = captor.getValue();
        assertThat(req.text()).isEqualTo("原句");
        assertThat(req.controlInstruction()).isEqualTo("An elderly woman, angry");
        assertThat(req.delivery()).isEqualTo("angry");
        assertThat(req.hasReferenceVoice()).isFalse();
    }

    private static NarrationAudioService service(NarrationTtsConfig config, NarrationVoiceEngine... engines) {
        return new NarrationAudioService(new NarrationEngineRegistry(List.of(engines)), config, MESSAGES);
    }

    private static NarrationVoiceEngine availableEngine(String id, NarrationVoiceMode... modes) {
        NarrationVoiceEngine engine = mock(NarrationVoiceEngine.class);
        when(engine.id()).thenReturn(id);
        when(engine.isAvailable()).thenReturn(true);
        when(engine.supportedModes()).thenReturn(EnumSet.copyOf(List.of(modes)));
        return engine;
    }

    private static NarrationTtsConfig config(String engine) {
        NarrationTtsConfig cfg = new NarrationTtsConfig();
        cfg.setEngine(engine);
        return cfg;
    }
}
