package top.sywyar.pixivdownload.novel;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.ai.narration.NarrationCharacter;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.novel.db.NovelNarrationCast;
import top.sywyar.pixivdownload.novel.db.NovelNarrationScriptRow;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.tts.narration.NarrationAudioService;
import top.sywyar.pixivdownload.tts.narration.NarrationScript;
import top.sywyar.pixivdownload.tts.narration.NarrationScriptService;
import top.sywyar.pixivdownload.tts.narration.NarrationSentence;
import top.sywyar.pixivdownload.tts.narration.NarrationSentenceSplitter;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;
import top.sywyar.pixivdownload.util.TimestampUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 「AI 听小说」整章脚本编排 + 持久化服务：负责断句、按分段字数驱动 {@link NovelNarrationCastService} 分析、把
 * <b>整章逐句脚本持久化</b>（{@code novel_narration_scripts}），并按持久化行 + <b>活花名册</b>合成单行音频。
 *
 * <p>LLM 分析昂贵：分析结果（逐句归属）持久化后<b>重播不重算</b>，只在用户主动「重新分析」（{@code force}）时重算。
 * 脚本<b>不</b>存 controlInstruction —— 单行合成时按 speaker 从<b>当前活花名册</b>取基底画像再合并 delivery，
 * 因此音色编辑 / 冲突解决<b>即时生效</b>、无需重分析（前端音频缓存键含 {@code castUpdatedTime} 自动失效）。
 */
@Slf4j
@Service
public class NovelNarrationScriptService {

    private final NovelNarrationCastService castService;
    private final NovelDatabase novelDatabase;
    private final NovelMapper novelMapper;
    private final NarrationAudioService narrationAudioService;
    private final ObjectMapper objectMapper;

    public NovelNarrationScriptService(NovelNarrationCastService castService,
                                       NovelDatabase novelDatabase,
                                       NovelMapper novelMapper,
                                       NarrationAudioService narrationAudioService,
                                       ObjectMapper objectMapper) {
        this.castService = castService;
        this.novelDatabase = novelDatabase;
        this.novelMapper = novelMapper;
        this.narrationAudioService = narrationAudioService;
        this.objectMapper = objectMapper;
    }

    /** 整章逐句脚本的持久化形态（也是 {@code script_json} 的序列化形态）。下标 {@code i}、说话人 {@code speaker} 用短键。 */
    public record ScriptLine(
            @JsonProperty("i") int index,
            @JsonProperty("speaker") int speakerId,
            @JsonProperty("speakerName") String speakerName,
            @JsonProperty("delivery") String delivery,
            @JsonProperty("paragraphIndex") int paragraphIndex,
            @JsonProperty("text") String text) {
    }

    /**
     * 一次 {@link #getOrAnalyze} 的结果。
     *
     * @param lines           逐句脚本（与正文句子等长、按下标升序）
     * @param castId          所用花名册 ID（{@code 0} 表示纯旁白 / 无花名册）
     * @param castUpdatedTime 花名册 {@code updated_time}（供前端音频缓存键失效）
     * @param segmentSize     本次分析所用分段字数
     * @param analyzedTime    脚本分析时间（供前端音频缓存随脚本版本失效）
     * @param conflicts       未解决冲突（仅在本次<b>新分析</b>时有值；命中缓存时为空）
     */
    public record ChapterScript(List<ScriptLine> lines, long castId, long castUpdatedTime,
                                int segmentSize, long analyzedTime, List<NarrationConflictReport> conflicts) {
    }

    /** 整章正文过大、不适合一次性分析时抛出，由控制器转 400 并提示上限 / 调整范围。 */
    public static class ContentTooLargeException extends RuntimeException {
        private final int limit;

        public ContentTooLargeException(int limit) {
            super("narration content too large");
            this.limit = limit;
        }

        public int limit() {
            return limit;
        }
    }

    /**
     * 取某本小说在某语言下的整章逐句脚本：命中持久化且非 {@code force} 时直接返回缓存（不调 LLM）；否则断句 →
     * 按 {@code segmentSize} 分析 → 落库（含 {@code segment_size}）后返回。
     *
     * @param lang         内容语言（{@code null}/空=原文）；与详情页内容语言切换一致
     * @param segmentSize  分段字数（{@code <=0}=整章一批）
     * @param force        {@code true} 强制重新分析并覆盖缓存
     * @param maxChars     整章正文字数上限；新分析时正文超限抛 {@link ContentTooLargeException}
     */
    public ChapterScript getOrAnalyze(long novelId, String lang, int segmentSize, boolean force, int maxChars) {
        String langKey = lang == null ? "" : lang.trim();
        int normalizedSegment = Math.max(0, segmentSize);
        if (!force) {
            NovelNarrationScriptRow row = novelMapper.findNarrationScript(novelId, langKey);
            if (row != null) {
                return new ChapterScript(parseLines(row.scriptJson()), row.castId(),
                        castUpdatedTime(row.castId()), row.segmentSize(), row.analyzedTime(), List.of());
            }
        }
        String raw = resolveRawContent(novelId, langKey);
        if (maxChars > 0 && raw.length() > maxChars) {
            throw new ContentTooLargeException(maxChars);
        }
        List<NarrationSentence> sentences = NarrationSentenceSplitter.split(raw);
        ChapterNarration narration = castService.analyzeChapter(novelId, sentences, normalizedSegment);
        NarrationScript script = narration.script();

        List<ScriptLine> lines = new ArrayList<>(script.lines().size());
        for (NarrationScript.Line l : script.lines()) {
            int para = (l.index() >= 0 && l.index() < sentences.size())
                    ? sentences.get(l.index()).paragraphIndex() : -1;
            lines.add(new ScriptLine(l.index(), l.speakerId(), l.speakerName(),
                    l.delivery() == null ? "" : l.delivery(), para, l.text()));
        }
        long castId = resolveCastId(novelId);
        long now = TimestampUtils.nowMillis();
        novelMapper.upsertNarrationScript(novelId, langKey, castId, normalizedSegment, now, writeLines(lines));
        return new ChapterScript(lines, castId, castUpdatedTime(castId), normalizedSegment, now, narration.conflicts());
    }

    /**
     * 合成持久化脚本中的一行：按该行 speaker 从<b>活花名册</b>取基底画像、合并该行 delivery，再交给朗读引擎合成。
     * 编辑音色 / 解决冲突会立即体现在合成结果里（基底永远从活花名册派生，不读脚本里的旧画像）。
     *
     * @return 合成音频；脚本不存在 / 行下标越界时返回 {@code null}（控制器转 404）
     * @throws top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceException 引擎不可用 / 合成失败（控制器转 502）
     */
    public NarrationAudio synthesizeLine(long novelId, String lang, int lineIndex) {
        String langKey = lang == null ? "" : lang.trim();
        NovelNarrationScriptRow row = novelMapper.findNarrationScript(novelId, langKey);
        if (row == null) {
            return null;
        }
        List<ScriptLine> lines = parseLines(row.scriptJson());
        if (lineIndex < 0 || lineIndex >= lines.size()) {
            return null;
        }
        ScriptLine line = lines.get(lineIndex);
        List<NarrationCharacter> roster = row.castId() > 0
                ? castService.voices(row.castId())
                : List.of(NarrationCharacter.defaultNarrator());

        String base = null;
        String speakerName = line.speakerName();
        for (NarrationCharacter c : roster) {
            if (c.id() == line.speakerId()) {
                base = c.controlInstruction();
                speakerName = c.name();
                break;
            }
        }
        if (base == null || base.isBlank()) {
            for (NarrationCharacter c : roster) {
                if (c.narrator()) {
                    base = c.controlInstruction();
                    break;
                }
            }
        }
        if (base == null || base.isBlank()) {
            base = NarrationCharacter.DEFAULT_NARRATOR_INSTRUCTION;
        }
        String combined = NarrationScriptService.combine(base, line.delivery());
        NarrationScript.Line scriptLine = new NarrationScript.Line(
                line.index(), line.text(), line.speakerId(), speakerName, line.delivery(), combined);
        return narrationAudioService.synthesizeLine(scriptLine, langKey.isEmpty() ? null : langKey);
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private String resolveRawContent(long novelId, String langKey) {
        NovelRecord rec = novelDatabase.getNovel(novelId);
        String original = (rec == null || rec.rawContent() == null) ? "" : rec.rawContent();
        if (langKey.isEmpty()) {
            return original;
        }
        // 与详情页一致：该语言有非空译文则用译文（与渲染源同源，保证 paragraphIndex 对齐），否则回退原文。
        String translated = novelDatabase.getTranslationContent(novelId, langKey);
        return (translated != null && !translated.isBlank()) ? translated : original;
    }

    /** 分析后解析实际所用花名册 ID（analyzeChapter 已按需创建）；无花名册返回 0。 */
    private long resolveCastId(long novelId) {
        NovelNarrationCastService.DefaultCast def = castService.resolveNovelDefaultCast(novelId);
        return (def != null && def.cast() != null) ? def.cast().id() : 0L;
    }

    private long castUpdatedTime(long castId) {
        if (castId <= 0) {
            return 0L;
        }
        NovelNarrationCast cast = castService.find(castId);
        return cast == null ? 0L : cast.updatedTime();
    }

    private String writeLines(List<ScriptLine> lines) {
        try {
            return objectMapper.writeValueAsString(lines);
        } catch (Exception e) {
            // 不应发生（纯值对象序列化）；记日志后退回空数组，避免阻断分析落库
            log.warn("serialize narration script failed: {}", e.getMessage());
            return "[]";
        }
    }

    private List<ScriptLine> parseLines(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ScriptLine> lines = objectMapper.readValue(json, new TypeReference<List<ScriptLine>>() {});
            return lines == null ? List.of() : lines;
        } catch (Exception e) {
            log.warn("parse narration script failed: {}", e.getMessage());
            return List.of();
        }
    }
}
