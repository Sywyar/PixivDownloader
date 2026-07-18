package top.sywyar.pixivdownload.novel.metadata;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 批量装配小说插件自有的轻量详情；基础行、内嵌图片与译文语言各执行一次查询。
 */
@PluginManagedBean
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NovelWorkDetailsRepository {

    private final NovelMapper novelMapper;

    public Optional<NovelWorkDetails> find(long novelId) {
        return Optional.ofNullable(findAll(List.of(novelId)).get(novelId));
    }

    /**
     * 按入参首次出现顺序返回未软删除小说的详情；未知、非法或软删除 id 直接跳过。
     */
    public Map<Long, NovelWorkDetails> findAll(Collection<Long> novelIds) {
        List<Long> ids = normalizeIds(novelIds);
        if (ids.isEmpty()) {
            return Map.of();
        }

        List<NovelMapper.NovelWorkDetailsRow> rows = novelMapper.findWorkDetailsByIds(ids);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, NovelMapper.NovelWorkDetailsRow> rowsById = new LinkedHashMap<>();
        for (NovelMapper.NovelWorkDetailsRow row : rows) {
            if (row != null) {
                rowsById.put(row.novelId(), row);
            }
        }
        if (rowsById.isEmpty()) {
            return Map.of();
        }

        List<Long> activeIds = ids.stream().filter(rowsById::containsKey).toList();
        Map<Long, List<String>> imagesById = groupValues(novelMapper.findNovelImageIdsByIds(activeIds));
        Map<Long, List<String>> languagesById = groupValues(novelMapper.findTranslationLangsByIds(activeIds));

        Map<Long, NovelWorkDetails> out = new LinkedHashMap<>();
        for (Long id : activeIds) {
            NovelMapper.NovelWorkDetailsRow row = rowsById.get(id);
            out.put(id, new NovelWorkDetails(
                    id,
                    row.wordCount(),
                    row.textLength(),
                    row.readingTimeSeconds(),
                    row.pageCount(),
                    row.xLanguage(),
                    row.coverExt(),
                    imagesById.getOrDefault(id, List.of()),
                    languagesById.getOrDefault(id, List.of())));
        }
        return Collections.unmodifiableMap(out);
    }

    private static List<Long> normalizeIds(Collection<Long> novelIds) {
        if (novelIds == null || novelIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long id : novelIds) {
            if (id != null && id > 0) {
                normalized.add(id);
            }
        }
        return List.copyOf(normalized);
    }

    private static Map<Long, List<String>> groupValues(List<NovelMapper.NovelWorkDetailValueRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> grouped = new LinkedHashMap<>();
        for (NovelMapper.NovelWorkDetailValueRow row : rows) {
            if (row != null && row.value() != null) {
                grouped.computeIfAbsent(row.novelId(), ignored -> new ArrayList<>()).add(row.value());
            }
        }
        grouped.replaceAll((ignored, values) -> List.copyOf(values));
        return grouped;
    }
}
