package top.sywyar.pixivdownload.plugin.security;

import top.sywyar.pixivdownload.plugin.api.security.OperationVerification;
import top.sywyar.pixivdownload.plugin.api.web.page.PageInteraction;
import top.sywyar.pixivdownload.plugin.api.web.page.PageResourceProfile;
import top.sywyar.pixivdownload.plugin.web.remote.RemoteValues;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/** 只检验声明纯值，不生成挑战、不调用供应商。 */
public final class OperationPolicies {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private OperationPolicies() { }

    public static void identity(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9:._-]{1,128}")) invalid();
    }

    public static void protocol(PageInteraction value) {
        if (value == null) invalid();
        identity(value.id());
        if (value.version() < 1) invalid();
        schema(value.stateSchema()); schema(value.responseSchema());
    }

    public static boolean compatible(PageInteraction left, PageInteraction right) {
        try {
            return left.id().equals(right.id()) && left.version() == right.version()
                    && JSON.readTree(left.stateSchema()).equals(JSON.readTree(right.stateSchema()))
                    && JSON.readTree(left.responseSchema()).equals(JSON.readTree(right.responseSchema()));
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("OPERATION_POLICY_INVALID"); }
    }

    public static void schema(String value) {
        if (value == null || value.length() > 16384 || value.getBytes(StandardCharsets.UTF_8).length > 16384) invalid();
        RemoteValues.validateSchema(value);
    }

    public static OperationVerification.Policy validate(OperationVerification.Policy policy) {
        if (policy == null || policy.toString().getBytes(StandardCharsets.UTF_8).length > 65536) invalid();
        identity(policy.revision()); protocol(policy.protocol());
        if (!policy.contextSchema().isEmpty()) schema(policy.contextSchema());
        if (policy.resources().size() > 16) invalid();
        var profiles = new HashSet<String>();
        for (var profile : policy.resources()) {
            identity(profile.id()); identity(profile.revision());
            if (!profiles.add(profile.id()) || profile.sources().size() > 64) invalid();
            var sources = new HashSet<PageResourceProfile.Source>();
            for (var source : profile.sources()) {
                if (source.kind() == null || source.origin() == null || !sources.add(source)) invalid();
                if (source.origin().equals("self")) continue;
                if (source.kind() == PageResourceProfile.Kind.WORKER && source.origin().equals("blob:")) continue;
                if (source.origin().length() > 512) invalid();
                URI uri;
                try { uri = URI.create(source.origin()); }
                catch (IllegalArgumentException failure) { throw new IllegalArgumentException("OPERATION_POLICY_INVALID"); }
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
                        || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535
                        || !source.origin().equals(uri.toASCIIString())) invalid();
            }
        }
        return policy;
    }

    private static void invalid() { throw new IllegalArgumentException("OPERATION_POLICY_INVALID"); }
}
