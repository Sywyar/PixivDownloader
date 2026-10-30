package top.sywyar.pixivdownload.plugin.api.security;

/**
 * CHALLENGE 不产生会话；REJECTED 与任何非合法结果均拒绝当前登录。
 * @param status 验证结论
 * @param challenge 由策略自行解释的有限纯文本挑战
 */
public record LoginVerificationResult(Status status, String challenge) {
    /** 单项策略的验证结论。 */
    public enum Status {
        /** 当前项通过，仍须其余必要项通过。 */
        APPROVED,
        /** 拒绝本次登录。 */
        REJECTED,
        /** 等待一次绑定当前 publication 的响应。 */
        CHALLENGE
    }
}
