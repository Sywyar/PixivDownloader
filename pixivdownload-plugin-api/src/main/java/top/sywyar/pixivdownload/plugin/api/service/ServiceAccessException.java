package top.sywyar.pixivdownload.plugin.api.service;

import java.util.Objects;

/** 宿主生成的稳定失败；不携带插件异常、cause、suppressed 或插件栈帧。 */
public final class ServiceAccessException extends RuntimeException {
    /** 调用方可据此区分缺席、声明错误、撤回与执行失败。 */
    public enum Reason {
        /** 消费方尚未发布或已撤回，或从服务栈同步修改生命周期。 */
        NOT_ACTIVE,
        /** 未显式导入此服务。 */
        UNDECLARED,
        /** 提供者缺席、已撤回或旧 publication 已失效。 */
        UNAVAILABLE,
        /** 有多个活动提供者。 */
        AMBIGUOUS,
        /** 参数或结果不符合纯值合同。 */
        INVALID_VALUE,
        /** 同步在途调用已达到累计容量。 */
        CAPACITY,
        /** 提供者执行失败；原始异常对象不跨边界。 */
        INVOCATION_FAILED,
        /** 当前访问器实现没有提供请求的能力。 */
        UNSUPPORTED
    }

    /** 唯一跨边界保存的失败事实。 */
    private final Reason reason;

    /**
     * 创建不携带异常对象图的失败。
     * @param reason 稳定原因
     */
    public ServiceAccessException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").name(), null, false, false);
        this.reason = reason;
    }

    /** @return 可据以选择降级或错误显示的稳定原因 */
    public Reason reason() {
        return reason;
    }
}
