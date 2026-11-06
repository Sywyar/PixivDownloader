package top.sywyar.pixivdownload.plugin.api.security;

/**
 * 业务证明与挑战由策略拥有，attemptId 由宿主生成并绑定请求与精确 publication。
 * @param operationId 业务操作身份
 * @param subject 宿主规范化的主体摘要
 * @param context 业务 owner 筛选的上下文 JSON
 * @param attemptId 宿主生成并绑定请求的尝试身份
 * @param revision 正式策略修订
 * @param protocol 交互协议身份
 * @param response 是否为挑战响应
 * @param proof 由策略解释的不透明证明
 */
public record OperationVerificationRequest(String operationId, String subject, String context,
        String attemptId, String revision, String protocol, boolean response, String proof) { }
