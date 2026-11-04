package top.sywyar.pixivdownload.plugin.api.security;

/** 业务证明与挑战由策略拥有，attemptId 由宿主生成并绑定请求与精确 publication。 */
public record OperationVerificationRequest(String operationId, String subject, String context,
        String attemptId, String revision, String protocol, boolean response, String proof) { }
