package top.sywyar.pixivdownload.novel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelGlossary;
import top.sywyar.pixivdownload.novel.db.NovelGlossaryEntry;
import top.sywyar.pixivdownload.novel.db.NovelGlossaryInsert;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.db.NovelSeries;
import top.sywyar.pixivdownload.util.TimestampUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 名词映射表（glossary）编排服务：管理「原文专有名词 → 各目标语言译名」的映射表，供 AI 翻译时统一术语。
 *
 * <p>一张映射表默认绑定到某个小说系列或某本单独小说（用于「翻译某作品时默认带出哪张表」），但可被任意
 * 作品复用。条目按 {@code (glossary_id, source, lang_code)} 唯一，一表内同一原文可对多种语言各有译名。
 * AI 在翻译中遇到的新名词由 {@link #mergeAiTerms} 自动并入（已存在条目不覆盖）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelGlossaryService {

    private final NovelMapper novelMapper;
    private final NovelDatabase novelDatabase;

    /**
     * 某作品的「默认映射表」解析结果。{@code glossary} 为 {@code null} 表示尚未创建，此时
     * {@code suggestedName} + {@code seriesId}/{@code novelId} 给出按需创建所需的名称与绑定。
     */
    public record DefaultGlossary(NovelGlossary glossary, String suggestedName,
                                  Long seriesId, Long novelId) {}

    public List<NovelGlossary> listAll() {
        return novelMapper.findAllGlossaries();
    }

    public NovelGlossary find(long id) {
        return novelMapper.findGlossaryById(id);
    }

    public List<NovelGlossaryEntry> entries(long glossaryId) {
        return novelMapper.findGlossaryEntries(glossaryId);
    }

    public boolean exists(long id) {
        return novelMapper.countGlossaryById(id) > 0;
    }

    public NovelGlossary create(String name, Long seriesId, Long novelId) {
        Long boundSeries = seriesId != null && seriesId > 0 ? seriesId : null;
        Long boundNovel = novelId != null && novelId > 0 ? novelId : null;
        // 绑定到系列 / 单本的默认表至多一张：已存在则直接复用，避免重复创建
        // （按需创建与编辑器显式创建可能对同一作品并发触发，无绑定的「新建表」不受此约束）。
        if (boundSeries != null) {
            NovelGlossary existing = novelMapper.findGlossaryBySeriesId(boundSeries);
            if (existing != null) return existing;
        } else if (boundNovel != null) {
            NovelGlossary existing = novelMapper.findGlossaryByNovelId(boundNovel);
            if (existing != null) return existing;
        }
        long now = TimestampUtils.nowMillis();
        NovelGlossaryInsert insert = new NovelGlossaryInsert();
        insert.setName(normalizeName(name, boundSeries, boundNovel));
        insert.setSeriesId(boundSeries);
        insert.setNovelId(boundNovel);
        insert.setCreatedTime(now);
        insert.setUpdatedTime(now);
        novelMapper.insertGlossary(insert);
        return novelMapper.findGlossaryById(insert.getId());
    }

    public NovelGlossary rename(long id, String name) {
        if (name == null || name.isBlank()) {
            return novelMapper.findGlossaryById(id);
        }
        novelMapper.updateGlossaryName(id, name.trim(), TimestampUtils.nowMillis());
        return novelMapper.findGlossaryById(id);
    }

    @Transactional
    public void delete(long id) {
        novelMapper.deleteGlossaryEntries(id);
        novelMapper.deleteGlossary(id);
    }

    /** 手动整表替换条目：清空后写入给定条目（同 (原文,语言) 覆盖）。 */
    @Transactional
    public void replaceEntries(long glossaryId, List<NovelGlossaryEntry> entries) {
        long now = TimestampUtils.nowMillis();
        novelMapper.deleteGlossaryEntries(glossaryId);
        if (entries != null) {
            for (NovelGlossaryEntry e : entries) {
                if (e == null) continue;
                String source = trimOrNull(e.source());
                String lang = trimOrNull(e.langCode());
                String target = trimOrNull(e.target());
                if (source == null || lang == null || target == null) continue;
                novelMapper.upsertGlossaryEntry(glossaryId, source, lang, target, now);
            }
        }
        novelMapper.touchGlossary(glossaryId, now);
    }

    /** AI 翻译返回的新名词自动并入：写入 {@code (source, langCode, target)}，已存在的不覆盖。 */
    @Transactional
    public void mergeAiTerms(long glossaryId, String langCode, List<NovelGlossaryEntry> terms) {
        if (terms == null || terms.isEmpty() || langCode == null || langCode.isBlank()) {
            return;
        }
        if (!exists(glossaryId)) {
            return;
        }
        long now = TimestampUtils.nowMillis();
        int merged = 0;
        for (NovelGlossaryEntry t : terms) {
            if (t == null) continue;
            String source = trimOrNull(t.source());
            String target = trimOrNull(t.target());
            if (source == null || target == null) continue;
            novelMapper.insertGlossaryEntryIfAbsent(glossaryId, source, langCode.trim(), target, now);
            merged++;
        }
        if (merged > 0) {
            novelMapper.touchGlossary(glossaryId, now);
        }
    }

    /** 解析某本小说的默认映射表：属于系列则用系列表，否则用该小说自己的表（均按需创建）。 */
    public DefaultGlossary resolveNovelDefault(long novelId) {
        NovelRecord record = novelDatabase.getNovel(novelId);
        if (record == null) {
            return null;
        }
        if (record.seriesId() != null && record.seriesId() > 0) {
            long seriesId = record.seriesId();
            return new DefaultGlossary(novelMapper.findGlossaryBySeriesId(seriesId),
                    seriesDefaultName(seriesId), seriesId, null);
        }
        return new DefaultGlossary(novelMapper.findGlossaryByNovelId(novelId),
                novelDefaultName(record), null, novelId);
    }

    /** 解析某小说系列的默认映射表（默认表可能尚未创建）。 */
    public DefaultGlossary resolveSeriesDefault(long seriesId) {
        return new DefaultGlossary(novelMapper.findGlossaryBySeriesId(seriesId),
                seriesDefaultName(seriesId), seriesId, null);
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
        return "glossary";
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 把映射表条目转成发给 AI 的术语列表（受 {@code limit} 上限约束，0 / 负数表示不限）。 */
    public static List<NovelGlossaryEntry> capped(List<NovelGlossaryEntry> entries, int limit) {
        if (entries == null || entries.isEmpty()) return List.of();
        if (limit <= 0 || entries.size() <= limit) return entries;
        return new ArrayList<>(entries.subList(0, limit));
    }
}
