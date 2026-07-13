package top.sywyar.pixivdownload.plugin.lifecycle;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.ExternalRuntimeCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PluginCapabilityContributionAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PluginContextCapabilityContributionAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPublication;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;

/** Coordinates unpublished external capability preparation, atomic admission and exact-owner teardown. */
@Component
public class PluginCapabilityContributionRegistrar {

    public static final class PreparedOwner {
        private final ExternalCapabilityPreparation preparation;
        private final List<PreparedAdapter> adapters;
        private boolean preparationStarted;
        private boolean preparationComplete;
        private boolean consumed;
        private boolean cleanupPending;
        private boolean cleanupComplete;
        private PublishedOwner cleanupBatch;

        private PreparedOwner(
                ExternalCapabilityPreparation preparation,
                List<PreparedAdapter> adapters) {
            this.preparation = preparation;
            this.adapters = adapters;
        }

        public ExternalCapabilityOwner owner() {
            return preparation.owner();
        }
    }

    private record PreparedAdapter(
            ExternalRuntimeCapabilityAdapter adapter,
            ExternalRuntimeCapabilityAdapter.PreparedContribution contribution
    ) {
    }

    private static final class PublishedOwner {
        private final PreparedOwner preparedOwner;
        private ExternalCapabilityPublication publication;
        private ExternalCapabilityDrain drain;
        private final List<PreparedAdapter> adapters;
        private boolean centralRetired;
        private boolean centralAcknowledged;
        private boolean centralForgotten;
        private boolean failedCleanupComplete;

        private PublishedOwner(PreparedOwner preparedOwner, List<PreparedAdapter> adapters) {
            this.preparedOwner = preparedOwner;
            this.adapters = adapters;
        }

        private void published(ExternalCapabilityPublication value) {
            publication = value;
        }

        private ExternalCapabilityPublication publication() {
            return publication;
        }

        private List<PreparedAdapter> adapters() {
            return adapters;
        }

        private ExternalCapabilityOwner owner() {
            return preparedOwner.owner();
        }
    }

    private final List<PluginCapabilityContributionAdapter<?>> legacyAdapters;
    private final List<PluginContextCapabilityContributionAdapter> legacyContextAdapters;
    private final List<ExternalRuntimeCapabilityAdapter> runtimeAdapters;
    private final ExternalCapabilityInvocationRegistry invocationRegistry;
    private final Runnable centralCleanupProbe;
    private final Object publicationLock = new Object();
    private final Map<Long, PublishedOwner> published = new HashMap<>();
    private final Map<String, ExternalCapabilityPublication> legacyPublications = new HashMap<>();
    private final Map<String, ExternalCapabilityDrain> legacyDrains = new HashMap<>();

    /** Test/legacy constructor retaining the original immediate adapter contract. */
    public PluginCapabilityContributionRegistrar(List<PluginCapabilityContributionAdapter<?>> adapters) {
        this(adapters, List.of());
    }

    /** Test/legacy constructor retaining the original immediate adapter contract. */
    public PluginCapabilityContributionRegistrar(
            List<PluginCapabilityContributionAdapter<?>> adapters,
            List<PluginContextCapabilityContributionAdapter> contextAdapters) {
        this.legacyAdapters = sortedLegacy(adapters);
        this.legacyContextAdapters = sortedContexts(contextAdapters);
        this.runtimeAdapters = List.of();
        this.invocationRegistry = new ExternalCapabilityInvocationRegistry();
        this.centralCleanupProbe = () -> {
        };
    }

    /** Production wiring: migrated adapters use the unpublished central boundary; non-migrated adapters stay legacy. */
    @Autowired
    public PluginCapabilityContributionRegistrar(
            List<PluginCapabilityContributionAdapter<?>> adapters,
            List<PluginContextCapabilityContributionAdapter> contextAdapters,
            List<ExternalRuntimeCapabilityAdapter> runtimeAdapters,
            ExternalCapabilityInvocationRegistry invocationRegistry) {
        this(adapters, contextAdapters, runtimeAdapters, invocationRegistry, () -> {
        });
    }

    PluginCapabilityContributionRegistrar(
            List<PluginCapabilityContributionAdapter<?>> adapters,
            List<PluginContextCapabilityContributionAdapter> contextAdapters,
            List<ExternalRuntimeCapabilityAdapter> runtimeAdapters,
            ExternalCapabilityInvocationRegistry invocationRegistry,
            Runnable centralCleanupProbe) {
        this.legacyAdapters = sortedLegacy(adapters == null ? List.of() : adapters.stream()
                .filter(adapter -> !(adapter instanceof ExternalRuntimeCapabilityAdapter))
                .toList());
        this.legacyContextAdapters = sortedContexts(contextAdapters == null ? List.of() : contextAdapters.stream()
                .filter(adapter -> !(adapter instanceof ExternalRuntimeCapabilityAdapter))
                .toList());
        this.runtimeAdapters = runtimeAdapters == null ? List.of() : runtimeAdapters.stream()
                .sorted(Comparator.comparing(ExternalRuntimeCapabilityAdapter::capabilityName))
                .toList();
        this.invocationRegistry = java.util.Objects.requireNonNull(
                invocationRegistry, "external capability invocation registry");
        this.centralCleanupProbe = java.util.Objects.requireNonNull(
                centralCleanupProbe, "external capability central cleanup probe");
    }

    /** Allocate a pure caller-owned slot before any central state or raw child target is installed. */
    public PreparedOwner allocateOwner(
            String pluginId,
            String packageId,
            long pluginGeneration) {
        synchronized (publicationLock) {
            rejectTrackedOwnerClash(pluginId, packageId);
            ExternalCapabilityPreparation preparation = invocationRegistry.allocatePreparation(
                    pluginId, packageId, pluginGeneration);
            return new PreparedOwner(preparation, new ArrayList<>());
        }
    }

    /** Install and populate a preheld slot; every side effect is recoverable through that exact slot. */
    public void prepareInto(PreparedOwner preparedOwner, ConfigurableApplicationContext context) {
        if (preparedOwner == null) {
            throw new IllegalArgumentException("prepared external capability owner must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("external capability child context must not be null");
        }
        Throwable failure = null;
        try {
            synchronized (publicationLock) {
                if (preparedOwner.preparationStarted || preparedOwner.consumed) {
                    throw new IllegalStateException("external capability preparation is already started: "
                            + preparedOwner.owner());
                }
                preparedOwner.preparationStarted = true;
            }
            invocationRegistry.installPreparation(preparedOwner.preparation);
            for (ExternalRuntimeCapabilityAdapter adapter : runtimeAdapters) {
                ExternalRuntimeCapabilityAdapter.PreparedContribution contribution =
                        adapter.prepare(preparedOwner.preparation, context);
                if (contribution == null || !preparedOwner.owner().equals(contribution.owner())) {
                    throw new IllegalStateException("runtime capability adapter returned an invalid prepared owner: "
                            + adapter.capabilityName());
                }
                preparedOwner.adapters.add(new PreparedAdapter(adapter, contribution));
            }
            preparedOwner.preparationComplete = true;
        } catch (Throwable preparationFailure) {
            failure = preparationFailure;
            throw projectFailure("prepare", preparedOwner.owner(), preparationFailure, List.of());
        } finally {
            if (failure != null) {
                Throwable cleanupFailure = null;
                try {
                    discardUnpublished(preparedOwner);
                } catch (Throwable discardFailure) {
                    cleanupFailure = discardFailure;
                }
                Throwable fatal = firstFatal(failure, cleanupFailure);
                if (fatal != null) {
                    rethrowFatal(fatal);
                }
                if (cleanupFailure != null) {
                    throw projectFailure("discard", preparedOwner.owner(), cleanupFailure, List.of());
                }
            }
        }
    }

    /** Publish all downstream proxy snapshots first, then atomically open central admission. */
    public ExternalCapabilityPublication publish(PreparedOwner preparedOwner) {
        if (preparedOwner == null) {
            throw new IllegalArgumentException("prepared external capability owner must not be null");
        }
        synchronized (publicationLock) {
            if (preparedOwner.consumed || preparedOwner.cleanupPending) {
                throw new IllegalStateException("external capability preparation is already consumed: "
                        + preparedOwner.owner());
            }
            if (!preparedOwner.preparationComplete) {
                throw new IllegalStateException("external capability preparation is incomplete: "
                        + preparedOwner.owner());
            }
            List<PreparedAdapter> visible = new ArrayList<>(preparedOwner.adapters.size());
            List<Throwable> cleanupFailures = new ArrayList<>(preparedOwner.adapters.size() + 1);
            Throwable failure = null;
            PublishedOwner batch = new PublishedOwner(preparedOwner, visible);
            preparedOwner.cleanupBatch = batch;
            try {
                published.put(preparedOwner.owner().publicationId(), batch);
                for (PreparedAdapter prepared : preparedOwner.adapters) {
                    // Add first: an adapter that partially updates then throws must still be withdrawn.
                    visible.add(prepared);
                    prepared.adapter().publish(prepared.contribution());
                }
                ExternalCapabilityPublication publication = invocationRegistry.publish(preparedOwner.preparation);
                batch.published(publication);
                preparedOwner.consumed = true;
                return publication;
            } catch (Throwable publicationFailure) {
                failure = publicationFailure;
                preparedOwner.cleanupPending = true;
                cleanupFailedPublication(batch, cleanupFailures);
                if (batch.centralForgotten && cleanupFailures.isEmpty()) {
                    completeFailedPublicationCleanup(preparedOwner, batch);
                }
                Throwable fatal = firstFatal(failure, cleanupFailures);
                if (fatal != null) {
                    rethrowFatal(fatal);
                }
                throw projectFailure("publish", preparedOwner.owner(), failure, cleanupFailures);
            }
        }
    }

    private void cleanupFailedPublication(
            PublishedOwner batch,
            List<Throwable> cleanupFailures) {
        withdrawPreparedReverse(batch.owner(), batch.adapters(), cleanupFailures);
        compensateCentralPublication(batch, cleanupFailures);
    }

    private void compensateCentralPublication(
            PublishedOwner batch,
            List<Throwable> cleanupFailures) {
        if (batch.centralForgotten) {
            return;
        }
        boolean interrupted = false;
        try {
            centralCleanupProbe.run();
            ExternalCapabilityDrain drain = batch.drain;
            if (drain == null) {
                drain = invocationRegistry.withdrawPublished(batch.preparedOwner.preparation);
                if (drain != null) {
                    batch.drain = drain;
                }
            }
            if (drain == null) {
                boolean discarded = invocationRegistry.discardUnpublished(batch.preparedOwner.preparation);
                if (!discarded) {
                    throw new IllegalStateException("external capability preparation was neither published nor "
                            + "discardable: " + batch.owner());
                }
                batch.centralRetired = true;
                batch.centralAcknowledged = true;
                batch.centralForgotten = true;
                return;
            }
            while (!drain.isDrained()) {
                if (!drain.awaitDrained()) {
                    interrupted = true;
                    Thread.interrupted();
                }
            }
            if (!batch.centralRetired) {
                invocationRegistry.retireDrained(drain);
                batch.centralRetired = true;
            }
            if (!batch.centralAcknowledged) {
                invocationRegistry.acknowledgeRetired(drain);
                batch.centralAcknowledged = true;
            }
            if (!batch.centralForgotten) {
                boolean forgotten = invocationRegistry.forgetRetirementAcknowledgement(drain);
                if (!forgotten && !batch.centralAcknowledged) {
                    throw new IllegalStateException("external capability retirement acknowledgement is missing: "
                            + batch.owner());
                }
                // A false result after an acknowledged exact owner means a prior remove completed before return.
                batch.centralForgotten = true;
            }
        } catch (Throwable cleanupFailure) {
            cleanupFailures.add(cleanupFailure);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Atomically stop new calls for an exact publication and return its in-flight drain. */
    public Optional<ExternalCapabilityDrain> withdraw(ExternalCapabilityPublication publication) {
        if (publication == null) {
            return Optional.empty();
        }
        synchronized (publicationLock) {
            PublishedOwner owner = published.get(publication.owner().publicationId());
            if (owner == null || owner.publication() != publication) {
                return Optional.empty();
            }
            if (owner.drain != null) {
                return Optional.of(owner.drain);
            }
            Optional<ExternalCapabilityDrain> withdrawn = invocationRegistry.withdraw(publication);
            if (withdrawn.isPresent()) {
                owner.drain = withdrawn.orElseThrow();
            }
            return withdrawn;
        }
    }

    /** Recover a successful central publication when lifecycle was interrupted before recording the return value. */
    public Optional<ExternalCapabilityPublication> recoverPublication(PreparedOwner preparedOwner) {
        if (preparedOwner == null) {
            return Optional.empty();
        }
        synchronized (publicationLock) {
            PublishedOwner owner = published.get(preparedOwner.owner().publicationId());
            if (owner == null || owner.preparedOwner != preparedOwner || owner.publication() == null) {
                return Optional.empty();
            }
            return Optional.of(owner.publication());
        }
    }

    /** Discard a prepared batch that was never published. */
    public boolean discardUnpublished(PreparedOwner preparedOwner) {
        if (preparedOwner == null) {
            return false;
        }
        synchronized (publicationLock) {
            if (preparedOwner.consumed) {
                if (preparedOwner.cleanupComplete) {
                    return true;
                }
                PublishedOwner completed = preparedOwner.cleanupBatch;
                if (completed != null && completed.failedCleanupComplete) {
                    finishPreparedCleanupFlags(preparedOwner);
                    return true;
                }
                return false;
            }
            if (preparedOwner.cleanupPending) {
                PublishedOwner batch = preparedOwner.cleanupBatch;
                if (batch == null) {
                    return false;
                }
                List<Throwable> failures = new ArrayList<>(batch.adapters().size() + 1);
                cleanupFailedPublication(batch, failures);
                if (batch.centralForgotten && failures.isEmpty()) {
                    completeFailedPublicationCleanup(preparedOwner, batch);
                    return true;
                }
                Throwable fatal = firstFatal(null, failures);
                if (fatal != null) {
                    rethrowFatal(fatal);
                }
                throw projectFailure(
                        "retry failed publication cleanup",
                        preparedOwner.owner(), failures.get(0), failures.subList(1, failures.size()));
            }
            boolean discarded = invocationRegistry.discardUnpublished(preparedOwner.preparation);
            if (discarded) {
                preparedOwner.cleanupComplete = true;
                preparedOwner.consumed = true;
            }
            return discarded;
        }
    }

    /**
     * Remove every exact downstream proxy and clear central raw targets. Cleanup always reaches every adapter and
     * the central registry; a later VM/Thread fatal takes priority over an earlier ordinary failure.
     */
    public void retireDrained(ExternalCapabilityDrain drain) {
        if (drain == null) {
            throw new IllegalArgumentException("external capability drain must not be null");
        }
        synchronized (publicationLock) {
            PublishedOwner owner = published.get(drain.owner().publicationId());
            if (owner == null || owner.publication() == null
                    || !owner.publication().owner().equals(drain.owner())) {
                throw new IllegalStateException("unknown external capability publication drain: " + drain.owner());
            }
            if (owner.drain == null) {
                owner.drain = drain;
            } else if (owner.drain != drain) {
                throw new IllegalStateException("external capability publication drain mismatch: " + drain.owner());
            }
            List<Throwable> failures = new ArrayList<>(owner.adapters().size() + 1);
            withdrawPreparedReverse(drain.owner(), owner.adapters(), failures);
            if (!owner.centralRetired) {
                try {
                    invocationRegistry.retireDrained(drain);
                    owner.centralRetired = true;
                } catch (Throwable retirementFailure) {
                    failures.add(retirementFailure);
                }
            }
            Throwable fatal = firstFatal(null, failures);
            if (fatal != null) {
                rethrowFatal(fatal);
            }
            if (!failures.isEmpty()) {
                throw projectFailure("retire", drain.owner(), failures.get(0), failures.subList(1, failures.size()));
            }
        }
    }

    /** Acknowledge and forget the central host proof while retaining this exact registrar tombstone for retry. */
    public void acknowledgeRetired(ExternalCapabilityDrain drain) {
        if (drain == null) {
            throw new IllegalArgumentException("external capability drain must not be null");
        }
        synchronized (publicationLock) {
            PublishedOwner owner = requirePublishedDrain(drain);
            if (!owner.centralRetired) {
                throw new IllegalStateException("external capability drain is not retired: " + drain.owner());
            }
            if (!owner.centralAcknowledged) {
                invocationRegistry.acknowledgeRetired(drain);
                owner.centralAcknowledged = true;
            }
            if (!owner.centralForgotten) {
                boolean forgotten = invocationRegistry.forgetRetirementAcknowledgement(drain);
                if (!forgotten && !owner.centralAcknowledged) {
                    throw new IllegalStateException("external capability retirement acknowledgement is missing: "
                            + drain.owner());
                }
                owner.centralForgotten = true;
            }
        }
    }

    /** Drop adapter/publication references only after lifecycle has closed the exact child context. */
    public boolean releaseRetirementProof(ExternalCapabilityDrain drain) {
        if (drain == null) {
            return false;
        }
        synchronized (publicationLock) {
            PublishedOwner owner = published.get(drain.owner().publicationId());
            if (owner == null) {
                return false;
            }
            if (owner.drain != drain || !owner.centralForgotten) {
                throw new IllegalStateException("external capability retirement proof is not releasable: "
                        + drain.owner());
            }
            legacyPublications.remove(drain.owner().pluginId(), owner.publication());
            legacyDrains.remove(drain.owner().pluginId(), drain);
            owner.adapters().clear();
            owner.preparedOwner.cleanupBatch = null;
            published.remove(drain.owner().publicationId(), owner);
            return true;
        }
    }

    /**
     * Compatibility bridge used until lifecycle wiring passes package/generation explicitly. Production migrated
     * adapters still use the unpublished boundary; remaining legacy adapters retain their established behavior.
     */
    public void register(String pluginId, ConfigurableApplicationContext context) {
        if (context == null) {
            unregister(pluginId);
            return;
        }
        PreparedOwner preparedOwner = null;
        boolean legacyRegistered = false;
        try {
            registerLegacy(pluginId, context);
            legacyRegistered = true;
            if (!runtimeAdapters.isEmpty()) {
                preparedOwner = allocateOwner(pluginId, pluginId, 0L);
                prepareInto(preparedOwner, context);
                ExternalCapabilityPublication publication = publish(preparedOwner);
                synchronized (publicationLock) {
                    legacyPublications.put(pluginId, publication);
                }
            }
        } catch (Throwable failure) {
            if (preparedOwner != null && !preparedOwner.consumed) {
                discardUnpublished(preparedOwner);
            }
            Throwable cleanupFatal = null;
            if (legacyRegistered) {
                try {
                    unregisterLegacy(pluginId);
                } catch (Throwable cleanupFailure) {
                    cleanupFatal = mergeFatal(cleanupFatal, cleanupFailure);
                    addSuppressedSafely(failure, cleanupFailure);
                }
            }
            if (isFatal(failure)) {
                rethrowFatal(failure);
            }
            if (cleanupFatal != null) {
                rethrowFatal(cleanupFatal);
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalStateException("failed to register runtime capabilities for plugin '"
                    + pluginId + "' (failureType=" + failure.getClass().getName() + ")");
        }
    }

    /**
     * Registers only adapters that have not migrated to the exact-owner invocation boundary.
     * Lifecycle uses this bridge for queue operations while migrated capabilities remain unpublished.
     */
    void registerLegacy(String pluginId, ConfigurableApplicationContext context) {
        if (context == null) {
            unregisterLegacy(pluginId);
            return;
        }
        List<PluginCapabilityContributionAdapter<?>> registered = new ArrayList<>();
        List<PluginContextCapabilityContributionAdapter> registeredContexts = new ArrayList<>();
        try {
            for (PluginCapabilityContributionAdapter<?> adapter : legacyAdapters) {
                registered.add(adapter);
                registerOne(pluginId, context, adapter);
            }
            for (PluginContextCapabilityContributionAdapter adapter : legacyContextAdapters) {
                registeredContexts.add(adapter);
                adapter.register(pluginId, context);
            }
        } catch (Throwable failure) {
            Throwable cleanupFatal = rollbackContexts(pluginId, registeredContexts, failure);
            cleanupFatal = mergeFatal(cleanupFatal, rollback(pluginId, registered, failure));
            if (isFatal(failure)) {
                rethrowFatal(failure);
            }
            if (cleanupFatal != null) {
                rethrowFatal(cleanupFatal);
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalStateException("failed to register legacy runtime capabilities for plugin '"
                    + pluginId + "' (failureType=" + failure.getClass().getName() + ")");
        }
    }

    public void unregister(String pluginId) {
        List<String> failureTypes = new ArrayList<>();
        Throwable fatal = null;
        ExternalCapabilityPublication publication;
        ExternalCapabilityDrain existingDrain;
        synchronized (publicationLock) {
            publication = legacyPublications.get(pluginId);
            existingDrain = legacyDrains.get(pluginId);
        }
        if (publication != null) {
            try {
                ExternalCapabilityDrain drain = existingDrain;
                if (drain == null) {
                    drain = withdraw(publication).orElse(null);
                    if (drain != null) {
                        synchronized (publicationLock) {
                            legacyDrains.put(pluginId, drain);
                        }
                    }
                }
                if (drain != null) {
                    if (!drain.isDrained()) {
                        failureTypes.add("in-flight external capability invocation");
                    } else {
                        retireDrained(drain);
                        acknowledgeRetired(drain);
                        releaseRetirementProof(drain);
                    }
                }
            } catch (Throwable failure) {
                fatal = mergeFatal(fatal, failure);
                if (!isFatal(failure)) {
                    failureTypes.add(failure.getClass().getName());
                }
            }
        }
        try {
            unregisterLegacy(pluginId);
        } catch (Throwable failure) {
            fatal = mergeFatal(fatal, failure);
            if (!isFatal(failure)) {
                failureTypes.add(failure.getClass().getName());
            }
        }
        if (fatal != null) {
            rethrowFatal(fatal);
        }
        if (!failureTypes.isEmpty()) {
            throw new IllegalStateException(
                    "failed to unregister one or more runtime capabilities for plugin '" + pluginId
                            + "' (failureTypes=" + failureTypes + ")");
        }
    }

    /** Withdraws every non-migrated adapter while preserving cleanup-all and fatal-priority semantics. */
    void unregisterLegacy(String pluginId) {
        List<String> failureTypes = new ArrayList<>();
        Throwable fatal = null;
        for (PluginCapabilityContributionAdapter<?> adapter : legacyAdapters) {
            try {
                adapter.unregister(pluginId);
            } catch (Throwable adapterFailure) {
                fatal = mergeFatal(fatal, adapterFailure);
                if (!isFatal(adapterFailure)) {
                    failureTypes.add(adapterFailure.getClass().getName());
                }
            }
        }
        for (PluginContextCapabilityContributionAdapter adapter : legacyContextAdapters) {
            try {
                adapter.unregister(pluginId);
            } catch (Throwable adapterFailure) {
                fatal = mergeFatal(fatal, adapterFailure);
                if (!isFatal(adapterFailure)) {
                    failureTypes.add(adapterFailure.getClass().getName());
                }
            }
        }
        if (fatal != null) {
            rethrowFatal(fatal);
        }
        if (!failureTypes.isEmpty()) {
            throw new IllegalStateException(
                    "failed to unregister one or more legacy runtime capabilities for plugin '" + pluginId
                            + "' (failureTypes=" + failureTypes + ")");
        }
    }

    public List<String> capabilityNames() {
        return java.util.stream.Stream.of(
                        legacyAdapters.stream().map(PluginCapabilityContributionAdapter::capabilityName),
                        legacyContextAdapters.stream().map(PluginContextCapabilityContributionAdapter::capabilityName),
                        runtimeAdapters.stream().map(ExternalRuntimeCapabilityAdapter::capabilityName))
                .flatMap(stream -> stream)
                .distinct()
                .sorted()
                .toList();
    }

    private void completeFailedPublicationCleanup(
            PreparedOwner preparedOwner,
            PublishedOwner batch) {
        batch.failedCleanupComplete = true;
        published.remove(preparedOwner.owner().publicationId(), batch);
        preparedOwner.cleanupComplete = true;
        preparedOwner.consumed = true;
        finishPreparedCleanupFlags(preparedOwner);
    }

    private static void finishPreparedCleanupFlags(PreparedOwner preparedOwner) {
        preparedOwner.cleanupPending = false;
        preparedOwner.cleanupBatch = null;
    }

    private PublishedOwner requirePublishedDrain(ExternalCapabilityDrain drain) {
        PublishedOwner owner = published.get(drain.owner().publicationId());
        if (owner == null || owner.drain != drain || owner.publication() == null
                || !owner.publication().owner().equals(drain.owner())) {
            throw new IllegalStateException("unknown external capability publication drain: " + drain.owner());
        }
        return owner;
    }

    private void rejectTrackedOwnerClash(String pluginId, String packageId) {
        for (PublishedOwner owner : published.values()) {
            ExternalCapabilityOwner tracked = owner.owner();
            if (tracked.pluginId().equals(pluginId)) {
                throw new IllegalStateException(
                        "external capability plugin owner cleanup is still tracked: " + pluginId);
            }
            if (tracked.packageId().equals(packageId)) {
                throw new IllegalStateException(
                        "external capability package owner cleanup is still tracked: " + packageId);
            }
        }
    }

    private static List<PluginCapabilityContributionAdapter<?>> sortedLegacy(
            List<PluginCapabilityContributionAdapter<?>> adapters) {
        return adapters == null ? List.of() : adapters.stream()
                .sorted(Comparator.comparing(PluginCapabilityContributionAdapter::capabilityName))
                .toList();
    }

    private static List<PluginContextCapabilityContributionAdapter> sortedContexts(
            List<PluginContextCapabilityContributionAdapter> adapters) {
        return adapters == null ? List.of() : adapters.stream()
                .sorted(Comparator.comparing(PluginContextCapabilityContributionAdapter::capabilityName))
                .toList();
    }

    private static <T> List<T> beans(ConfigurableApplicationContext context, Class<T> type) {
        return List.copyOf(context.getBeansOfType(type).values());
    }

    private static <T> void registerOne(
            String pluginId,
            ConfigurableApplicationContext context,
            PluginCapabilityContributionAdapter<T> adapter) {
        adapter.register(pluginId, beans(context, adapter.beanType()));
    }

    private static void withdrawPreparedReverse(
            ExternalCapabilityOwner owner,
            List<PreparedAdapter> adapters,
            List<Throwable> failures) {
        for (int index = adapters.size() - 1; index >= 0; index--) {
            try {
                adapters.get(index).adapter().withdraw(owner);
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
    }

    private static RuntimeException projectFailure(
            String action,
            ExternalCapabilityOwner owner,
            Throwable failure,
            List<Throwable> cleanupFailures) {
        rethrowFatal(failure);
        String cleanup = cleanupFailures == null || cleanupFailures.isEmpty()
                ? "" : ", cleanupFailureTypes=" + cleanupFailures.stream()
                .map(item -> item.getClass().getName()).toList();
        return new IllegalStateException("failed to " + action + " external runtime capabilities (owner="
                + owner + ", failureType=" + failure.getClass().getName() + cleanup + ")");
    }

    private static Throwable firstFatal(Throwable primary, Throwable cleanup) {
        if (isFatal(primary)) {
            return primary;
        }
        return isFatal(cleanup) ? cleanup : null;
    }

    private static Throwable firstFatal(Throwable primary, List<Throwable> cleanup) {
        if (isFatal(primary)) {
            return primary;
        }
        for (Throwable failure : cleanup) {
            if (isFatal(failure)) {
                return failure;
            }
        }
        return null;
    }

    private static Throwable rollback(
            String pluginId,
            List<PluginCapabilityContributionAdapter<?>> registered,
            Throwable registrationFailure) {
        Throwable fatal = null;
        ListIterator<PluginCapabilityContributionAdapter<?>> iterator = registered.listIterator(registered.size());
        while (iterator.hasPrevious()) {
            try {
                iterator.previous().unregister(pluginId);
            } catch (Throwable rollbackFailure) {
                if (isFatal(rollbackFailure)) {
                    fatal = mergeFatal(fatal, rollbackFailure);
                } else {
                    addSuppressedSafely(registrationFailure, rollbackFailure);
                }
            }
        }
        return fatal;
    }

    private static Throwable rollbackContexts(
            String pluginId,
            List<PluginContextCapabilityContributionAdapter> registered,
            Throwable registrationFailure) {
        Throwable fatal = null;
        ListIterator<PluginContextCapabilityContributionAdapter> iterator =
                registered.listIterator(registered.size());
        while (iterator.hasPrevious()) {
            try {
                iterator.previous().unregister(pluginId);
            } catch (Throwable rollbackFailure) {
                if (isFatal(rollbackFailure)) {
                    fatal = mergeFatal(fatal, rollbackFailure);
                } else {
                    addSuppressedSafely(registrationFailure, rollbackFailure);
                }
            }
        }
        return fatal;
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static Throwable mergeFatal(Throwable current, Throwable failure) {
        if (!isFatal(failure)) {
            return current;
        }
        return current == null ? failure : current;
    }

    private static void addSuppressedSafely(Throwable target, Throwable suppressed) {
        if (target != null && suppressed != null && target != suppressed) {
            try {
                target.addSuppressed(suppressed);
            } catch (Throwable ignored) {
                // Preserve the original legacy failure while still completing every cleanup action.
            }
        }
    }
}
