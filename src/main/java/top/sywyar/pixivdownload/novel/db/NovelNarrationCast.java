package top.sywyar.pixivdownload.novel.db;

/**
 * 一份「朗读花名册」的元信息（不含角色条目）。花名册用于多角色有声朗读时为说话人分配稳定音色：
 * 默认绑定到某个小说系列（{@code seriesId}）或某本单独小说（{@code novelId}），二者择一——
 * <b>一个系列共享一份花名册、无系列的单本各自一份</b>，与名词映射表（{@link NovelGlossary}）的绑定方式一致。
 * 角色条目存于 {@code novel_narration_voices}（旁白恒为 {@code character_id = 0}）。
 *
 * @param seriesId   归属的小说系列 ID（绑定来源之一），可为 {@code null}
 * @param novelId    归属的单独小说 ID（绑定来源之一），可为 {@code null}
 * @param voiceCount 该花名册当前的角色数（含旁白；列表查询时填充，单查时可能为 0）
 */
public record NovelNarrationCast(
        long id,
        String name,
        Long seriesId,
        Long novelId,
        long createdTime,
        long updatedTime,
        int voiceCount
) {
}
