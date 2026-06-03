package top.sywyar.pixivdownload.tts.narration.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.download.response.ErrorResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.tts.narration.NarrationAudioService;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceException;

/**
 * 多角色朗读（AI 听小说）TTS 试听端点，便于联调当前配置的引擎。
 *
 * <p>本端点按 monitor 语义保护（见 {@code AuthFilter} 的 {@code /api/narration/} 前缀），<b>不</b>在公共白名单、
 * <b>不</b>在访客邀请白名单中——solo 与 multi 两种模式下都仅管理员可访问，限流绝不作用于 solo / 已登录管理员。
 * 前端听书页整合是后续步骤。
 */
@RestController
@RequestMapping("/api/narration/tts")
@Slf4j
@RequiredArgsConstructor
public class NarrationTtsController {

    /** 单次试听文本上限，避免超长正文一次性塞给引擎。 */
    private static final int MAX_TEXT_LENGTH = 4000;

    private final NarrationAudioService narrationAudioService;
    private final AppMessages messages;

    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody NarrationTtsPreviewRequest request) {
        String text = request.text() == null ? "" : request.text().trim();
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse(messages.get("narration.tts.error.empty-text")));
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(messages.get("narration.tts.text-too-long", MAX_TEXT_LENGTH)));
        }
        String controlInstruction = request.controlInstruction() == null
                ? "" : request.controlInstruction().trim();
        try {
            NarrationAudio audio = narrationAudioService.synthesize(text, controlInstruction, null);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf(audio.contentType()));
            headers.setCacheControl(CacheControl.noStore());
            return ResponseEntity.ok().headers(headers).body(audio.data());
        } catch (NarrationVoiceException e) {
            log.warn(messages.getForLog("narration.tts.log.preview-failed", e.getMessage()));
            return ResponseEntity.status(502)
                    .body(new ErrorResponse(messages.get("narration.tts.preview.failed", e.getMessage())));
        }
    }
}
