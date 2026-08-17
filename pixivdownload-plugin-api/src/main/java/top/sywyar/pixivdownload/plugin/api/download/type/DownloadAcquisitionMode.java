package top.sywyar.pixivdownload.plugin.api.download.type;

/**
 * 下载工作台取得作品的模式。枚举名用于 Java 侧稳定编译，{@link #code()} 是前端 descriptor 与
 * {@code /api/download/extensions} 暴露的稳定字符串。
 */
public enum DownloadAcquisitionMode {

    /**
     * 表示 {@code SINGLE_IMPORT} 状态。
     */
    SINGLE_IMPORT("single-import"),
    /**
     * 表示 {@code USER_PROFILE} 状态。
     */
    USER_PROFILE("user"),
    /**
     * 表示 {@code SERIES_COLLECTION} 状态。
     */
    SERIES_COLLECTION("series"),
    /**
     * 表示 {@code SEARCH} 状态。
     */
    SEARCH("search"),
    /**
     * 表示 {@code QUICK} 状态。
     */
    QUICK("quick");

    private final String code;

    DownloadAcquisitionMode(String code) {
        this.code = code;
    }

    /**
     * 前端契约中的取得模式 id。
     *
     * @return 方法返回的字符串
     */
    public String code() {
        return code;
    }
}
