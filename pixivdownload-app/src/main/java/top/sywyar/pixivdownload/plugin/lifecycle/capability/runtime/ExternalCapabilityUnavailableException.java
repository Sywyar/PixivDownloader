package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

/** Fail-closed signal emitted when a proxy no longer has admission to its exact owner publication. */
public final class ExternalCapabilityUnavailableException extends IllegalStateException {

    public ExternalCapabilityUnavailableException(String message) {
        super(message);
    }
}
