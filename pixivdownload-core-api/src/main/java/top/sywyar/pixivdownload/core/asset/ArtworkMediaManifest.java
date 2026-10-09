package top.sywyar.pixivdownload.core.asset;

import java.util.List;
import java.util.Set;

/**
 * 按作品页号持久化的媒体事实，区分原图与转换副本。
 * @param originalExtension 原始文件扩展名
 * @param extensions 已保存产物的扩展名列表
 * @param originalRetained 原始文件是否保留
 */
public record ArtworkMediaManifest(String originalExtension, List<String> extensions, boolean originalRetained) {
    /** 媒体记录允许使用的扩展名。 */
    private static final Set<String> FORMATS = Set.of("png", "jpg", "jpeg", "webp", "gif", "apng", "mp4", "zip");

    /**
     * 校验原始格式、产物格式与保留状态的一致性，并复制产物格式列表。
     *
     * @param originalExtension 原始文件扩展名
     * @param extensions 复制为不可变列表的产物扩展名
     * @param originalRetained 原始文件是否保留
     * @throws IllegalArgumentException 格式不受支持、列表为空或重复、保留状态与产物不一致
     */
    public ArtworkMediaManifest {
        if (originalExtension == null || !FORMATS.contains(originalExtension) || extensions == null || extensions.isEmpty()
                || extensions.size() > FORMATS.size() || extensions.stream().anyMatch(value -> value == null || !FORMATS.contains(value))
                || Set.copyOf(extensions).size() != extensions.size()) {
            throw new IllegalArgumentException("Invalid media manifest");
        }
        extensions = List.copyOf(extensions);
        if (originalRetained && !extensions.contains(originalExtension)) throw new IllegalArgumentException("Original file is not an output");
    }

    /**
     * 按产物列表中是否包含原始扩展名推导保留状态。
     * @param originalExtension 原始文件扩展名
     * @param extensions 已保存产物的扩展名列表
     */
    public ArtworkMediaManifest(String originalExtension, List<String> extensions) {
        this(originalExtension, extensions, extensions != null && extensions.contains(originalExtension));
    }

}
