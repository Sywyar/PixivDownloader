package top.sywyar.pixivdownload.tts.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.ErrorResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;
import top.sywyar.pixivdownload.tts.EdgeTtsClient;
import top.sywyar.pixivdownload.tts.EdgeTtsException;
import top.sywyar.pixivdownload.tts.EdgeTtsVoiceService;
import top.sywyar.pixivdownload.tts.TtsRateLimitService;
import top.sywyar.pixivdownload.tts.dto.EdgeTtsSynthesizeRequest;
import top.sywyar.pixivdownload.tts.dto.EdgeTtsVoice;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 文本转语音（TTS）端点。仅在线引擎 Edge TTS 需要后端代理；浏览器内置的 Web Speech API 完全在前端运行，不经过这里。
 *
 * <p>这些端点不在 {@code AuthFilter} 的公共白名单中：solo 模式需要登录会话、multi 模式访客受常规接口限流约束，
 * 与其它 {@code /api/pixiv/**} 代理端点的访问语义一致。
 */
@RestController
@RequestMapping("/api/tts/edge")
@Slf4j
@RequiredArgsConstructor
public class TtsController {

    /** 单次合成的文本上限，避免超长正文一次性塞给在线服务。 */
    private static final int MAX_TEXT_LENGTH = 4000;

    private final EdgeTtsClient edgeTtsClient;
    private final EdgeTtsVoiceService voiceService;
    private final TtsRateLimitService ttsRateLimitService;
    private final AppMessages messages;

    @GetMapping("/voices")
    public ResponseEntity<List<EdgeTtsVoice>> voices() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(6, TimeUnit.HOURS).cachePublic())
                .body(voiceService.listVoices());
    }

    @PostMapping("/synthesize")
    public ResponseEntity<?> synthesize(@RequestBody EdgeTtsSynthesizeRequest request,
                                        HttpServletRequest httpRequest) {
        ResponseEntity<?> deny = checkGuestRateLimit(httpRequest);
        if (deny != null) {
            return deny;
        }
        String text = request.text() == null ? "" : request.text().trim();
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse(messages.get("tts.text.empty")));
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(messages.get("tts.text.too-long", MAX_TEXT_LENGTH)));
        }
        String voice = request.voice();
        if (voice == null || !allowedVoiceNames().contains(voice)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(messages.get("tts.voice.invalid", voice)));
        }
        String rate = formatRate(request.rate());
        String pitch = formatPitch(request.pitch());
        try {
            byte[] mp3 = edgeTtsClient.synthesize(text, voice, rate, pitch, "+0%");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("audio/mpeg"));
            headers.setCacheControl(CacheControl.noStore());
            return ResponseEntity.ok().headers(headers).body(mp3);
        } catch (EdgeTtsException e) {
            log.warn(logMessage("tts.log.synthesize-failed", e.getMessage()));
            return ResponseEntity.status(502).body(new ErrorResponse(messages.get("tts.synthesize.failed", e.getMessage())));
        }
    }

    /**
     * 邀请访客在线合成限流。仅作用于携带有效邀请会话的访客（任意模式）；
     * 管理员 / solo 拥有者没有邀请会话，直接放行。返回非 null 即为应直接返回的 429。
     */
    private ResponseEntity<?> checkGuestRateLimit(HttpServletRequest request) {
        Object attr = request.getAttribute(GuestInviteSession.REQUEST_ATTR);
        if (!(attr instanceof GuestInviteSession session)) {
            return null;
        }
        if (!ttsRateLimitService.isAllowed("invite:" + session.id())) {
            return ResponseEntity.status(429).body(new ErrorResponse(
                    messages.get("tts.rate-limit.exceeded", ttsRateLimitService.getLimitPerMinute())));
        }
        return null;
    }

    private Set<String> allowedVoiceNames() {
        return voiceService.listVoices().stream()
                .map(EdgeTtsVoice::shortName)
                .collect(Collectors.toSet());
    }

    /** 语速限制在 [-50, 100] %，缺省 0%。 */
    private static String formatRate(Integer rate) {
        int v = rate == null ? 0 : Math.max(-50, Math.min(100, rate));
        return (v >= 0 ? "+" : "") + v + "%";
    }

    /** 音调限制在 [-50, 50] Hz，缺省 0Hz。 */
    private static String formatPitch(Integer pitch) {
        int v = pitch == null ? 0 : Math.max(-50, Math.min(50, pitch));
        return (v >= 0 ? "+" : "") + v + "Hz";
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
