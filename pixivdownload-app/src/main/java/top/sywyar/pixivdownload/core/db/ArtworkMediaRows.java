package top.sywyar.pixivdownload.core.db;

import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;

import java.util.List;
import java.util.Map;

/** 完整登记和选页登记共用的逐页媒体事实。 */
public final class ArtworkMediaRows {
    private ArtworkMediaRows() {}

    public static ArtworkMediaManifest read(Map<String, Object> row, boolean partial) {
        String original = (String) row.get("original_extension");
        List<String> formats = List.of(((String) row.get("extensions")).split(",", -1));
        Long thumbnail = (Long) row.get("thumbnail_owned");
        boolean owned = thumbnail == null ? legacyThumbnail(original, formats, partial) : flag(thumbnail);
        return new ArtworkMediaManifest(original, formats, flag((Long) row.get("original_retained")), owned);
    }

    private static boolean legacyThumbnail(String original, List<String> formats, boolean partial) {
        // 旧动图必须先发布缩略图才能登记。选页也只走首次生成，原生 WebP 仅保留原图时跳过缩略图。
        if (ArtworkMediaManifest.UNKNOWN_ORIGINAL.equals(original)) return false;
        if ("zip".equals(original)) return true;
        if (partial) return formats.contains("webp") && !formats.equals(List.of(original));
        // 历史补转只追加格式。若唯一产物是转换后的 WebP，它必定由首次下载生成。
        return !"webp".equals(original) && formats.equals(List.of("webp"));
    }

    private static boolean flag(long value) {
        if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid artwork media flag");
        return value == 1;
    }
}
