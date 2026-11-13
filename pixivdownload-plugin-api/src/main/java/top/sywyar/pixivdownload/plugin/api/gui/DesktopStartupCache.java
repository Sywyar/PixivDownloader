package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.UUID;

/** 宿主持有的启动缓存训练；关闭观察页面不停止任务。 */
public interface DesktopStartupCache {
    /** {@return 最近一次任务的机器事实；读取不执行磁盘扫描或启动训练} */
    Snapshot snapshot();

    /**
     * 按当前安装的插件启动一次训练；已有任务时返回同一次执行。
     * @return 本次执行身份
     */
    UUID start();

    /**
     * 请求取消精确执行；资源释放后才发布取消终态。
     * @param executionId 要取消的任务
     * @return 是否接受取消请求
     */
    boolean cancel(UUID executionId);

    /** 训练状态。 */
    enum State {
        /** 尚未完成检查或训练。 */ IDLE,
        /** 已核对现存缓存。 */ READY,
        /** 正在执行。 */ RUNNING,
        /** 正在取消及释放资源。 */ STOPPING,
        /** 本次训练已发布。 */ SUCCEEDED,
        /** 取消完成。 */ CANCELLED,
        /** 本次训练失败。 */ FAILED
    }

    /** 来自真实执行步骤的进度；不表示耗时比例。 */
    enum Phase {
        /** 冻结输入和初始化隔离目录。 */ PREPARING,
        /** 录制实际桌面启动。 */ RECORDING,
        /** 生成 JVM 缓存。 */ ASSEMBLING,
        /** 校验缓存和输入身份。 */ VERIFYING,
        /** 执行结束。 */ FINISHED
    }

    /** 可安全展示的失败原因。 */
    enum Failure {
        /** 无失败。 */ NONE,
        /** 其它进程持有训练锁。 */ BUSY,
        /** 训练期间输入发生变化。 */ INPUT_CHANGED,
        /** 目录访问被拒绝。 */ ACCESS_DENIED,
        /** 训练子进程未正常完成。 */ TRAINING_FAILED,
        /** 输入或缓存读写失败。 */ IO_ERROR
    }

    /**
     * 当前或最近一次训练的不可变快照。
     * @param executionId 执行身份；尚未执行时为空
     * @param state 当前状态
     * @param phase 当前阶段
     * @param failure 受控失败原因
     * @param pluginCount 本次快照中的插件包数量
     */
    record Snapshot(UUID executionId, State state, Phase phase, Failure failure, int pluginCount) {
        /** {@return 是否仍在执行或释放资源} */
        public boolean active() { return state == State.RUNNING || state == State.STOPPING; }
    }
}
