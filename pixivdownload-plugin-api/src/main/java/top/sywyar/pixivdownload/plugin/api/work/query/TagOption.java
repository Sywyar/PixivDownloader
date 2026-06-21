package top.sywyar.pixivdownload.plugin.api.work.query;

/**
 * 标签目录行：标签与「使用该标签的可见作品数」。
 *
 * @param tagId          标签池 id
 * @param name           Pixiv 原始标签名
 * @param translatedName 可选翻译名，可为 {@code null}
 * @param workCount      使用该标签的作品数（按查询条件统计）
 */
public record TagOption(long tagId, String name, String translatedName, long workCount) {
}
