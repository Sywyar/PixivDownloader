package top.sywyar.pixivdownload.novel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.ai.narration.NarrationCharacter;
import top.sywyar.pixivdownload.ai.narration.NarrationConflict;
import top.sywyar.pixivdownload.ai.narration.NarrationLineVoice;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.novel.db.NovelNarrationCast;
import top.sywyar.pixivdownload.novel.db.NovelNarrationCastInsert;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.db.NovelSeries;
import top.sywyar.pixivdownload.tts.narration.NarrationScript;
import top.sywyar.pixivdownload.tts.narration.NarrationScriptService;
import top.sywyar.pixivdownload.tts.narration.NarrationSegmentAnalysis;
import top.sywyar.pixivdownload.util.TimestampUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 朗读花名册（narration cast）编排服务：管理「说话人 → 固定音色画像（Control Instruction）」的名册，并以
 * <b>合并单次按段分析</b>驱动一章小说的多角色朗读——逐段把句子连同当前花名册发给 LLM
 * （{@link NarrationScriptService#analyzeSegment}），一次拿到逐句归属 + 新角色 + 兼容性补充 + 冲突上报，
 * 然后落库、按编辑来源路由。
 *
 * <p>绑定与入册机制与名词映射表（{@link NovelGlossaryService}）<b>一致</b>：
 * <ul>
 *   <li><b>一个系列共享一份花名册、无系列的单本各自一份</b>——{@link #resolveNovelDefaultCast} 属系列则取系列册、
 *       否则取该单本自己的册（均按需创建）。</li>
 *   <li>新角色按<b>角色名</b> putIfAbsent 入册（{@code edited_by_user=0}），从而同一角色跨章复用同一音色画像。</li>
 * </ul>
 *
 * <p><b>入册与冲突路由</b>（{@code edited_by_user} 标记 0=AI 生成 / 1=用户手改锁定）：
 * <ul>
 *   <li>{@code updatedCharacters}（兼容性补充）：仅对 {@code edited_by_user=0} 的角色刷新画像；用户锁定的<b>忽略</b>。</li>
 *   <li>{@code conflicts}（完全相反 / 明显不完整）：对 {@code edited_by_user=0} 的角色<b>自动采纳建议</b>覆盖画像；
 *       对用户锁定角色<b>绝不覆盖</b>，收集成 {@link NarrationConflictReport} 待用户处理。</li>
 * </ul>
 *
 * <p>持久化与 AI 编排解耦：本服务持有 DB + 按段编排，{@link NarrationScriptService} 仍是纯 AI 分析器
 * （花名册 / 句子进、分段结果出，不碰 DB、不断句）。
 */
@Slf4j
@Service
public class NovelNarrationCastService {

    /** 每段发给 AI 的句子数（控制单次响应体量与下标规模；建议 30–40）。 */
    static final int SEGMENT_SIZE = 35;

    private final NovelMapper novelMapper;
    private final NovelDatabase novelDatabase;
    private final NarrationScriptService narrationScriptService;
    private final TransactionOperations transactionOperations;

    public NovelNarrationCastService(NovelMapper novelMapper,
                                     NovelDatabase novelDatabase,
                                     NarrationScriptService narrationScriptService,
                                     PlatformTransactionManager transactionManager) {
        this(novelMapper, novelDatabase, narrationScriptService, new TransactionTemplate(transactionManager));
    }

    NovelNarrationCastService(NovelMapper novelMapper,
                              NovelDatabase novelDatabase,
                              NarrationScriptService narrationScriptService,
                              TransactionOperations transactionOperations) {
        this.novelMapper = novelMapper;
        this.novelDatabase = novelDatabase;
        this.narrationScriptService = narrationScriptService;
        this.transactionOperations = transactionOperations;
    }

    /**
     * 某作品的「默认花名册」解析结果。{@code cast} 为 {@code null} 表示尚未创建，此时
     * {@code suggestedName} + {@code seriesId}/{@code novelId} 给出按需创建所需的名称与绑定。
     */
    public record DefaultCast(NovelNarrationCast cast, String suggestedName, Long seriesId, Long novelId) {}

    /** 一段处理后的花名册变更结果：新角色「临时 id → 真实 id」映射 + 本段未解决的冲突。 */
    record SegmentRosterResult(Map<Integer, Integer> tempToReal, List<NarrationConflictReport> unresolvedConflicts) {}

    public List<NovelNarrationCast> listAll() {
        return novelMapper.findAllNarrationCasts();
    }

    public NovelNarrationCast find(long id) {
        return novelMapper.findNarrationCastById(id);
    }

    public boolean exists(long id) {
        return novelMapper.countNarrationCastById(id) > 0;
    }

    /** 名册的全部角色（旁白居首，id 0）。 */
    public List<NarrationCharacter> voices(long castId) {
        return loadRoster(castId);
    }

    public NovelNarrationCast create(String name, Long seriesId, Long novelId) {
        Long boundSeries = seriesId != null && seriesId > 0 ? seriesId : null;
        Long boundNovel = novelId != null && novelId > 0 ? novelId : null;
        // 绑定到系列 / 单本的默认册至多一张：已存在则直接复用（与 NovelGlossaryService.create 一致，不靠 DB 唯一约束）。
        if (boundSeries != null) {
            NovelNarrationCast existing = novelMapper.findNarrationCastBySeriesId(boundSeries);
            if (existing != null) return existing;
        } else if (boundNovel != null) {
            NovelNarrationCast existing = novelMapper.findNarrationCastByNovelId(boundNovel);
            if (existing != null) return existing;
        }
        long now = TimestampUtils.nowMillis();
        NovelNarrationCastInsert insert = new NovelNarrationCastInsert();
        insert.setName(normalizeName(name, boundSeries, boundNovel));
        insert.setSeriesId(boundSeries);
        insert.setNovelId(boundNovel);
        insert.setCreatedTime(now);
        insert.setUpdatedTime(now);
        novelMapper.insertNarrationCast(insert);
        return novelMapper.findNarrationCastById(insert.getId());
    }

    public NovelNarrationCast rename(long id, String name) {
        if (name == null || name.isBlank()) {
            return novelMapper.findNarrationCastById(id);
        }
        novelMapper.updateNarrationCastName(id, name.trim(), TimestampUtils.nowMillis());
        return novelMapper.findNarrationCastById(id);
    }

    @Transactional
    public void delete(long id) {
        novelMapper.deleteNarrationVoices(id);
        novelMapper.deleteNarrationCast(id);
    }

    /**
     * 手动整册替换角色：清空后写入给定角色（同 {@code character_id} 覆盖）。用户手改的角色一律标记
     * {@code edited_by_user=1}（AI 之后绝不覆盖其画像，只能以冲突形式弹给用户）。始终保留一名旁白（id 0）——
     * 列表中未含旁白时补一名默认旁白（来源仍记为 AI 生成 {@code edited_by_user=0}，允许后续 AI 补充）。
     */
    @Transactional
    public void replaceVoices(long castId, List<NarrationCharacter> roster) {
        long now = TimestampUtils.nowMillis();
        novelMapper.deleteNarrationVoices(castId);
        boolean hasNarrator = false;
        if (roster != null) {
            for (NarrationCharacter c : roster) {
                if (c == null || c.name() == null || c.name().isBlank()) continue;
                if (c.controlInstruction() == null || c.controlInstruction().isBlank()) continue;
                novelMapper.upsertNarrationVoice(castId, c.id(), c.name().trim(),
                        c.gender(), c.age(), c.controlInstruction().trim(), true, now);
                hasNarrator |= c.id() == NarrationCharacter.NARRATOR_ID;
            }
        }
        if (!hasNarrator) {
            NarrationCharacter n = NarrationCharacter.defaultNarrator();
            novelMapper.upsertNarrationVoice(castId, n.id(), n.name(), n.gender(), n.age(),
                    n.controlInstruction(), false, now);
        }
        novelMapper.touchNarrationCast(castId, now);
    }

    /** 解析某本小说的默认花名册：属于系列则用系列册，否则用该小说自己的册（均按需创建）。 */
    public DefaultCast resolveNovelDefaultCast(long novelId) {
        NovelRecord record = novelDatabase.getNovel(novelId);
        if (record == null) {
            return null;
        }
        if (record.seriesId() != null && record.seriesId() > 0) {
            long seriesId = record.seriesId();
            return new DefaultCast(novelMapper.findNarrationCastBySeriesId(seriesId),
                    seriesDefaultName(seriesId), seriesId, null);
        }
        return new DefaultCast(novelMapper.findNarrationCastByNovelId(novelId),
                novelDefaultName(record), null, novelId);
    }

    /** 解析某小说系列的默认花名册（默认册可能尚未创建）。 */
    public DefaultCast resolveSeriesDefaultCast(long seriesId) {
        return new DefaultCast(novelMapper.findNarrationCastBySeriesId(seriesId),
                seriesDefaultName(seriesId), seriesId, null);
    }

    /**
     * 用作品的持久化花名册分析整章句子，产出完整逐句朗读脚本 + 未解决冲突：解析默认花名册（按需创建）→ 把句子
     * 按 {@link #SEGMENT_SIZE} 切段 → 逐段携带刷新后的花名册做合并单次分析 → 新角色入册、补充 / 冲突按编辑来源
     * 路由 → 把逐句临时 id 重映射成真实 id 累积 → 最终用刷新后的名册装配脚本。
     *
     * <p>断句是调用方职责（{@code sentences} 已切好按序传入）。AI 关闭 / 失败 / 某段不可解析时该段整段归旁白，
     * 结果始终与输入句子等长、永不缺句。
     */
    public ChapterNarration analyzeChapter(long novelId, List<String> sentences) {
        List<String> safeSentences = sentences == null ? List.of() : sentences;
        if (safeSentences.isEmpty()) {
            NarrationScript empty = narrationScriptService.buildScript(
                    List.of(NarrationCharacter.defaultNarrator()), List.of(), List.of());
            return new ChapterNarration(empty, List.of());
        }
        DefaultCast def = resolveNovelDefaultCast(novelId);
        if (def == null) {
            // 作品不存在：全篇单一默认旁白，不落任何花名册。
            NarrationScript script = narrationScriptService.buildScript(
                    List.of(NarrationCharacter.defaultNarrator()), safeSentences, List.of());
            return new ChapterNarration(script, List.of());
        }
        long castId = def.cast() != null ? def.cast().id()
                : create(def.suggestedName(), def.seriesId(), def.novelId()).id();
        ensureNarratorPersisted(castId);

        List<NarrationLineVoice> allLineVoices = new ArrayList<>(safeSentences.size());
        Map<Integer, NarrationConflictReport> unresolved = new LinkedHashMap<>();

        int total = safeSentences.size();
        for (int start = 0; start < total; start += SEGMENT_SIZE) {
            int end = Math.min(start + SEGMENT_SIZE, total);
            List<String> segment = safeSentences.subList(start, end);

            List<NarrationCharacter> roster = loadRoster(castId);
            int nextId = nextCharacterId(roster);
            NarrationSegmentAnalysis analysis = narrationScriptService.analyzeSegment(roster, segment, nextId);

            SegmentRosterResult res = processSegmentRoster(castId, roster, analysis);

            Set<Integer> rosterIds = new java.util.LinkedHashSet<>();
            for (NarrationCharacter c : roster) {
                rosterIds.add(c.id());
            }
            for (NarrationLineVoice lv : analysis.lines()) {
                int realSpeaker = remapSpeaker(lv.speakerId(), rosterIds, res.tempToReal());
                allLineVoices.add(new NarrationLineVoice(start + lv.index(), realSpeaker, lv.delivery()));
            }
            for (NarrationConflictReport report : res.unresolvedConflicts()) {
                unresolved.put(report.characterId(), report);
            }
        }

        List<NarrationCharacter> finalRoster = loadRoster(castId);
        NarrationScript script = narrationScriptService.buildScript(finalRoster, safeSentences, allLineVoices);
        return new ChapterNarration(script, new ArrayList<>(unresolved.values()));
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    /** 读取持久化名册；缺旁白行时在内存里兜底补一名默认旁白居首。 */
    List<NarrationCharacter> loadRoster(long castId) {
        List<NarrationCharacter> voices = novelMapper.findNarrationVoices(castId);
        boolean hasNarrator = voices.stream().anyMatch(NarrationCharacter::narrator);
        if (hasNarrator) {
            return voices;
        }
        List<NarrationCharacter> out = new ArrayList<>(voices.size() + 1);
        out.add(NarrationCharacter.defaultNarrator());
        out.addAll(voices);
        return out;
    }

    /** 确保旁白行（id 0）已持久化（默认音色、AI 生成来源），以便后续 AI 补充 / 冲突能对旁白生效。 */
    void ensureNarratorPersisted(long castId) {
        transactionOperations.execute(status -> {
            novelMapper.insertNarrationVoiceIfAbsent(castId, NarrationCharacter.NARRATOR_ID, "Narrator",
                    "unknown", "unknown", NarrationCharacter.DEFAULT_NARRATOR_INSTRUCTION, false,
                    TimestampUtils.nowMillis());
            return null;
        });
    }

    /**
     * 把一段分析结果落库并路由：① 新角色按名字 putIfAbsent 入册（{@code edited_by_user=0}）、建立临时 id → 真实
     * id 映射；② 兼容性补充仅对未锁定角色刷新画像；③ 冲突对未锁定角色自动采纳建议、对用户锁定角色收集为待处理
     * 冲突（绝不覆盖）。返回临时 id 映射与未解决冲突供编排层重映射逐句 speaker / 提示用户。
     */
    SegmentRosterResult processSegmentRoster(long castId, List<NarrationCharacter> roster,
                                             NarrationSegmentAnalysis analysis) {
        SegmentRosterResult result = transactionOperations.execute(
                status -> processSegmentRosterInTransaction(castId, roster, analysis));
        return result == null ? new SegmentRosterResult(Map.of(), List.of()) : result;
    }

    private SegmentRosterResult processSegmentRosterInTransaction(long castId, List<NarrationCharacter> roster,
                                                                  NarrationSegmentAnalysis analysis) {
        long now = TimestampUtils.nowMillis();
        Map<String, Integer> nameToId = new LinkedHashMap<>();
        Map<Integer, NarrationCharacter> byId = new LinkedHashMap<>();
        int maxId = NarrationCharacter.NARRATOR_ID;
        for (NarrationCharacter c : roster) {
            nameToId.putIfAbsent(normName(c.name()), c.id());
            byId.putIfAbsent(c.id(), c);
            maxId = Math.max(maxId, c.id());
        }
        boolean changed = false;

        // ① 新角色入册（按名字 putIfAbsent，不覆盖已有 / 用户改过的音色），返回 临时 id → 真实 id 映射。
        Map<Integer, Integer> tempToReal = new LinkedHashMap<>();
        for (NarrationCharacter c : analysis.newCharacters()) {
            if (c == null) continue;
            String name = c.name() == null ? "" : c.name().trim();
            if (name.isEmpty() || c.controlInstruction() == null || c.controlInstruction().isBlank()) continue;
            String key = normName(name);
            Integer realId = nameToId.get(key);
            if (realId == null) {
                realId = ++maxId;
                novelMapper.insertNarrationVoiceIfAbsent(castId, realId, name,
                        c.gender(), c.age(), c.controlInstruction().trim(), false, now);
                nameToId.put(key, realId);
                changed = true;
            }
            tempToReal.put(c.id(), realId);
        }

        // ② 兼容性补充：仅刷新 AI 生成（未锁定）的已有角色画像；用户锁定的忽略。
        for (Map.Entry<Integer, String> e : analysis.updatedCharacters().entrySet()) {
            NarrationCharacter ex = byId.get(e.getKey());
            if (ex == null || ex.editedByUser()) continue;
            String instr = e.getValue();
            if (instr == null || instr.isBlank()) continue;
            novelMapper.updateNarrationVoiceInstruction(castId, ex.id(), instr.trim(), false);
            changed = true;
        }

        // ③ 冲突路由：AI 生成角色自动采纳建议覆盖；用户锁定角色保留原值、收集为待处理冲突。
        List<NarrationConflictReport> unresolved = new ArrayList<>();
        for (NarrationConflict conflict : analysis.conflicts()) {
            NarrationCharacter ex = byId.get(conflict.characterId());
            if (ex == null) continue;
            if (ex.editedByUser()) {
                unresolved.add(new NarrationConflictReport(ex.id(), ex.name(), conflict.type(),
                        conflict.reason(), ex.controlInstruction(), conflict.suggestion()));
            } else {
                novelMapper.updateNarrationVoiceInstruction(castId, ex.id(), conflict.suggestion(), false);
                changed = true;
            }
        }

        if (changed) {
            novelMapper.touchNarrationCast(castId, now);
        }
        return new SegmentRosterResult(tempToReal, unresolved);
    }

    /** 把逐句 speaker 的段内引用映射成真实 id：已有名册 id 原样保留；新角色临时 id 换成入册后的真实 id；其余归旁白。 */
    private static int remapSpeaker(int speakerId, Set<Integer> rosterIds, Map<Integer, Integer> tempToReal) {
        if (rosterIds.contains(speakerId)) {
            return speakerId;
        }
        Integer real = tempToReal.get(speakerId);
        return real != null ? real : NarrationCharacter.NARRATOR_ID;
    }

    private static int nextCharacterId(List<NarrationCharacter> roster) {
        int max = NarrationCharacter.NARRATOR_ID;
        for (NarrationCharacter c : roster) {
            max = Math.max(max, c.id());
        }
        return max + 1;
    }

    private static String normName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private String seriesDefaultName(long seriesId) {
        NovelSeries series = novelDatabase.getSeries(seriesId);
        if (series != null && series.title() != null && !series.title().isBlank()) {
            return series.title().trim();
        }
        return "series-" + seriesId;
    }

    private String novelDefaultName(NovelRecord record) {
        if (record != null && record.title() != null && !record.title().isBlank()) {
            return record.title().trim();
        }
        long id = record == null ? 0 : record.novelId();
        return "novel-" + id;
    }

    private static String normalizeName(String name, Long seriesId, Long novelId) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        if (seriesId != null && seriesId > 0) return "series-" + seriesId;
        if (novelId != null && novelId > 0) return "novel-" + novelId;
        return "narration-cast";
    }
}
