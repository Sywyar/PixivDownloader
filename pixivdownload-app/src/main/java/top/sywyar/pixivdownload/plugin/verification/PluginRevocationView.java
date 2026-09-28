package top.sywyar.pixivdownload.plugin.verification;

import java.util.List;

/** 同一份已验证撤销快照的只读展示，不另存安全状态。 */
public record PluginRevocationView(
        String repositoryId, String status, String fetchedAt, String generatedTime, String nextUpdate,
        String graceUntil, String freshness, boolean installBlocked, boolean executionBlocked,
        boolean refreshAvailable, List<Restriction> restrictions) {
    public PluginRevocationView {
        restrictions = List.copyOf(restrictions);
    }

    public record Restriction(String scope, String action, String reasonCode, String effectiveTime) { }
}
