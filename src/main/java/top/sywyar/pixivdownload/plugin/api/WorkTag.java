package top.sywyar.pixivdownload.plugin.api;

/**
 * 单个作品携带的标签（{@code tags} 池行的投影）。
 *
 * @param tagId          标签池 id（历史数据可能缺失，可为 {@code null}）
 * @param name           Pixiv 原始标签名
 * @param translatedName 可选翻译名，可为 {@code null}
 */
public record WorkTag(Long tagId, String name, String translatedName) {
}
