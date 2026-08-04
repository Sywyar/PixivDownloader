package top.sywyar.pixivdownload.download.state;

/**
 * revision 已达到 {@link LayoutFeedbackStateStore#MAX_SAFE_REVISION}（JavaScript 安全
 * 整数上限，Number.MAX_SAFE_INTEGER）后仍执行需要修改状态的命令。幂等 no-op 命令不受
 * 影响；本异常表示无法安全递增 revision（绝不回绕为负数或 0），控制器映射为
 * 503 Service Unavailable（no-store, private），内存快照与状态文件均保持不变。
 */
public final class LayoutFeedbackRevisionExhaustedException extends RuntimeException {

    public LayoutFeedbackRevisionExhaustedException(long revision) {
        super("layout feedback revision exhausted at " + revision);
    }
}
