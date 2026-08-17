package top.sywyar.pixivdownload.core.download;

/**
 * 共享交互式作品下载的核心执行通道。
 *
 * <p>调用方提交完整的任务包装器；通道保持对象身份，并让接纳失败在调用栈内同步传播。
 */
@FunctionalInterface
public interface InteractiveDownloadExecutionLane {

    /**
     * 执行对应操作。
     *
     * @param task 任务
     */
    void execute(Runnable task);
}
