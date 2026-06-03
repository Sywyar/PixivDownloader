package top.sywyar.pixivdownload.novel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.ai.narration.NarrationCharacter;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.novel.db.NovelNarrationCast;
import top.sywyar.pixivdownload.novel.db.NovelNarrationCastInsert;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.db.NovelSeries;
import top.sywyar.pixivdownload.tts.narration.NarrationScript;
import top.sywyar.pixivdownload.tts.narration.NarrationScriptService;
import top.sywyar.pixivdownload.util.TimestampUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 朗读花名册（narration cast）编排服务：管理「说话人 → 固定音色画像（Control Instruction）」的名册，供多角色
 * 有声朗读时为每句分配稳定音色。
 *
 * <p>绑定与入册机制与名词映射表（{@link NovelGlossaryService}）<b>完全一致</b>：
 * <ul>
 *   <li><b>一个系列共享一份花名册、无系列的单本各自一份</b>——{@link #resolveNovelDefaultCast} 属系列则取系列册、
 *       否则取该单本自己的册（均按需创建）；同一绑定的默认册至多一张（{@link #create} 幂等复用）。</li>
 *   <li>AI 选角发现的新角色由 {@link #enrollCharacters} 自动并入（按角色名 putIfAbsent，<b>不覆盖</b>已有 /
 *       用户改过的音色），从而同一角色跨章复用同一音色画像，保证「同一个人描述准确一致」。</li>
 * </ul>
 *
 * <p>持久化与 AI 编排解耦：本服务持有 DB + 选角 AI 调用，{@link NarrationScriptService} 仍是纯 AI 编排器
 * （文本 / 名册进、脚本出，不碰 DB）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelNarrationCastService {

    /** 单次发给选角 AI 的「已有角色名」上限，控制 token 体量。 */
    private static final int MAX_KNOWN_NAMES = 200;

    private final NovelMapper novelMapper;
    private final NovelDatabase novelDatabase;
    private final NarrationScriptService narrationScriptService;

    /**
     * 某作品的「默认花名册」解析结果。{@code cast} 为 {@code null} 表示尚未创建，此时
     * {@code suggestedName} + {@code seriesId}/{@code novelId} 给出按需创建所需的名称与绑定。
     */
    public record DefaultCast(NovelNarrationCast cast, String suggestedName, Long seriesId, Long novelId) {}

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
     * 手动整册替换角色：清空后写入给定角色（同 {@code character_id} 覆盖）。始终保留一名旁白（id 0）——
     * 列表中未含旁白时补一名默认旁白。
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
                        c.gender(), c.age(), c.controlInstruction().trim(), now);
                hasNarrator |= c.id() == NarrationCharacter.NARRATOR_ID;
            }
        }
        if (!hasNarrator) {
            NarrationCharacter n = NarrationCharacter.defaultNarrator();
            novelMapper.upsertNarrationVoice(castId, n.id(), n.name(), n.gender(), n.age(),
                    n.controlInstruction(), now);
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
     * 选角并入册：解析作品默认花名册（按需创建）→ 把已有角色发给 AI 复用、选角 → 新角色 putIfAbsent 并入 →
     * 返回持久化后的完整名册。AI 关闭 / 失败时返回已有名册（至少含一名旁白），不抛异常。
     */
    public List<NarrationCharacter> resolveAndEnroll(long novelId, String chapterText) {
        DefaultCast def = resolveNovelDefaultCast(novelId);
        if (def == null) {
            return List.of(NarrationCharacter.defaultNarrator());
        }
        long castId = def.cast() != null ? def.cast().id()
                : create(def.suggestedName(), def.seriesId(), def.novelId()).id();
        List<NarrationCharacter> existing = loadRoster(castId);
        List<String> known = existing.stream()
                .filter(c -> !c.narrator())
                .map(NarrationCharacter::name)
                .limit(MAX_KNOWN_NAMES)
                .toList();
        List<NarrationCharacter> aiRoster = narrationScriptService.buildCast(chapterText, known);
        enrollCharacters(castId, aiRoster);
        return loadRoster(castId);
    }

    /**
     * 用作品的持久化花名册产出整段文本的多角色朗读脚本：选角入册 + 逐句归属 + 合成每句 Control Instruction。
     */
    public NarrationScript narrate(long novelId, String chapterText, List<String> sentences) {
        List<NarrationCharacter> roster = resolveAndEnroll(novelId, chapterText);
        return narrationScriptService.buildScript(roster, sentences);
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

    /**
     * AI 选角结果并入持久化名册：按<b>角色名</b> putIfAbsent——已在册的角色（含用户改过的音色）保持不变，
     * 只为新名字分配新的 {@code character_id} 并写入。同时确保旁白行（id 0）存在。
     */
    @Transactional
    void enrollCharacters(long castId, List<NarrationCharacter> aiRoster) {
        long now = TimestampUtils.nowMillis();
        List<NarrationCharacter> existing = novelMapper.findNarrationVoices(castId);
        Set<String> existingNames = new LinkedHashSet<>();
        int maxId = -1;
        boolean hasNarrator = false;
        for (NarrationCharacter c : existing) {
            existingNames.add(normName(c.name()));
            maxId = Math.max(maxId, c.id());
            hasNarrator |= c.narrator();
        }
        boolean changed = false;

        // 确保旁白行存在：优先用 AI 选出的旁白音色，否则默认旁白。
        if (!hasNarrator) {
            NarrationCharacter aiNarrator = firstNarrator(aiRoster);
            String instruction = aiNarrator != null ? aiNarrator.controlInstruction()
                    : NarrationCharacter.DEFAULT_NARRATOR_INSTRUCTION;
            novelMapper.insertNarrationVoiceIfAbsent(castId, NarrationCharacter.NARRATOR_ID, "Narrator",
                    "unknown", "unknown", instruction, now);
            existingNames.add(normName("Narrator"));
            maxId = Math.max(maxId, NarrationCharacter.NARRATOR_ID);
            changed = true;
        }

        if (aiRoster != null) {
            for (NarrationCharacter c : aiRoster) {
                if (c == null || c.narrator()) continue;
                String name = c.name() == null ? "" : c.name().trim();
                if (name.isEmpty() || c.controlInstruction() == null || c.controlInstruction().isBlank()) {
                    continue;
                }
                if (!existingNames.add(normName(name))) {
                    continue; // 已在册：不覆盖
                }
                int newId = ++maxId;
                novelMapper.insertNarrationVoiceIfAbsent(castId, newId, name,
                        c.gender(), c.age(), c.controlInstruction().trim(), now);
                changed = true;
            }
        }

        if (changed) {
            novelMapper.touchNarrationCast(castId, now);
        }
    }

    private static NarrationCharacter firstNarrator(List<NarrationCharacter> roster) {
        if (roster == null) return null;
        for (NarrationCharacter c : roster) {
            if (c != null && c.narrator()) return c;
        }
        return null;
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
