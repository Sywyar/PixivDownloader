package top.sywyar.pixivdownload.novel.db;

/**
 * 一张「名词映射表」的元信息（不含条目）。映射表用于在 AI 翻译时统一专有名词译法：
 * 属于某个小说系列（{@code seriesId}）或某本单独小说（{@code novelId}）的默认映射表，
 * 也可被任意作品复用。条目存于 {@code novel_glossary_entries}（见 {@link NovelGlossaryEntry}）。
 *
 * @param seriesId   归属的小说系列 ID（默认映射表绑定来源之一），可为 {@code null}
 * @param novelId    归属的单独小说 ID（默认映射表绑定来源之一），可为 {@code null}
 * @param entryCount 该映射表当前的名词条目数（列表查询时填充；单查时可能为 0）
 */
public record NovelGlossary(
        long id,
        String name,
        Long seriesId,
        Long novelId,
        long createdTime,
        long updatedTime,
        int entryCount
) {
}
