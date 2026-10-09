// 由 scripts/schedule/generate-pixiv-defaults.mjs 生成。
package top.sywyar.pixivdownload.download.schedule.snapshot;

/** Pixiv 计划定义的缺省值；修改生成器旁的 JSON 后重新生成。 */
public final class PixivScheduleDefaults {
    public static final String KIND = "illust";
    public static final int FETCH_LIMIT = 0;
    public static final String SOURCE_ORDER = "date_d";
    public static final String SOURCE_MODE = "all";
    public static final String SOURCE_S_MODE = "s_tag";
    public static final int SOURCE_MAX_PAGES = 3;
    public static final String SOURCE_REST = "show";
    public static final String FILTERS_CONTENT = "all";
    public static final String FILTERS_AI_FILTER = "all";
    public static final String FILTERS_TYPE_FILTER = "all";
    public static final String DOWNLOAD_FILE_NAME_TEMPLATE = "";
    public static final String DOWNLOAD_PATH_OVERFLOW_ACTION = "ASK";
    public static final boolean DOWNLOAD_BOOKMARK = false;
    public static final int DOWNLOAD_CONCURRENT = 1;
    public static final int DOWNLOAD_INTERVAL_MS = 0;
    public static final int DOWNLOAD_IMAGE_DELAY_MS = 0;
    public static final boolean DOWNLOAD_VERIFY_FILES = false;
    public static final boolean DOWNLOAD_REDOWNLOAD_DELETED = false;
    public static final String DOWNLOAD_NOVEL_FORMAT = "txt";
    public static final boolean DOWNLOAD_NOVEL_MERGE = false;
    public static final String DOWNLOAD_NOVEL_MERGE_FORMAT = "epub";
    public static final boolean DOWNLOAD_NOVEL_AUTO_TRANSLATE = false;
    public static final String DOWNLOAD_NOVEL_TRANSLATE_LANGUAGE = "";
    public static final int DOWNLOAD_NOVEL_TRANSLATE_SEGMENT_SIZE = 0;
    public static final String DOWNLOAD_IMAGE_FORMATS = "original";
    public static final String DOWNLOAD_UGOIRA_FORMATS = "webp";
    public static final int DOWNLOAD_MEDIA_QUALITY = 90;
    public static final boolean DOWNLOAD_MEDIA_WEBP_LOSSLESS = false;
    public static final int DOWNLOAD_MEDIA_MAXIMUM_EDGE = 0;
    public static final String CREDENTIAL_CONTENT = "safe";

    private PixivScheduleDefaults() {}
}
