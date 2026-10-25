package top.sywyar.pixivdownload.tts.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.tts.EdgeTtsClient;
import top.sywyar.pixivdownload.tts.EdgeTtsVoiceService;
import top.sywyar.pixivdownload.tts.TtsRateLimitService;
import top.sywyar.pixivdownload.tts.dto.EdgeTtsSynthesizeRequest;
import top.sywyar.pixivdownload.tts.dto.EdgeTtsVoice;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Edge TTS 合成校验与邀请访客限流")
class TtsControllerTest {

    @Test
    @DisplayName("宿主 subject 原样交给插件限流器且拒绝时保持 429")
    void delegatesOpaqueSubjectAndPreservesRateLimitResponse() {
        TtsRateLimitService limiter = mock(TtsRateLimitService.class);
        when(limiter.isAllowed("invite:42")).thenReturn(false);
        when(limiter.getLimitPerMinute()).thenReturn(30);
        MessageResolver messages = mock(MessageResolver.class);
        when(messages.get("tts.rate-limit.exceeded", 30)).thenReturn("请求过于频繁");
        TtsController controller = controller(limiter, messages,
                resolver(Optional.of("invite:42")));

        ResponseEntity<?> response = controller.synthesize(
                new EdgeTtsSynthesizeRequest("text", "voice", 0, 0),
                new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).isEqualTo(
                ApiErrorResponse.of("tts.rate-limit.exceeded", "请求过于频繁"));
        verify(limiter).isAllowed("invite:42");
    }

    @Test
    @DisplayName("非邀请请求不触发邀请访客限流")
    void skipsRateLimitWithoutSubject() {
        TtsRateLimitService limiter = mock(TtsRateLimitService.class);
        MessageResolver messages = mock(MessageResolver.class);
        when(messages.get("tts.text.empty")).thenReturn("文本为空");
        TtsController controller = controller(limiter, messages, resolver(Optional.empty()));

        ResponseEntity<?> response = controller.synthesize(
                new EdgeTtsSynthesizeRequest("", null, null, null),
                new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(ApiErrorResponse.of("tts.text.empty", "文本为空"));
        verify(limiter, never()).isAllowed(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("按当前音色列表校验并保留文本裁剪及语速音调边界")
    void validatesCurrentVoicesAndPreservesSynthesisParameters() {
        EdgeTtsClient client = mock(EdgeTtsClient.class);
        EdgeTtsVoiceService voices = mock(EdgeTtsVoiceService.class);
        when(voices.listVoices()).thenReturn(
                List.of(new EdgeTtsVoice("voice-a", "en-US", "Female", "A"),
                        new EdgeTtsVoice("voice-b", "en-US", "Male", "B")),
                List.of(new EdgeTtsVoice("voice-a", "en-US", "Female", "A")));
        byte[] audio = {1, 2, 3};
        when(client.synthesize("text", "voice-b", "-50%", "+50Hz", "+0%"))
                .thenReturn(audio);
        MessageResolver messages = mock(MessageResolver.class);
        when(messages.get("tts.voice.invalid", "voice-b")).thenReturn("Invalid voice");
        TtsController controller = new TtsController(client, voices,
                mock(TtsRateLimitService.class), messages, resolver(Optional.empty()));
        EdgeTtsSynthesizeRequest request = new EdgeTtsSynthesizeRequest(" text ", "voice-b", -200, 200);

        ResponseEntity<?> response = controller.synthesize(request, new MockHttpServletRequest());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(audio);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("audio/mpeg");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");

        ResponseEntity<?> removedVoice = controller.synthesize(request, new MockHttpServletRequest());
        assertThat(removedVoice.getStatusCode().value()).isEqualTo(400);
        assertThat(removedVoice.getBody()).isEqualTo(ApiErrorResponse.of("tts.voice.invalid", "Invalid voice"));
        verify(client).synthesize("text", "voice-b", "-50%", "+50Hz", "+0%");
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "VOICE"})
    @DisplayName("缺失或不在列表中的音色不会启动合成")
    void rejectsUnknownVoiceBeforeSynthesis(String voice) {
        EdgeTtsClient client = mock(EdgeTtsClient.class);
        EdgeTtsVoiceService voices = mock(EdgeTtsVoiceService.class);
        when(voices.listVoices()).thenReturn(List.of(new EdgeTtsVoice("voice", "en-US", "Female", "A")));
        MessageResolver messages = mock(MessageResolver.class);
        when(messages.get("tts.voice.invalid", voice)).thenReturn("Invalid voice");
        TtsController controller = new TtsController(client, voices,
                mock(TtsRateLimitService.class), messages, resolver(Optional.empty()));

        ResponseEntity<?> response = controller.synthesize(
                new EdgeTtsSynthesizeRequest("text", voice, null, null), new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(ApiErrorResponse.of("tts.voice.invalid", "Invalid voice"));
        verifyNoInteractions(client);
    }

    private static TtsController controller(TtsRateLimitService limiter,
                                            MessageResolver messages,
                                            RequestOwnerIdentityResolver resolver) {
        return new TtsController(mock(EdgeTtsClient.class), mock(EdgeTtsVoiceService.class),
                limiter, messages, resolver);
    }

    private static RequestOwnerIdentityResolver resolver(Optional<String> subject) {
        return new RequestOwnerIdentityResolver() {
            @Override
            public RequestOwnerIdentity resolve(jakarta.servlet.http.HttpServletRequest request) {
                return RequestOwnerIdentity.adminScope();
            }

            @Override
            public Optional<String> resolveInvitedGuestRateLimitSubject(
                    jakarta.servlet.http.HttpServletRequest request) {
                return subject;
            }
        };
    }
}
