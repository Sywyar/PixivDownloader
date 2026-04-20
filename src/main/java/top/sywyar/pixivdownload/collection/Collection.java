package top.sywyar.pixivdownload.collection;

/**
 * 本地收藏夹。{@code iconExt} 为空表示使用默认心形图标；非空时表示图标文件的扩展名
 * （png/jpg/jpeg/webp），实际文件存在 {@code {rootFolder}/_collection_icons/{id}.{ext}}。
 */
public record Collection(
        long id,
        String name,
        String iconExt,
        int sortOrder,
        long createdTime,
        int artworkCount
) {}
