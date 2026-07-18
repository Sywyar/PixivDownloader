package top.sywyar.pixivdownload.novel.db.series;

import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkTag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 小说系列目录及其装饰标签的插件私有读取门面。 */
public final class NovelSeriesCatalogRepository {

    private final NovelMapper novelMapper;

    public NovelSeriesCatalogRepository(NovelMapper novelMapper) {
        this.novelMapper = Objects.requireNonNull(novelMapper, "novelMapper");
    }

    public List<NovelSeriesCatalogRow> findAll() {
        List<NovelSeriesCatalogRow> rows = novelMapper.findNovelSeriesCatalog();
        return rows == null || rows.isEmpty() ? List.of() : List.copyOf(rows);
    }

    /** 批量读取系列标签；无标签的系列不出现在结果中。 */
    public Map<Long, List<WorkTag>> findTags(Collection<Long> seriesIds) {
        if (seriesIds == null || seriesIds.isEmpty()) {
            return Map.of();
        }
        List<NovelSeriesTagRow> rows = novelMapper.findNovelSeriesTagsBySeriesIds(seriesIds);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<WorkTag>> grouped = new LinkedHashMap<>();
        for (NovelSeriesTagRow row : rows) {
            if (row == null) {
                continue;
            }
            grouped.computeIfAbsent(row.seriesId(), ignored -> new ArrayList<>())
                    .add(new WorkTag(row.tagId(), row.name(), row.translatedName()));
        }
        grouped.replaceAll((ignored, tags) -> List.copyOf(tags));
        return Collections.unmodifiableMap(grouped);
    }
}
