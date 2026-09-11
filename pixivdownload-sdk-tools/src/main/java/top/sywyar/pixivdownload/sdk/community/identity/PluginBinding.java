package top.sywyar.pixivdownload.sdk.community.identity;

import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Owner;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;

/** 当前管理权唯一记录；历史签名归属不随此记录变更。 */
public record PluginBinding(int schemaVersion, String pluginId, Owner owner, String effectiveRequestId, String updatedAt) {
    public static PluginBinding read(CommunityJson.Document document, String path) {
        if (document.kind() != CommunityJson.Kind.BINDING) throw new ContractException("SCHEMA_INVALID", "");
        var value = document.as(PluginBinding.class);
        CommunityPaths.relative(path, false);
        if (!path.equals("plugin-bindings/" + value.pluginId + ".json")) throw new ContractException("PATH_MISMATCH", "/path");
        return value;
    }

    public void requireOwner(Owner expected) {
        if (!owner.equals(expected)) throw new ContractException("BINDING_MISMATCH", "/owner");
    }
}
