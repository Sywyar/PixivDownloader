package top.sywyar.pixivdownload.plugin.api.download.submission;

import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAttempt;

/**
 * 可选的类型自有提交能力，由宿主按精确 publication 发布和撤回。
 * submit 返回前须将任务交给受生命周期管理的队列，并经 DownloadLifecycle.track 登记同一 attempt。
 * 解析、选项白名单和输入校验归类型 owner；不能调用其它插件的私有接口。
 */
public interface DownloadSubmissionHandler {
    /**
     * {@return 本实现接收的稳定作品类型}
     */
    String workType();
    /**
     * 同步准备并接纳下载；抛错表示未成功接纳。异步任务必须参与所属插件的 quiesce/drain。
     * @param submission 不可变命令
     * @param attempt 宿主分配的执行身份，不能另建身份
     * @param credential 调用者显式提供的短期凭据；不得进入快照、事件、日志或持久化命令
     */
    void submit(DownloadSubmission submission, DownloadAttempt attempt, String credential);
}
