package top.sywyar.pixivdownload.tts.narration.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.ai.AiService;
import top.sywyar.pixivdownload.ai.narration.NarrationCharacter;
import top.sywyar.pixivdownload.ai.narration.NarratorVoicePreset;
import top.sywyar.pixivdownload.config.DebugConfig;
import top.sywyar.pixivdownload.common.ErrorResponse;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.NarrationConflictReport;
import top.sywyar.pixivdownload.novel.NarrationReferenceVoiceService;
import top.sywyar.pixivdownload.novel.NovelNarrationCastService;
import top.sywyar.pixivdownload.novel.NovelNarrationScriptService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelNarrationCast;
import top.sywyar.pixivdownload.core.metadata.NovelRecord;
import top.sywyar.pixivdownload.tts.narration.NarrationAudioService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final NarrationReferenceVoiceService referenceVoiceService;
    private final NarrationAudioService narrationAudioService;
    private final NovelDatabase novelDatabase;
    private final AppMessages messages;
    private final DebugConfig debugConfig;
    private final AiService aiService;

    // ── DTO ──────────────────────────────────────────────────────────────────

    /**
     * 生成 / 取脚本请求。{@code castId} 为本次分析显式指定的花名册（{@code null}=本作默认、{@code <=0}=纯旁白、
     * {@code >0}=指定/借用花名册）；{@code narratorPreset} 为首次分析弹窗选定的旁白音色预设 id（见
     * {@link NarratorVoicePreset}，空 / 未知=不改旁白）；{@code analyzeIfMissing} 为 {@code false} 时仅取缓存、
     * 无缓存不分析（探测用）。
     */
    public record ScriptRequest(Long novelId, String lang, Integer segmentSize, Boolean force,
                                Long castId, String narratorPreset, Boolean analyzeIfMissing) {}

    /** 逐句脚本行（下发给客户端，<b>不含</b> controlInstruction）。 */
    public record ScriptLineView(int index, int speakerId, String speakerName, String delivery,
                                 int paragraphIndex, String text) {}

    /** 花名册角色概要（脚本响应用，不含音色画像）。 */
    public record CastBrief(int id, String name, String gender, String age) {}

    public record ConflictView(int characterId, String name, String type, String reason,
                               String currentInstruction, String suggestion) {}

    public record ScriptResponse(List<ScriptLineView> lines, List<CastBrief> cast,
                                  List<ConflictView> conflicts, long castId, long castUpdatedTime,
                                  int segmentSize, long analyzedTime) {}

    /**
     * 花名册角色（含音色画像，仅 admin 可见）。{@code refAudioSource} 为该角色参考音 / 标准音来源
     * （{@code auto}=自动生成、{@code upload}=用户上传、{@code null}=未配，使用音色画像），供前端展示状态。
     */
    public record VoiceView(int id, String name, String gender, String age,
                            String controlInstruction, boolean editedByUser, String refAudioSource) {}

    public record CastResponse(Long castId, String castName, List<VoiceView> voices) {}

    /** 花名册概要（选择器用）。 */
    public record CastSummary(long id, String name, Long seriesId, Long novelId, int voiceCount) {}

    public record CastListResponse(List<CastSummary> casts) {}

    public record CreateCastRequest(String name, Long seriesId, Long novelId) {}

    /** 某作品的默认花名册；{@code castId} 为 {@code null} 表示尚未创建，前端可据 {@code name} + 绑定按需创建。 */
    public record DefaultCastResponse(Long castId, String name, Long seriesId, Long novelId, int voiceCount) {}

    /** 编辑某角色音色：优先用显式 {@code castId}，否则按 {@code novelId} 解析本作默认册（按需创建）。 */
    public record VoiceUpdateRequest(Long castId, Long novelId, Integer characterId, String controlInstruction) {}

    /**
     * 朗读引擎可用性（前端据此启用 / 禁用「富感情朗读」听书引擎入口）。
     * {@code available} 为 TTS 引擎是否真实可达；{@code debug} 为调试模式开关（开启时即便引擎不可达，前端也允许
     * 选中该引擎以运行分析、查看结果）；{@code textModelConfigured} 为文本模型（LLM）是否已配置就绪——逐句说话人
     * 分析依赖 LLM，未配置时该入口应隐藏（即便 TTS 可达或处于调试模式）。
     */
    public record AvailabilityResponse(boolean available, boolean debug, boolean textModelConfigured) {}

    /** 旁白音色预设（id + 固定英文画像）：前端按 id 映射 i18n 标签、用画像做预览 / 试听。 */
    public record NarratorPresetView(String id, String instruction) {}

    public record NarratorPresetsResponse(List<NarratorPresetView> presets) {}

    // ── 端点 ──────────────────────────────────────────────────────────────────

    /**
     * 当前配置的多角色朗读引擎是否可用（admin-only，由 {@code /api/narration/} 前缀按 monitor 语义保护）。
     * 前端「听书」据此把「富感情朗读」引擎选项置灰 / 禁用，避免后端未配置时点开即触发分析。
     */
    @GetMapping("/availability")
    public ResponseEntity<?> availability() {
        return ResponseEntity.ok(new AvailabilityResponse(
                narrationAudioService.isEngineAvailable(), debugConfig.isEnabled(),
                aiService.isConfigured()));
    }

    /** 旁白音色预设清单（admin-only）：供首次分析弹窗的「旁白音色」选择器渲染标签 / 预览 / 试听。 */
    @GetMapping("/narrator-presets")
    public NarratorPresetsResponse narratorPresets() {
        List<NarratorPresetView> presets = new ArrayList<>();
        for (NarratorVoicePreset p : NarratorVoicePreset.all()) {
            presets.add(new NarratorPresetView(p.id(), p.instruction()));
        }
        return new NarratorPresetsResponse(presets);
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
        boolean analyzeIfMissing = request.analyzeIfMissing() == null || request.analyzeIfMissing();

        // 探测模式：仅取缓存、无缓存不分析（点播放前判断是否已有脚本）。
        if (!force && !analyzeIfMissing) {
            NovelNarrationScriptService.ChapterScript cached = scriptService.peekScript(novelId, lang);
            if (cached == null) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.ok(toScriptResponse(cached));
        }

        // 真正会触发「新分析」的路径（force，或本作 / 该语言尚无持久化脚本）需要朗读引擎可用：引擎不可用且非调试模式时
        // 直接拒绝，避免「服务不可用仍跑 LLM 分析」产生无法播放的脚本与额外 AI 成本。缓存命中 / 上面的探测仍照常返回。
        if (!narrationAudioService.isEngineAvailable() && !debugConfig.isEnabled()
                && (force || scriptService.peekScript(novelId, lang) == null)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse(messages.get("narration.error.engine-unavailable")));
        }

        // 旁白音色预设 id → 固定英文画像（未知 / 空=不改旁白）；画像文本始终由后端枚举提供，不信任客户端原文。
        NarratorVoicePreset preset = NarratorVoicePreset.byId(request.narratorPreset());
        String narratorInstruction = preset == null ? null : preset.instruction();

        NovelNarrationScriptService.ChapterScript script;
        try {
            script = scriptService.getOrAnalyze(novelId, lang, segmentSize, force, MAX_CONTENT_CHARS,
                    request.castId(), narratorInstruction);
        } catch (NovelNarrationScriptService.ContentTooLargeException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(messages.get("narration.error.content-too-large", e.limit())));
        }
        return ResponseEntity.ok(toScriptResponse(script));
    }

    private ScriptResponse toScriptResponse(NovelNarrationScriptService.ChapterScript script) {
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
        return new ScriptResponse(lines, castBrief(script.castId()), conflicts, script.castId(),
                script.castUpdatedTime(), script.segmentSize(), script.analyzedTime());
    }

    /** 查看某本小说的默认花名册（含音色画像，admin-only）；未分析时返回仅旁白、不强制创建。 */
    @GetMapping("/cast")
    public ResponseEntity<?> cast(@RequestParam long novelId) {
        NovelNarrationCastService.DefaultCast def = castService.resolveNovelDefaultCast(novelId);
        if (def == null) {
            return ResponseEntity.notFound().build();
        }
        if (def.cast() == null) {
            return ResponseEntity.ok(new CastResponse(null, null,
                    List.of(voiceView(NarrationCharacter.defaultNarrator(), null))));
        }
        long castId = def.cast().id();
        Map<Integer, String> sources = referenceVoiceService.sources(castId);
        List<VoiceView> voices = new ArrayList<>();
        for (NarrationCharacter c : castService.voices(castId)) {
            voices.add(voiceView(c, sources.get(c.id())));
        }
        return ResponseEntity.ok(new CastResponse(castId, def.cast().name(), voices));
    }

    /** 列出全部花名册（选择器用，含角色数）。 */
    @GetMapping("/casts")
    public CastListResponse listCasts() {
        List<CastSummary> casts = new ArrayList<>();
        for (NovelNarrationCast c : castService.listAll()) {
            casts.add(toSummary(c));
        }
        return new CastListResponse(casts);
    }

    /** 新建花名册（{@code seriesId}/{@code novelId} 二选一绑定，均空=无绑定的共享册）。 */
    @PostMapping("/casts")
    public ResponseEntity<CastSummary> createCast(@RequestBody CreateCastRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().build();
        }
        NovelNarrationCast created = castService.create(request.name(), request.seriesId(), request.novelId());
        return ResponseEntity.ok(toSummary(created));
    }

    /** 某本小说的默认花名册（{@code castId} 为 {@code null} 表示尚未创建，前端可按需创建）。 */
    @GetMapping("/casts/novel/{novelId}/default")
    public ResponseEntity<DefaultCastResponse> novelDefaultCast(@PathVariable long novelId) {
        NovelNarrationCastService.DefaultCast def = castService.resolveNovelDefaultCast(novelId);
        if (def == null) {
            return ResponseEntity.notFound().build();
        }
        NovelNarrationCast cast = def.cast();
        if (cast != null) {
            return ResponseEntity.ok(new DefaultCastResponse(cast.id(), cast.name(),
                    cast.seriesId(), cast.novelId(), cast.voiceCount()));
        }
        return ResponseEntity.ok(new DefaultCastResponse(null, def.suggestedName(),
                def.seriesId(), def.novelId(), 0));
    }

    /** 取某花名册的全部角色（含音色画像，admin-only）；供弹窗内「选角与音色」按 castId 编辑所选花名册。 */
    @GetMapping("/casts/{id}/voices")
    public ResponseEntity<CastResponse> castVoices(@PathVariable long id) {
        NovelNarrationCast cast = castService.find(id);
        if (cast == null) {
            return ResponseEntity.notFound().build();
        }
        Map<Integer, String> sources = referenceVoiceService.sources(id);
        List<VoiceView> voices = new ArrayList<>();
        for (NarrationCharacter c : castService.voices(id)) {
            voices.add(voiceView(c, sources.get(c.id())));
        }
        return ResponseEntity.ok(new CastResponse(id, cast.name(), voices));
    }

    /** 编辑某角色音色画像并锁定（{@code edited_by_user=1}）。冲突解决「采纳建议 / 改写」复用本端点；「保留」为无操作。 */
    @PutMapping("/cast/voice")
    public ResponseEntity<?> updateVoice(@RequestBody VoiceUpdateRequest request) {
        if (request == null || request.characterId() == null
                || request.controlInstruction() == null || request.controlInstruction().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse(messages.get("narration.error.invalid-voice")));
        }
        long castId;
        if (request.castId() != null && request.castId() > 0) {
            if (!castService.exists(request.castId())) {
                return ResponseEntity.notFound().build();
            }
            castId = request.castId();
        } else if (request.novelId() != null) {
            NovelNarrationCastService.DefaultCast def = castService.resolveNovelDefaultCast(request.novelId());
            if (def == null) {
                return ResponseEntity.notFound().build();
            }
            castId = def.cast() != null ? def.cast().id()
                    : castService.create(def.suggestedName(), def.seriesId(), def.novelId()).id();
        } else {
            return ResponseEntity.badRequest().body(new ErrorResponse(messages.get("narration.error.invalid-voice")));
        }
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

    private static VoiceView voiceView(NarrationCharacter c, String refAudioSource) {
        return new VoiceView(c.id(), c.name(), c.gender(), c.age(),
                c.controlInstruction(), c.editedByUser(), refAudioSource);
    }

    private static CastSummary toSummary(NovelNarrationCast c) {
        return new CastSummary(c.id(), c.name(), c.seriesId(), c.novelId(), c.voiceCount());
    }
}
