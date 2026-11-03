package top.sywyar.pixivdownload.plugin.api.security;

import java.util.List;
import top.sywyar.pixivdownload.plugin.api.web.page.PageInteraction;
import top.sywyar.pixivdownload.plugin.api.web.page.PageResourceProfile;

/**
 * 业务 owner 执行副作用之前的同步验证接缝。插件在 child context 提供此 Bean。
 * 宿主按已生效的策略键要求唯一实现；缺席、歧义、异常及撤回都拒绝操作。
 * 策略可以通过独立契约组合验证器，宿主不解释 proof 或 challenge 的业务字段。
 * 本接口不接收业务回调，也不替代业务 owner 的身份、权限及输入校验。
 */
public interface OperationVerification extends top.sywyar.pixivdownload.plugin.api.operation.OperationHook {
    /** 验证策略作为通用前置回调，保持原有 proof/challenge 信封。 */
    @Override default String id() { return policyId(); }
    @Override default Definition definition() {
        Policy policy = policy();
        return new Definition(policy.revision(), java.util.Set.of(Phase.BEFORE_INVOKE),
                "{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}",
                policy.contextSchema(), "", policy.protocol(), policy.resources());
    }
    @Override default Decision invoke(Invocation invocation) {
        if (invocation.phase() != Phase.BEFORE_INVOKE) throw new IllegalArgumentException("OPERATION_HOOK_PHASE_INVALID");
        var result = verify(new OperationVerificationRequest(invocation.operation(), invocation.subject(), invocation.input(),
                invocation.attempt(), invocation.revision(), policy().protocol().id(), invocation.response(), invocation.proof()));
        return new Decision(switch (result.status()) {
            case APPROVED -> Verdict.CONTINUE;
            case REJECTED -> Verdict.REJECT;
            case CHALLENGE -> Verdict.CHALLENGE;
        }, result.challenge());
    }
    /**
     * 业务 owner 在 serviceImports 显式声明此接缝，不依赖任何具体策略或 provider。
     * 只授予 owner-bound OperationGuard；不能以此键导出或直接取得全局验证服务。
     */
    top.sywyar.pixivdownload.plugin.api.service.ServiceKey<OperationVerification> IMPORT =
            new top.sywyar.pixivdownload.plugin.api.service.ServiceKey<>("operation-verification", OperationVerification.class);

    /** 通用展示信封只承载不透明文本，文本内部的业务协议由 provider 拥有。 */
    PageInteraction INTERACTION = new PageInteraction("operation-verification", 1,
            "{\"type\":\"string\",\"maxLength\":16384}", "{\"type\":\"string\",\"maxLength\":16384}");

    /** {@return 稳定策略键，至多 96 字符的小写短横线标识；不是插件 id} */
    String policyId();

    /** @return 无副作用的当前策略计划；规则或资源变化必须改变 revision 并重新发布 */
    Policy policy();

    /** @return 凭据的验证代际；轮换只使旧挑战失效，不能改变已批准规则或权限 */
    String generation();

    /** contextSchema 为空表示不读取业务上下文，provider 此时只收到空 JSON 对象。 */
    record Policy(String revision, String contextSchema, PageInteraction protocol, List<PageResourceProfile> resources) { }

    /**
     * 首次调用 response=false、proof 为空；挑战响应携带同一 attemptId。
     * 请求仅含宿主规范化事实，不含密码、会话或 Cookie。
     * 策略不得依赖自动重试；一次挑战响应最多接纳一次。
     * @param request 可信请求事实与插件拥有的不透明证明
     * @return 纯值决策；challenge 仅在 CHALLENGE 时有意义，UTF-8 至多 16 KiB
     */
    OperationVerificationResult verify(OperationVerificationRequest request);
}
