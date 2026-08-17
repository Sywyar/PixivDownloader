package top.sywyar.pixivdownload.core.work.service;

/**
 * 下载路径候选未通过宿主安全策略。
 *
 * <p>异常不携带候选路径，避免路径或用户名经默认异常文本进入日志；调用方应使用自己仍持有的输入完成本地化投影。</p>
 */
public final class DownloadPathRejectedException extends RuntimeException {

    /**
     * 创建 {@code DownloadPathRejectedException} 实例。
     */
    public DownloadPathRejectedException() {
        super("download path rejected");
    }
}
