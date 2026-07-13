package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

/**
 * Host-owned projection of a non-fatal child capability failure.
 *
 * <p>It intentionally has no cause constructor so a child Throwable graph cannot escape or become a
 * long-lived host reference.
 */
public final class ExternalCapabilityInvocationException extends RuntimeException {

    public ExternalCapabilityInvocationException(String message) {
        super(message);
    }
}
