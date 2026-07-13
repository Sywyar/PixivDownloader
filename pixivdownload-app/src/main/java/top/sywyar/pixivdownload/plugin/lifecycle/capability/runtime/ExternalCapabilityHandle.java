package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import java.util.Objects;

/** Target-free pure value used by a parent-loader proxy to resolve one synchronous capability call. */
public record ExternalCapabilityHandle(
        ExternalCapabilityOwner owner,
        long capabilityToken,
        String capabilityTypeName
) {

    public ExternalCapabilityHandle {
        Objects.requireNonNull(owner, "owner");
        if (capabilityToken <= 0L) {
            throw new IllegalArgumentException("external capability token must be positive");
        }
        if (capabilityTypeName == null || capabilityTypeName.isBlank()) {
            throw new IllegalArgumentException("external capability type name must not be blank");
        }
    }
}
