package top.sywyar.pixivdownload.novel.narration.analysis;

/**
 * 断句产物：一句待朗读文本 + 它所属的<b>渲染块下标</b>（{@code paragraphIndex}）。
 *
 * <p>{@code paragraphIndex} 是该句所在块在小说正文「可朗读渲染块序列」中的下标，序列与前端
 * {@code #content-card} 内 {@code querySelectorAll('h2.novel-chapter, p')} 的文档顺序<b>逐一对齐</b>
 * （由 {@link top.sywyar.pixivdownload.novel.download.NovelMarkupParser#textBlocks} 保证）。多句可共享同一
 * {@code paragraphIndex}（同一段落里有多句），前端据此做段落级高亮 + 滚动跟随。
 *
 * @param text           待朗读的句子文本（已去除 ruby 注音 / 图片占位 / 翻页标记等不可朗读元素）
 * @param paragraphIndex 该句所属渲染块在全局块序列中的下标（与前端 DOM 块顺序对齐）
 */
public record NarrationSentence(String text, int paragraphIndex) {
}
