package top.sywyar.pixivdownload.plugin.api.download.lifecycle;

/**
 * 可选的下载前置规则 Bean，在网络与文件副作用之前同步执行。
 * 无规则时允许；任一规则拒绝、抛错或调用时 publication 已失效则拒绝该次执行。
 * 规则不修改请求，不持有执行器，也不替换传输实现。
 */
public interface DownloadAdmissionPolicy {
    /** 附加规则的结论；多个规则必须全部允许。 */
    enum Decision {
        /** 允许继续宿主的必要校验。 */
        ALLOW,
        /** 在产生副作用前拒绝本次尝试。 */
        REJECT
    }
    /**
     * 同步判断一次尝试，不修改请求。同一 attempt 可在准备与实际执行前重复检查，规则应无副作用。
     * @param attempt 不含凭据和路径的执行身份
     * @return 允许或拒绝；null 与普通异常按拒绝处理
     */
    Decision evaluate(DownloadAttempt attempt);
}
