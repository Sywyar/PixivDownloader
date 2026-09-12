package top.sywyar.pixivdownload.sdk.community.review;

import java.util.Set;

/** 来自受保护配置与当前平台权限的审核策略，不从投稿树加载。 */
public record ReviewPolicy(Set<String> reviewerAccountIds, Set<String> dismissalAccountIds,
                           String decisionWorkflowPath, Set<String> decisionWorkflowShas) {
    public ReviewPolicy {
        reviewerAccountIds = Set.copyOf(reviewerAccountIds);
        dismissalAccountIds = Set.copyOf(dismissalAccountIds);
        decisionWorkflowShas = Set.copyOf(decisionWorkflowShas);
    }
}
