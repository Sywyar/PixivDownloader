package top.sywyar.pixivdownload.novel.config;

/**
 * 小说下载执行池的插件自有设置。
 * <p>
 * 属性键保留历史名称，便于宿主通用配置迁移器把旧 {@code config.yaml} 值迁入
 * {@code config/plugins/novel.properties}，但设置的 owner 与生命周期均属于 novel 插件。
 */
public final class NovelExecutionSettings {

    public static final String PREFIX = "download";
    public static final String DOWNLOAD_CONCURRENCY_KEY = "download.novel-max-concurrent";
    public static final int DEFAULT_CONCURRENCY = 10;

    private int novelMaxConcurrent = DEFAULT_CONCURRENCY;

    public int getNovelMaxConcurrent() {
        return Math.max(1, novelMaxConcurrent);
    }

    public void setNovelMaxConcurrent(int novelMaxConcurrent) {
        this.novelMaxConcurrent = novelMaxConcurrent;
    }

}
