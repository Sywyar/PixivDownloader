package top.sywyar.pixivdownload.plugin.verification;

import io.swagger.v3.oas.annotations.media.Schema;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

import java.util.List;

/** 同一份已验证撤销快照的只读展示，不另存安全状态。 */
public record PluginRevocationView(
        String repositoryId, @Schema(allowableValues = {"NOT_CHECKED", "NOT_PROVIDED", "REVOKED", "YANKED", "CLEAR", "STALE"}) String status, @HttpApiNullable String fetchedAt, @HttpApiNullable String generatedTime,
        @HttpApiNullable String nextUpdate,
        @HttpApiNullable String graceUntil, String freshness, boolean installBlocked, boolean executionBlocked,
        boolean refreshAvailable, List<Restriction> restrictions) {
    public PluginRevocationView {
        restrictions = List.copyOf(restrictions);
    }

    public record Restriction(String scope, String action, String reasonCode, String effectiveTime) { }
}
