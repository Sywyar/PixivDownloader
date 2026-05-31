package top.sywyar.pixivdownload.novel.db;

/**
 * 名词映射表中的一条映射：{@code source}（原文专有名词）在目标语言 {@code langCode}（BCP-47）下译为
 * {@code target}。同一张表内一个原文可对多种目标语言各有一条（一表多语言）。
 */
public record NovelGlossaryEntry(
        String source,
        String langCode,
        String target
) {
}
