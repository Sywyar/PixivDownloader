package top.sywyar.pixivdownload.plugin.api.download.lifecycle;

/** 前置规则拒绝或不可用；调用方以稳定机器码 DOWNLOAD_ADMISSION_REJECTED 投影。 */
public final class DownloadAdmissionRejectedException extends RuntimeException {
    /** 使用固定机器码，不保留插件异常或其 ClassLoader。 */
    public DownloadAdmissionRejectedException() {
        super("DOWNLOAD_ADMISSION_REJECTED");
    }
}
