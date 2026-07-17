package top.sywyar.pixivdownload.novel.narration.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import top.sywyar.pixivdownload.common.ErrorResponse;
import top.sywyar.pixivdownload.config.RuntimePathProvider;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.narration.NarrationReferenceVoiceService;
import top.sywyar.pixivdownload.novel.narration.NovelNarrationCastService;
import top.sywyar.pixivdownload.novel.narration.UploadedAudioValidator;
import top.sywyar.pixivdownload.novel.db.NovelNarrationVoiceRef;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationReferenceVoice;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 多角色朗读「参考音 / 标准音」管理端点：生成自动标准音、上传真人参考音、删除、试听。全部挂在 {@code /api/narration/}
 * 前缀下，按 monitor 语义保护（admin-only）——<b>不</b>入 {@code isPublic()} / 访客邀请白名单，限流绝不作用于 solo /
 * 已登录管理员。参考音字节存盘于 {@code data/narration-voice/}，元数据落库；任何变更都会推进花名册 {@code updated_time}
 * 使前端音频缓存失效、逐句音频自动按新音色重算。
 */
@RestController
@RequestMapping("/api/narration/cast/voice/reference")
@Slf4j
@RequiredArgsConstructor
public class NarrationReferenceVoiceController {

    /** 上传参考音字节上限（VoxCPM 推荐参考音 ≤50s，留足余量）。 */
    private static final long MAX_UPLOAD_BYTES = 8L * 1024 * 1024;
    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    private final NovelNarrationCastService castService;
    private final NarrationReferenceVoiceService referenceVoiceService;
    private final MessageResolver messages;
    private final RuntimePathProvider runtimePathProvider;

    public record GenerateRequest(Long castId, Integer characterId, String text) {}

    /** 参考音状态（生成 / 上传后回传，供前端刷新该角色的「标准音 / 参考音」区）。 */
    public record ReferenceStatus(long castId, int characterId, String source, String ext, boolean hasText) {}

    /** 自动生成并采用某角色的标准音（用其当前音色画像走 Voice Design 渲用户决定的示例正文，留空回退 i18n 默认句）。 */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody GenerateRequest request) {
        if (request == null || request.castId() == null || request.characterId() == null) {
            return badRequest("narration.error.invalid-voice");
        }
        if (!castService.exists(request.castId())) {
            return ResponseEntity.notFound().build();
        }
        try {
            String seedText = request.text() == null || request.text().isBlank()
                    ? messages.get("narration.seed-text")
                    : request.text().trim();
            NarrationReferenceVoiceService.GenerateResult result =
                    referenceVoiceService.generateSeed(request.castId(), request.characterId(), seedText);
            return switch (result.outcome()) {
                case ADOPTED -> ResponseEntity.ok(status(result.ref()));
                case TOO_SHORT -> ResponseEntity.unprocessableEntity()
                        .body(new ErrorResponse(messages.get("narration.error.ref-too-short")));
                case NO_BASE -> badRequest("narration.error.ref-no-base");
            };
        } catch (NarrationVoiceException e) {
            log.warn(messages.getForLog("narration.tts.log.preview-failed", e.getMessage()));
            return ResponseEntity.status(502)
                    .body(new ErrorResponse(messages.get("narration.tts.preview.failed", e.getMessage())));
        }
    }

    /** 上传真人参考音（wav/mp3）+ 可选转录。 */
    @PostMapping
    public ResponseEntity<?> upload(@RequestParam("castId") long castId,
                                    @RequestParam("characterId") int characterId,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "refText", required = false) String refText) {
        if (!castService.exists(castId)) {
            return ResponseEntity.notFound().build();
        }
        if (file == null || file.isEmpty()) {
            return badRequest("narration.error.ref-invalid-file");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return badRequest("narration.error.ref-too-large");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            log.warn("read narration reference voice upload failed: castId={}, characterId={}, err={}",
                    castId, characterId, e.getMessage());
            return badRequest("narration.error.ref-invalid-file");
        }
        UploadedAudioValidator.Result audio = UploadedAudioValidator.validate(data);
        String declaredExt = UploadedAudioValidator.declaredExtension(
                file.getContentType(),
                file.getOriginalFilename()
        );
        if (audio == null || (declaredExt != null && !declaredExt.equals(audio.extension()))) {
            return badRequest("narration.error.ref-invalid-file");
        }
        NovelNarrationVoiceRef ref = referenceVoiceService.saveUpload(
                castId, characterId, data, audio.extension(), refText);
        if (ref == null) {
            // 角色不在该花名册中（且非旁白）：服务已拒绝落盘，返回明确 404 而非 500。
            return ResponseEntity.status(404)
                    .body(new ErrorResponse(messages.get("narration.error.ref-character-not-found")));
        }
        return ResponseEntity.ok(status(ref));
    }

    /** 删除某角色的参考音。 */
    @DeleteMapping
    public ResponseEntity<?> delete(@RequestParam long castId, @RequestParam int characterId) {
        if (!castService.exists(castId)) {
            return ResponseEntity.notFound().build();
        }
        referenceVoiceService.delete(castId, characterId);
        return ResponseEntity.ok().build();
    }

    /** 试听某角色已配的参考音（直接回放存盘文件，非合成）。 */
    @GetMapping
    public ResponseEntity<?> preview(@RequestParam long castId, @RequestParam int characterId) {
        NovelNarrationVoiceRef ref = referenceVoiceService.reference(castId, characterId);
        if (ref == null) {
            return ResponseEntity.notFound().build();
        }
        Path file = runtimePathProvider.narrationVoiceFile(castId, characterId, ref.ext());
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] audio = Files.readAllBytes(file);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf(NarrationReferenceVoiceService.mimeForExt(ref.ext())));
            headers.setCacheControl(CacheControl.noStore());
            headers.add(X_CONTENT_TYPE_OPTIONS, "nosniff");
            return ResponseEntity.ok().headers(headers).body(audio);
        } catch (IOException e) {
            log.warn("read narration reference voice failed: castId={}, characterId={}, err={}",
                    castId, characterId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    private ResponseEntity<ErrorResponse> badRequest(String messageKey) {
        return ResponseEntity.badRequest().body(new ErrorResponse(messages.get(messageKey)));
    }

    private static ReferenceStatus status(NovelNarrationVoiceRef ref) {
        return new ReferenceStatus(ref.castId(), ref.characterId(), ref.source(), ref.ext(),
                ref.text() != null && !ref.text().isBlank());
    }

}
