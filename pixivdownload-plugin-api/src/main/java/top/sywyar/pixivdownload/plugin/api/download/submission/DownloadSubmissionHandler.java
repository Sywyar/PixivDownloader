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
     * {@return 插件内的绝对 classpath 资源路径，内容为该作品类型提交命令的 JSON Schema；
     * 未提供结构的实现返回 null。宿主在注册时读取，查询文档时不调用插件。}
     */
    default String inputSchemaResource() { return null; }
    /**
     * 同步准备并接纳下载；抛错表示未成功接纳。异步任务必须参与所属插件的 quiesce/drain。
     * 提供方可抛出 {@link top.sywyar.pixivdownload.plugin.api.contract.InputFieldException}，
     * 以请求字段的 JSON Pointer 和稳定原因标识输入错误；不得含提交值或凭据。
     * @param submission 不可变命令
     * @param attempt 宿主分配的执行身份，不能另建身份
     * @param credential 调用者显式提供的短期凭据；不得进入快照、事件、日志或持久化命令
     */
    void submit(DownloadSubmission submission, DownloadAttempt attempt, String credential);

    /**
     * 在插件调用代理内执行提交，将稳定失败码及可选字段诊断传回宿主。
     * @param submission 下载命令
     * @param attempt 宿主分配的执行身份
     * @param credential 本次取得凭据
     * @return 已知失败信息；成功时为 null
     */
    default DownloadTaskException.Failure submitAndReportFailure(DownloadSubmission submission, DownloadAttempt attempt,
                                                               String credential) {
        try {
            submit(submission, attempt, credential);
            return null;
        } catch (DownloadTaskException failure) {
            return failure.failure();
        } catch (top.sywyar.pixivdownload.plugin.api.contract.InputFieldException invalid) {
            return new DownloadTaskException.Failure(DownloadTaskException.Code.REJECTED, invalid.field(), invalid.reason());
        }
    }
}
