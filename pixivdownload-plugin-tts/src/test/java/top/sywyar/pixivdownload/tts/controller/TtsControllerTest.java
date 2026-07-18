package top.sywyar.pixivdownload.tts.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.tts.EdgeTtsClient;
import top.sywyar.pixivdownload.tts.EdgeTtsVoiceService;
import top.sywyar.pixivdownload.tts.TtsRateLimitService;
import top.sywyar.pixivdownload.tts.dto.EdgeTtsSynthesizeRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Edge TTS 控制器邀请访客限流")
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
        assertThat(response.getBody()).isEqualTo(java.util.Map.of("error", "请求过于频繁"));
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
        verify(limiter, never()).isAllowed(org.mockito.ArgumentMatchers.anyString());
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
