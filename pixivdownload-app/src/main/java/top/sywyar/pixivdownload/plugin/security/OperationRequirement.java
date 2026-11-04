package top.sywyar.pixivdownload.plugin.security;

import top.sywyar.pixivdownload.plugin.lifecycle.capability.OperationVerificationCapabilityAdapter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 业务 owner 的静态保护下限，页面发布不能移除。 */
public record OperationRequirement(String operation, List<String> policies) {
    public OperationRequirement {
        if (operation == null || !operation.matches("[a-z0-9-]+:[a-zA-Z0-9._-]{1,128}")
                || policies == null || policies.size() > 16 || policies.stream().distinct().count() != policies.size())
            throw new IllegalArgumentException("OPERATION_POLICY_CONFIGURATION_INVALID");
        policies.forEach(OperationVerificationCapabilityAdapter::key);
        policies = List.copyOf(policies);
    }

    public static Map<String, List<String>> collect(List<OperationRequirement> requirements) {
        var values = new TreeMap<String, List<String>>();
        for (var requirement : requirements) {
            if (values.putIfAbsent(requirement.operation(), requirement.policies()) != null)
                throw new IllegalArgumentException("OPERATION_POLICY_CONFIGURATION_INVALID");
        }
        return Map.copyOf(values);
    }
}
