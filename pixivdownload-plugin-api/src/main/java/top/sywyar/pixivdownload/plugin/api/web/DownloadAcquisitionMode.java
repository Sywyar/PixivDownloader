package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 下载工作台取得作品的模式。枚举名用于 Java 侧稳定编译，{@link #code()} 是前端 descriptor 与
 * {@code /api/download/extensions} 暴露的稳定字符串。
 */
public enum DownloadAcquisitionMode {

    SINGLE_IMPORT("single-import"),
    USER_PROFILE("user"),
    SERIES_COLLECTION("series"),
    SEARCH("search"),
    QUICK("quick");

    private final String code;

    DownloadAcquisitionMode(String code) {
        this.code = code;
    }

    /** 前端契约中的取得模式 id。 */
    public String code() {
        return code;
    }
}
