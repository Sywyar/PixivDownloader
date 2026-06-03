package top.sywyar.pixivdownload.tts.narration.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.ai.narration.NarrationCharacter;
import top.sywyar.pixivdownload.download.response.ErrorResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.NarrationConflictReport;
import top.sywyar.pixivdownload.novel.NovelNarrationCastService;
import top.sywyar.pixivdownload.novel.NovelNarrationScriptService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.tts.narration.NarrationAudioService;

import java.util.ArrayList;
import java.util.List;

/**
 * 「AI 听小说」多角色朗读编排端点：整章脚本生成 / 缓存（{@code /script}）、花名册音色查看（{@code /cast}）、
 * 单角色音色编辑（{@code /cast/voice}）。单行音频合成在 {@link NarrationTtsController#line} 下。
 *
 * <p>全部端点按 monitor 语义保护（{@code AuthFilter} 的 {@code /api/narration/} 前缀），<b>不</b>在公共白名单 /
 * 访客邀请白名单中——solo 与 multi 两种模式下都仅管理员可访问，限流绝不作用于 solo / 已登录管理员。
 * {@code controlInstruction} 仅在花名册 / 冲突相关响应中下发（admin-only），<b>不</b>随逐句脚本下发。
 */
@RestController
@RequestMapping("/api/narration")
@Slf4j
@RequiredArgsConstructor
public class NarrationController {

    /** 整章正文字数上限：超过则拒绝一次性分析（避免一次性 LLM 成本 / 延迟失控），提示用户。 */
    private static final int MAX_CONTENT_CHARS = 200_000;

    private final NovelNarrationScriptService scriptService;
    private final NovelNarrationCastService castService;
    private final NarrationAudioService narrationAudioService;
    private final NovelDatabase novelDatabase;
    private final AppMessages messages;

    // ── DTO ──────────────────────────────────────────────────────────────────

    public record ScriptRequest(Long novelId, String lang, Integer segmentSize, Boolean force) {}

    /** 逐句脚本行（下发给客户端，<b>不含</b> controlInstruction）。 */
    public record ScriptLineView(int index, int speakerId, String speakerName, String delivery,
                                 int paragraphIndex, String text) {}

    /** 花名册角色概要（脚本响应用，不含音色画像）。 */
    public record CastBrief(int id, String name, String gender, String age) {}

    public record ConflictView(int characterId, String name, String type, String reason,
                               String currentInstruction, String suggestion) {}

    public record ScriptResponse(List<ScriptLineView> lines, List<CastBrief> cast,
                                  List<ConflictView> conflicts, long castUpdatedTime,
                                  int segmentSize, long analyzedTime) {}

    /** 花名册角色（含音色画像，仅 admin 可见）。 */
    public record VoiceView(int id, String name, String gender, String age,
                            String controlInstruction, boolean editedByUser) {}

    public record CastResponse(Long castId, String castName, List<VoiceView> voices) {}

    public record VoiceUpdateRequest(Long novelId, Integer characterId, String controlInstruction) {}

    /** 朗读引擎可用性（前端据此启用 / 禁用「富感情朗读」听书引擎入口）。 */
    public record AvailabilityResponse(boolean available) {}

    // ── 端点 ──────────────────────────────────────────────────────────────────

    /**
     * 当前配置的多角色朗读引擎是否可用（admin-only，由 {@code /api/narration/} 前缀按 monitor 语义保护）。
     * 前端「听书」据此把「富感情朗读」引擎选项置灰 / 禁用，避免后端未配置时点开即触发分析。
     */
    @GetMapping("/availability")
    public ResponseEntity<?> availability() {
        return ResponseEntity.ok(new AvailabilityResponse(narrationAudioService.isEngineAvailable()));
    }

    /** 生成 / 取整章逐句脚本（缓存命中不调 LLM；{@code force} 重新分析）。 */
    @PostMapping("/script")
    public ResponseEntity<?> script(@RequestBody ScriptRequest request) {
        if (request == null || request.novelId() == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(messages.get("narration.error.missing-novel")));
        }
        long novelId = request.novelId();
        NovelRecord rec = novelDatabase.getNovel(novelId);
        if (rec == null) {
            return ResponseEntity.notFound().build();
        }
        String lang = request.lang() == null ? "" : request.lang().trim();
        int segmentSize = request.segmentSize() == null ? 0 : Math.max(0, request.segmentSize());
        boolean force = Boolean.TRUE.equals(request.force());

        NovelNarrationScriptService.ChapterScript script;
        try {
            script = scriptService.getOrAnalyze(novelId, lang, segmentSize, force, MAX_CONTENT_CHARS);
        } catch (NovelNarrationScriptService.ContentTooLargeException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(messages.get("narration.error.content-too-large", e.limit())));
        }

        List<ScriptLineView> lines = new ArrayList<>(script.lines().size());
        for (NovelNarrationScriptService.ScriptLine l : script.lines()) {
            lines.add(new ScriptLineView(l.index(), l.speakerId(), l.speakerName(),
                    l.delivery(), l.paragraphIndex(), l.text()));
        }
        List<ConflictView> conflicts = new ArrayList<>(script.conflicts().size());
        for (NarrationConflictReport c : script.conflicts()) {
            conflicts.add(new ConflictView(c.characterId(), c.name(), c.type(),
                    c.reason(), c.currentInstruction(), c.suggestion()));
        }
        return ResponseEntity.ok(new ScriptResponse(lines, castBrief(script.castId()),
                conflicts, script.castUpdatedTime(), script.segmentSize(), script.analyzedTime()));
    }

    /** 查看某本小说的默认花名册（含音色画像，admin-only）；未分析时返回仅旁白、不强制创建。 */
    @GetMapping("/cast")
    public ResponseEntity<?> cast(@RequestParam long novelId) {
        NovelNarrationCastService.DefaultCast def = castService.resolveNovelDefaultCast(novelId);
        if (def == null) {
            return ResponseEntity.notFound().build();
        }
        if (def.cast() == null) {
            return ResponseEntity.ok(new CastResponse(null, null, List.of(voiceView(NarrationCharacter.defaultNarrator()))));
        }
        long castId = def.cast().id();
        List<VoiceView> voices = new ArrayList<>();
        for (NarrationCharacter c : castService.voices(castId)) {
            voices.add(voiceView(c));
        }
        return ResponseEntity.ok(new CastResponse(castId, def.cast().name(), voices));
    }

    /** 编辑某角色音色画像并锁定（{@code edited_by_user=1}）。冲突解决「采纳建议 / 改写」复用本端点；「保留」为无操作。 */
    @PutMapping("/cast/voice")
    public ResponseEntity<?> updateVoice(@RequestBody VoiceUpdateRequest request) {
        if (request == null || request.novelId() == null || request.characterId() == null
                || request.controlInstruction() == null || request.controlInstruction().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse(messages.get("narration.error.invalid-voice")));
        }
        NovelNarrationCastService.DefaultCast def = castService.resolveNovelDefaultCast(request.novelId());
        if (def == null) {
            return ResponseEntity.notFound().build();
        }
        long castId = def.cast() != null ? def.cast().id()
                : castService.create(def.suggestedName(), def.seriesId(), def.novelId()).id();
        castService.updateVoiceInstruction(castId, request.characterId(), request.controlInstruction());
        return ResponseEntity.ok().build();
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private List<CastBrief> castBrief(long castId) {
        if (castId <= 0) {
            NarrationCharacter n = NarrationCharacter.defaultNarrator();
            return List.of(new CastBrief(n.id(), n.name(), n.gender(), n.age()));
        }
        List<CastBrief> out = new ArrayList<>();
        for (NarrationCharacter c : castService.voices(castId)) {
            out.add(new CastBrief(c.id(), c.name(), c.gender(), c.age()));
        }
        return out;
    }

    private static VoiceView voiceView(NarrationCharacter c) {
        return new VoiceView(c.id(), c.name(), c.gender(), c.age(), c.controlInstruction(), c.editedByUser());
    }
}
