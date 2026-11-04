package top.sywyar.pixivdownload.plugin.api.security;

/**
 * CHALLENGE 不执行业务动作；REJECTED 与任何非合法结果均拒绝当前操作。
 * @param status 验证结论
 * @param challenge 由策略自行解释的有限纯文本挑战
 */
public record OperationVerificationResult(Status status, String challenge) {
    /** 单项策略的验证结论。 */
    public enum Status {
        /** 当前项通过，仍须其余必要项通过。 */
        APPROVED,
        /** 拒绝本次操作。 */
        REJECTED,
        /** 等待一次绑定当前 publication 的响应。 */
        CHALLENGE
    }
}
