package top.sywyar.pixivdownload.core.download.queue;

/**
 * 宿主 HTTP 队列控制面使用的窄同步命令接口。
 *
 * <p>它刻意不暴露 {@code prepareQuiesce} / {@code cancelQuiescedTasks}：生命周期清退必须使用注册中心保存的
 * raw {@code QueueOperations}，普通取消 / 清空则只通过代际受控的 invocation proxy 调用。
 */
public interface QueueOperationCommands {

    void cancel(String workKey, String ownerUuid, boolean admin);

    int clearAll();

    int clearForOwner(String ownerUuid);
}
