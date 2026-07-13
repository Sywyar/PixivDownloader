package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;

/**
 * Two-step host adapter for one family of external synchronous capabilities.
 *
 * <p>{@link #prepare} may capture metadata and create target-free proxies, but must not make them visible.
 * {@link #publish} exposes only the prepared metadata/proxies after every adapter has prepared successfully.
 */
public interface ExternalRuntimeCapabilityAdapter {

    interface PreparedContribution {
        ExternalCapabilityOwner owner();
    }

    String capabilityName();

    PreparedContribution prepare(
            ExternalCapabilityPreparation preparation,
            ConfigurableApplicationContext context);

    void publish(PreparedContribution contribution);

    /** Remove only the exact publication. A stale owner must not delete a replacement. */
    void withdraw(ExternalCapabilityOwner owner);
}
