package top.sywyar.pixivdownload.download;

import top.sywyar.pixivdownload.core.pixiv.filename.PixivWorkFileNameFormatter;

import java.util.regex.Pattern;

/**
 * 共享目录（{@code download.artwork-folder-template} 非空）下的跨作品文件名冲突防护。
 *
 * <h2>为什么必须防</h2>
 * 应用是**从记录重新推导**文件名的：{@code file_name_templates} 里存的是模板，
 * 每次要用到文件时由 {@code template + title + count} 现算（见
 * {@code ArtworkFileLocator.resolveStoredFileBaseName}）。因此落盘时**不能**为了避让冲突
 * 临时改名（例如加个后缀），否则记录与磁盘再也对不上。
 *
 * <p>同时 {@code ensureUnique} 只在**单个作品内部**去重（它的 {@code used} / {@code baseCounts}
 * 是每次调用新建的局部集合），对共享目录里的其它作品一无所知。所以两个作品的标题相同、
 * 或长标题被 {@code MAX_BASENAME_LENGTH} 截断成同一个前缀时，就会渲染出同一个文件名：
 * <ul>
 *   <li>后下载的作品直接覆盖先前的文件；</li>
 *   <li>删除任一方时 {@code ArtworkFileLocator} 按文件名主干匹配，会把对方的文件一起删掉。</li>
 * </ul>
 *
 * <h2>怎么防</h2>
 * 不做数据库查询（{@code ArtworkDownloadLookup} 只有 {@code isDownloaded(id)}，
 * 增加「按目录反查作品」需要改动 SDK 公开面），而是从**构造上保证唯一**：
 * 共享目录下要求文件名模板必须包含 {@code {artwork_id}}。作品 ID 全局唯一，
 * 于是「渲染结果含作品 ID」蕴含「不同作品不可能同名」——这是本地就能判定的充分条件。
 *
 * <p>不要求 {@code {artwork_id}} 出现在开头：模板可能把标题放在前面，
 * 只要该变量存在即可（截断取前缀时仍有极小概率把 ID 截掉，因此额外做一次逐页自检）。
 */
final class SharedDirectoryNameGuard {

    private SharedDirectoryNameGuard() {}

    private static final Pattern ARTWORK_ID_VARIABLE = Pattern.compile("\\{artwork_id}");

    /** 模板是否含 {@code {artwork_id}} —— 共享目录下唯一性的充分条件。 */
    static boolean declaresArtworkId(String template) {
        return template != null && ARTWORK_ID_VARIABLE.matcher(template).find();
    }

    /**
     * 逐页核对「渲染出的名字确实带上了本作品的 ID」，防止截断把 ID 截掉。
     *
     * <p>只在共享目录场景调用。命中即说明该模板在此作品上无法保证唯一，
     * 调用方应拒绝本次下载而不是冒险写入。
     *
     * @return true 表示至少有一页的名字不含作品 ID（危险）
     */
    static boolean losesArtworkIdAfterTruncation(Iterable<String> baseNames, long artworkId) {
        String token = String.valueOf(artworkId);
        for (String baseName : baseNames) {
            if (baseName == null || !baseName.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 共享目录下该模板是否安全（声明了 {@code {artwork_id}} 且渲染后仍保留）。
     *
     * @param template 规范化后的文件名模板
     * @param artworkId 作品 ID
     * @param baseNames 按当前模板与本作品参数渲染出的全部页文件名主干
     */
    static boolean isSafeInSharedDirectory(String template, long artworkId, Iterable<String> baseNames) {
        return declaresArtworkId(template) && !losesArtworkIdAfterTruncation(baseNames, artworkId);
    }

    /** 默认模板是否满足共享目录约束（供测试与文档引用）。 */
    static boolean defaultTemplateIsSharedSafe() {
        return declaresArtworkId(PixivWorkFileNameFormatter.DEFAULT_TEMPLATE);
    }
}
