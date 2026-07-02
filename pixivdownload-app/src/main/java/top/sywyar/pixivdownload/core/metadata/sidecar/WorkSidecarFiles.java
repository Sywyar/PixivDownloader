package top.sywyar.pixivdownload.core.metadata.sidecar;

import java.nio.file.Path;

/**
 * 作品 meta sidecar 文件命名规则（纯 JDK，无框架依赖）。
 * <p>
 * sidecar 文件名为 {@code {workId}.meta.json}（per-work 命名，避免 ImageClassifier 摊平单图作品时跨作品撞名）。
 * 本类只负责文件名规则，JSON 解析、存储、校验等逻辑由 {@link WorkSidecarStore} 承担。
 */
public final class WorkSidecarFiles {

    /** sidecar 文件名后缀：{@code {workId}.meta.json}。 */
    public static final String SIDECAR_SUFFIX = ".meta.json";

    private WorkSidecarFiles() {
        // 工具类，禁止实例化
    }

    /**
     * sidecar 文件名（不含目录）。
     *
     * @param workId 作品 ID
     * @return {@code {workId}.meta.json}
     */
    public static String fileName(long workId) {
        return workId + SIDECAR_SUFFIX;
    }

    /**
     * 是否为 sidecar 文件名（供配额打包 / 小说导出枚举层排除 {@code *.meta.json}）。
     *
     * @param fileName 文件名（不含路径）
     * @return 文件名以 {@code .meta.json} 结尾返回 {@code true}，否则 {@code false}
     */
    public static boolean isSidecarFileName(String fileName) {
        return fileName != null && fileName.endsWith(SIDECAR_SUFFIX);
    }

    /**
     * 是否为 sidecar 路径。
     *
     * @param path 文件路径
     * @return 路径的文件名以 {@code .meta.json} 结尾返回 {@code true}，否则 {@code false}
     */
    public static boolean isSidecarFile(Path path) {
        if (path == null) {
            return false;
        }
        Path fileName = path.getFileName();
        return fileName != null && isSidecarFileName(fileName.toString());
    }
}
