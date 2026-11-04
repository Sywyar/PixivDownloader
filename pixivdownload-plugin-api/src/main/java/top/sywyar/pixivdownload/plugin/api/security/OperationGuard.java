package top.sywyar.pixivdownload.plugin.api.security;

import java.io.IOException;
import java.util.Map;

/**
 * 宿主绑定业务 owner 的同步操作入口。仅在全部必要验证通过时执行本地动作一次。
 * 必要能力缺席或换代时拒绝；租约持续到动作实际返回，不将回调传给 provider。
 * 此入口不重试命令，也不代替 owner 的身份、权限、CSRF 与限流检查。
 */
public interface OperationGuard extends top.sywyar.pixivdownload.plugin.api.operation.OperationTargets {
    /** 仅在调用方本地执行的同步动作，不是跨插件服务参数。 */
    @FunctionalInterface
    interface Action<T> {
        /**
         * @return 业务结果；IO 失败不触发自动重试
         * @throws IOException 业务动作失败
         */
        T run() throws IOException;
    }

    /** 一次操作的结果；业务值只在 APPROVED 时存在，不跨 provider 传递。 */
    record Result<T>(Status status, T value, String token, String revision, Map<String, String> challenges) {
        /**
         * 复制公开挑战快照，调用方不能改变已有结果。
         * @param status 操作结论
         * @param value 本地业务结果，仅通过时存在
         * @param token 一次挑战身份，未产生挑战时为空
         * @param revision 正式发布身份
         * @param challenges 按策略键索引的公开挑战
         */
        public Result { challenges = Map.copyOf(challenges); }
    }

    /** 稳定结果由 HTTP 或其它调用方按自身传输协议投影。 */
    enum Status {
        /** 准备时无需交互证明，或执行时动作已完成。 */ APPROVED,
        /** 等待一次响应，动作未执行。 */ CHALLENGE,
        /** 验证拒绝。 */ REJECTED,
        /** 必要依赖或正式状态不可用。 */ UNAVAILABLE,
        /** 挑战已失效或已消费。 */ STALE,
        /** 输入不符合合同。 */ INVALID
    }

    /**
     * 操作身份必须属于当前 owner；必要策略只取服务端正式状态。
     * @param operationId owner 命名空间中的稳定操作身份
     * @param subject 业务 owner 校验格式后的主体绑定材料，不含密码或会话，不代表已认证
     * @param context owner 筛选后的必要上下文 JSON
     * @param token 首次请求为空，响应使用当前挑战 token
     * @param proofs provider 拥有的有限证明，不作为授权事实
     * @param action 本地业务动作
     * @param <T> 本地业务返回类型
     * @return 验证状态及仅在成功时存在的业务结果
     * @throws IOException 业务动作的 IO 错误；不重放动作
     */
    <T> Result<T> execute(String operationId, String subject, String context,
            String token, Map<String, String> proofs, Action<T> action) throws IOException;

    /**
     * 在业务提交前准备必要挑战，不接收或执行业务动作。缺席和换代仍按当前正式策略拒绝。
     * APPROVED 只表示本次准备无需交互证明，正式执行仍须重新校验服务端策略。
     * @param operationId 当前 owner 声明的操作身份
     * @param subject 格式已校验的主体绑定材料，不代表已认证，不含密码或会话
     * @param context owner 筛选后的必要上下文 JSON
     * @return 准备结论、正式修订及按策略索引的挑战，业务值始终为空
     * @throws IOException 准备流程失败
     */
    Result<Void> prepare(String operationId, String subject, String context) throws IOException;

}
