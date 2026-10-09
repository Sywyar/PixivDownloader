package top.sywyar.pixivdownload.plugin.api.download.task;

import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadSubmission;
import top.sywyar.pixivdownload.plugin.api.download.submission.DownloadTaskException;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 宿主必需的任务提交与状态门面。提交仅接受管理员；HTTP 调用方必须使用宿主身份解析器。
 * 状态为进程内事实，终态保留至少五分钟，不承诺跨重启恢复或事件重放。
 * 类型能力缺席、撤回或换代时拒绝提交；旧命令不会重投替代实例。
 */
public interface DownloadTasks {
    /**
     * 提交或查询相同幂等键的原任务；宿主只保存命令摘要，不保存原始选项与凭据。
     * 类型执行器可以在本次下载期间持有必要凭据，任务结束或排队取消时必须释放。
     * @param submission 下载命令
     * @param credential 显式短期凭据，空值表示匿名
     * @param owner 可信请求身份
     * @return 当前任务及是否重复提交
     * @throws DownloadTaskException 拒绝、冲突、容量不足或能力不可用
     */
    Receipt submit(DownloadSubmission submission, String credential, RequestOwnerIdentity owner);
    /**
     * 按任务身份查询调用者可见的执行状态。
     *
     * @param taskId 执行身份
     * @param owner 可信请求身份
     * @return 身份可见的任务；未知或过期为空
     */
    Optional<DownloadTaskSnapshot> find(UUID taskId, RequestOwnerIdentity owner);
    /**
     * 捕获调用者可见的任务集合，供断线后恢复状态。
     *
     * @param owner 可信请求身份
     * @return 同一时点的任务快照，用于断线后重新同步
     */
    Snapshot snapshot(RequestOwnerIdentity owner);
    /**
     * 只取消该任务捕获的队列句柄；不按作品键改投其它任务或新 publication。
     * @param taskId 执行身份
     * @param owner 可信请求身份
     * @return 取消请求结果，运行任务真正终结后才更新终态
     */
    CancelResult cancel(UUID taskId, RequestOwnerIdentity owner);

    /** 取消命令结果。 */
    enum CancelResult {
        /** 已向原任务发送取消请求。 */ REQUESTED,
        /** 任务已经终结。 */ TERMINAL,
        /** 未知、过期或当前身份不可见。 */ NOT_FOUND,
        /** 仍在同步准备，尚无可取消队列句柄。 */ NOT_CANCELLABLE
    }
    /**
     * 接纳回执。
     * @param task 当前任务
     * @param duplicate 是否命中同一命令
     */
    record Receipt(DownloadTaskSnapshot task, boolean duplicate) {}
    /**
     * 同一时点的可见任务。
     * @param epoch 进程标识
     * @param revision 状态版本
     * @param tasks 当前可见任务
     */
    record Snapshot(UUID epoch, long revision, List<DownloadTaskSnapshot> tasks) {
        /**
         * 防御性复制当前任务。
         * @param epoch 进程标识
         * @param revision 状态版本
         * @param tasks 当前可见任务
         */
        public Snapshot { tasks = List.copyOf(tasks); }
    }
}
