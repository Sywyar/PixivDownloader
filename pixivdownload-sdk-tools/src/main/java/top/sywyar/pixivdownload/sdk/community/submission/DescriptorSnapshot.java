package top.sywyar.pixivdownload.sdk.community.submission;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginRiskDeclaration;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.util.Comparator;
import java.util.List;

/** 仅从已冻结包的描述符派生审核事实，不按安装路径再次打开包。 */
public record DescriptorSnapshot(String requiredSdk, String executionMode, List<PluginDependencyRef> dependencies,
                                 PluginRiskDeclaration riskDeclaration) {
    public DescriptorSnapshot { dependencies = List.copyOf(dependencies); }

    public static DescriptorSnapshot from(PluginDescriptor descriptor, VersionSubmission submission) {
        if (!descriptor.id().equals(submission.pluginId()) || !descriptor.version().equals(submission.version())
                || !descriptor.externalValidationErrors().isEmpty()) {
            throw new ContractException("DESCRIPTOR_MISMATCH", "/package");
        }
        String required = descriptor.requires().present() ? descriptor.requires().raw() : "*";
        var snapshot = new DescriptorSnapshot(required, descriptor.executionMode().descriptorValue(),
                descriptor.dependencies().stream().sorted(Comparator.comparing(PluginDependencyRef::pluginId)).toList(),
                descriptor.riskDeclaration());
        snapshot.validate();
        return snapshot;
    }

    public void validate() {
        byte[] bytes = CommunityJson.encode(this);
        CommunityJson.validateStructure("descriptor", CommunityJson.strictTree(bytes, bytes.length));
        CommunityValues.unique(dependencies, PluginDependencyRef::pluginId, "/descriptor/dependencies");
        if (!VersionRequirement.parse(requiredSdk).valid()) throw new ContractException("DESCRIPTOR_MISMATCH", "/descriptor/requiredSdk");
        for (var dependency : dependencies) if (!dependency.requirement().valid()) {
            throw new ContractException("DESCRIPTOR_MISMATCH", "/descriptor/dependencies");
        }
        for (String signal : riskDeclaration.signals()) {
            ControlledCatalogs.require(ControlledCatalogs.RISK_SIGNALS, signal, "/descriptor/riskDeclaration/signals");
        }
    }
}
