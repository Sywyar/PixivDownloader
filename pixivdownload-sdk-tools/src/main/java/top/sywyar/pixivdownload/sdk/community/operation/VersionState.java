package top.sywyar.pixivdownload.sdk.community.operation;

import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

/** 单个已发布包的处置事实；社区独立限制由撤销快照另外保留。 */
public record VersionState(String pluginId, String version, String packageSha256, State state, String decisionSha256) {
    public enum State { ACTIVE, YANKED, REVOKED }

    public VersionState {
        CommunityValues.version(version, "/version");
        validateValue("pluginId", pluginId);
        validateValue("sha256", packageSha256);
        if (state == null || state == State.ACTIVE && decisionSha256 != null
                || state != State.ACTIVE && decisionSha256 == null) {
            throw new ContractException("INVALID_STATE_TRANSITION", "/state");
        }
        if (decisionSha256 != null) validateValue("sha256", decisionSha256);
    }

    /** 在身份、审批及证明已核对后调用；这里只产生新纯值，不更新磁盘或历史记录。 */
    public VersionState transition(VersionStatusRequest request, String newDecisionSha256) {
        var p = request.payload();
        if (!pluginId.equals(p.pluginId()) || !version.equals(p.version()) || !packageSha256.equals(p.packageSha256())) {
            throw new ContractException("BINDING_MISMATCH", "/payload/packageSha256");
        }
        validateValue("sha256", newDecisionSha256);
        State next = switch (p.action()) {
            case YANK -> {
                if (state != State.ACTIVE) throw invalid();
                yield State.YANKED;
            }
            case UNYANK -> {
                if (state != State.YANKED) throw invalid();
                if (!decisionSha256.equals(p.yankedDecisionSha256())) {
                    throw new ContractException("BASELINE_CHANGED", "/payload/yankedDecisionSha256");
                }
                yield State.ACTIVE;
            }
            case REVOKE -> {
                if (state == State.REVOKED) throw invalid();
                yield State.REVOKED;
            }
        };
        return new VersionState(pluginId, version, packageSha256, next, next == State.ACTIVE ? null : newDecisionSha256);
    }

    private static ContractException invalid() { return new ContractException("INVALID_STATE_TRANSITION", "/payload/action"); }
    private static void validateValue(String definition, String value) {
        byte[] bytes = CommunityJson.encode(value);
        CommunityJson.validateStructure(definition, CommunityJson.strictTree(bytes, bytes.length));
    }
}
