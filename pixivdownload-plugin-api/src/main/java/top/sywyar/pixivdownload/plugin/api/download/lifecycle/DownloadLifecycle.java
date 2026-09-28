package top.sywyar.pixivdownload.plugin.api.download.lifecycle;

/** 宿主必需门面；插件实现由宿主管理，消费者不得缓存插件 Bean。 */
public interface DownloadLifecycle {
    /**
     * 同步执行当前规则；无规则时允许。
     * @param attempt 待接纳的执行身份
     * @throws DownloadAdmissionRejectedException 规则拒绝或不可用，调用方必须停止副作用
     */
    void checkAdmission(DownloadAttempt attempt);
    /**
     * 尽力通知，不改变已经成立的业务事实；普通观察异常隔离，VM 错误继续抛出。
     * @param event 已发生的执行事实
     */
    void publish(DownloadEvent event);
}
