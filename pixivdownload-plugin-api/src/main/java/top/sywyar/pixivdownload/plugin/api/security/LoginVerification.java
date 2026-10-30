package top.sywyar.pixivdownload.plugin.api.security;

/**
 * 管理员密码校验成功后、创建会话之前的同步验证接缝。插件在 child context 提供此 Bean。
 * 宿主按配置的策略键要求唯一实现；缺席、歧义、异常及撤回都拒绝登录。
 * 策略可以通过独立契约组合验证器，宿主不解释 proof 或 challenge 的业务字段。
 * 本接口不提供创建会话或绕过密码校验的权限。
 */
public interface LoginVerification {
    /** {@return 稳定策略键，至多 96 字符的小写短横线标识；不是插件 id} */
    String policyId();

    /**
     * 首次调用 response=false、proof 为空；挑战响应携带同一 attemptId。
     * 请求仅含宿主规范化事实，不含密码、会话或 Cookie。
     * 策略不得依赖自动重试；一次挑战响应最多接纳一次。
     * @param request 可信请求事实与插件拥有的不透明证明
     * @return 纯值决策；challenge 仅在 CHALLENGE 时有意义，UTF-8 至多 16 KiB
     */
    LoginVerificationResult verify(LoginVerificationRequest request);
}
