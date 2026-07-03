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
import top.sywyar.pixivdownload.common.ErrorResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.narration.NovelNarrationScriptService;
import top.sywyar.pixivdownload.novel.narration.audio.NarrationAudioService;
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
    private final NovelNarrationScriptService narrationScriptService;
    private final AppMessages messages;

    /**
     * 合成持久化整章脚本的<b>某一行</b>音频。服务端按该行 speaker 从<b>活花名册</b>取基底画像、合并该行
     * delivery 后交给引擎，因此音色编辑 / 冲突解决会即时体现。脚本不存在 / 行越界 → 404；引擎失败 → 502。
     */
    @PostMapping("/line")
    public ResponseEntity<?> line(@RequestBody NarrationLineRequest request) {
        if (request == null || request.novelId() == null || request.lineIndex() == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(messages.get("narration.error.invalid-line")));
        }
        String lang = request.lang() == null ? "" : request.lang().trim();
        try {
            NarrationAudio audio = narrationScriptService.synthesizeLine(
                    request.novelId(), lang, request.lineIndex());
            if (audio == null) {
                return ResponseEntity.status(404)
                        .body(new ErrorResponse(messages.get("narration.error.no-script")));
            }
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

    /** 单行合成请求：按 {@code novelId} + {@code lineIndex} 定位持久化脚本行；{@code lang} 与脚本语言一致（空=原文）。 */
    public record NarrationLineRequest(Long novelId, Integer lineIndex, String lang) {}

    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody NarrationTtsPreviewRequest request) {
        String text = request == null || request.text() == null ? "" : request.text().trim();
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
            NarrationAudio audio = narrationAudioService.synthesizeVoiceDesign(text, controlInstruction, null);
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
