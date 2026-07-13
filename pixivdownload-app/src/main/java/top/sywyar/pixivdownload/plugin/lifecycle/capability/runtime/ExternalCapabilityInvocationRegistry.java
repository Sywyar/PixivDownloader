package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.BaseStream;

/**
 * Host-owned admission and invocation boundary for synchronous external runtime capabilities.
 *
 * <p>Raw child targets live only in unpublished/published owner state in this registry. Public registries receive
 * parent-loader JDK proxies whose handler contains only a pure handle and this host registry. Withdrawing a
 * publication atomically rejects new leases; an already active exact owner can make nested synchronous calls to
 * another capability of the same publication until its outer lease is released.
 */
@Component
public final class ExternalCapabilityInvocationRegistry {

    @FunctionalInterface
    public interface MetadataSupplier<T> {
        T get() throws Throwable;
    }

    private enum Phase {
        PREPARED,
        PUBLISHED,
        WITHDRAWN
    }

    private static final class TargetEntry {
        private final Class<?> capabilityType;
        private Object target;

        private TargetEntry(Class<?> capabilityType, Object target) {
            this.capabilityType = capabilityType;
            this.target = target;
        }

        private void clear() {
            target = null;
        }
    }

    private static final class OwnerState {
        private final ExternalCapabilityOwner owner;
        private final ExternalCapabilityPreparation preparation;
        private final ExternalCapabilityLeaseState leaseState;
        private final ExternalCapabilityDrain drain;
        private final Map<Long, TargetEntry> targets = new HashMap<>();
        private ExternalCapabilityPublication publication;
        private Phase phase = Phase.PREPARED;
        private int preparationOperations;

        private OwnerState(
                ExternalCapabilityOwner owner,
                ExternalCapabilityPreparation preparation,
                Runnable beforeAcquirePublishProbe,
                Runnable beforeReleaseProbe,
                Runnable afterReleaseProbe,
                Runnable afterWithdrawPublishProbe) {
            this.owner = owner;
            this.preparation = preparation;
            this.leaseState = new ExternalCapabilityLeaseState(
                    beforeAcquirePublishProbe,
                    beforeReleaseProbe,
                    afterReleaseProbe,
                    afterWithdrawPublishProbe);
            this.drain = new ExternalCapabilityDrain(owner, leaseState);
        }

        private void clearTargets() {
            targets.values().forEach(TargetEntry::clear);
            targets.clear();
        }
    }

    private enum RetirementPhase {
        RETIRED,
        ACKNOWLEDGED
    }

    /** Host-only retirement proof; it deliberately retains no publication token, preparation, target, or Bean. */
    private record RetiredOwner(
            ExternalCapabilityOwner owner,
            ExternalCapabilityLeaseState leaseState,
            ExternalCapabilityDrain drain,
            RetirementPhase phase) {

        private static RetiredOwner from(OwnerState state) {
            return new RetiredOwner(
                    state.owner, state.leaseState, state.drain, RetirementPhase.RETIRED);
        }

        private boolean matches(ExternalCapabilityDrain expected) {
            return owner.equals(expected.owner()) && leaseState == expected.leaseState() && drain == expected;
        }

        private RetiredOwner acknowledged() {
            return phase == RetirementPhase.ACKNOWLEDGED
                    ? this
                    : new RetiredOwner(owner, leaseState, drain, RetirementPhase.ACKNOWLEDGED);
        }
    }

    /** The handler intentionally has exactly the target-free handle and host registry as instance state. */
    private static final class HostInvocationHandler implements InvocationHandler {
        private final ExternalCapabilityHandle handle;
        private final ExternalCapabilityInvocationRegistry registry;

        private HostInvocationHandler(
                ExternalCapabilityHandle handle,
                ExternalCapabilityInvocationRegistry registry) {
            this.handle = handle;
            this.registry = registry;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, arguments);
            }
            return registry.invoke(handle, method, arguments);
        }

        private Object objectMethod(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "equals" -> proxy == (arguments == null ? null : arguments[0]);
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "ExternalCapabilityProxy[type=" + handle.capabilityTypeName()
                        + ", owner=" + handle.owner() + ", token=" + handle.capabilityToken() + "]";
                default -> throw new ExternalCapabilityInvocationException(
                        "unsupported proxy Object method: " + method.getName());
            };
        }
    }

    private final Object lock = new Object();
    private final Map<Long, OwnerState> states = new HashMap<>();
    private final Map<Long, RetiredOwner> retirementProofs = new HashMap<>();
    private final ThreadLocal<ActiveOwnerScope> activeOwners = new ThreadLocal<>();
    private final Runnable postPublishProbe;
    private final Runnable postAcquireProbe;
    private final Runnable beforeLeaseAcquirePublishProbe;
    private final Runnable beforeLeaseReleaseProbe;
    private final Runnable afterLeaseReleaseProbe;
    private final Runnable beforeOwnerScopeCloseProbe;
    private final Runnable afterOwnerScopeCloseProbe;
    private final Runnable afterWithdrawPublishProbe;
    private final Runnable postRetireProbe;
    private final Runnable postAcknowledgeProbe;
    private final Runnable postDiscardProbe;
    private final Runnable postPrepareInstallProbe;
    private long nextPublicationId;
    private long nextCapabilityToken;

    public ExternalCapabilityInvocationRegistry() {
        this(() -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        });
    }

    ExternalCapabilityInvocationRegistry(Runnable postPublishProbe) {
        this(postPublishProbe, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        });
    }

    ExternalCapabilityInvocationRegistry(Runnable postPublishProbe, Runnable postAcquireProbe) {
        this(postPublishProbe, postAcquireProbe, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        }, () -> {
        });
    }

    ExternalCapabilityInvocationRegistry(
            Runnable postPublishProbe,
            Runnable postAcquireProbe,
            Runnable beforeLeaseAcquirePublishProbe,
            Runnable beforeLeaseReleaseProbe,
            Runnable afterLeaseReleaseProbe,
            Runnable beforeOwnerScopeCloseProbe,
            Runnable afterOwnerScopeCloseProbe) {
        this(postPublishProbe,
                postAcquireProbe,
                beforeLeaseAcquirePublishProbe,
                beforeLeaseReleaseProbe,
                afterLeaseReleaseProbe,
                beforeOwnerScopeCloseProbe,
                afterOwnerScopeCloseProbe,
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                });
    }

    ExternalCapabilityInvocationRegistry(
            Runnable postPublishProbe,
            Runnable postAcquireProbe,
            Runnable beforeLeaseAcquirePublishProbe,
            Runnable beforeLeaseReleaseProbe,
            Runnable afterLeaseReleaseProbe,
            Runnable beforeOwnerScopeCloseProbe,
            Runnable afterOwnerScopeCloseProbe,
            Runnable afterWithdrawPublishProbe,
            Runnable postRetireProbe) {
        this(postPublishProbe,
                postAcquireProbe,
                beforeLeaseAcquirePublishProbe,
                beforeLeaseReleaseProbe,
                afterLeaseReleaseProbe,
                beforeOwnerScopeCloseProbe,
                afterOwnerScopeCloseProbe,
                afterWithdrawPublishProbe,
                postRetireProbe,
                () -> {
                },
                () -> {
                },
                () -> {
                });
    }

    ExternalCapabilityInvocationRegistry(
            Runnable postPublishProbe,
            Runnable postAcquireProbe,
            Runnable beforeLeaseAcquirePublishProbe,
            Runnable beforeLeaseReleaseProbe,
            Runnable afterLeaseReleaseProbe,
            Runnable beforeOwnerScopeCloseProbe,
            Runnable afterOwnerScopeCloseProbe,
            Runnable afterWithdrawPublishProbe,
            Runnable postRetireProbe,
            Runnable postAcknowledgeProbe) {
        this(postPublishProbe,
                postAcquireProbe,
                beforeLeaseAcquirePublishProbe,
                beforeLeaseReleaseProbe,
                afterLeaseReleaseProbe,
                beforeOwnerScopeCloseProbe,
                afterOwnerScopeCloseProbe,
                afterWithdrawPublishProbe,
                postRetireProbe,
                postAcknowledgeProbe,
                () -> {
                },
                () -> {
                });
    }

    ExternalCapabilityInvocationRegistry(
            Runnable postPublishProbe,
            Runnable postAcquireProbe,
            Runnable beforeLeaseAcquirePublishProbe,
            Runnable beforeLeaseReleaseProbe,
            Runnable afterLeaseReleaseProbe,
            Runnable beforeOwnerScopeCloseProbe,
            Runnable afterOwnerScopeCloseProbe,
            Runnable afterWithdrawPublishProbe,
            Runnable postRetireProbe,
            Runnable postAcknowledgeProbe,
            Runnable postDiscardProbe) {
        this(postPublishProbe,
                postAcquireProbe,
                beforeLeaseAcquirePublishProbe,
                beforeLeaseReleaseProbe,
                afterLeaseReleaseProbe,
                beforeOwnerScopeCloseProbe,
                afterOwnerScopeCloseProbe,
                afterWithdrawPublishProbe,
                postRetireProbe,
                postAcknowledgeProbe,
                postDiscardProbe,
                () -> {
                });
    }

    ExternalCapabilityInvocationRegistry(
            Runnable postPublishProbe,
            Runnable postAcquireProbe,
            Runnable beforeLeaseAcquirePublishProbe,
            Runnable beforeLeaseReleaseProbe,
            Runnable afterLeaseReleaseProbe,
            Runnable beforeOwnerScopeCloseProbe,
            Runnable afterOwnerScopeCloseProbe,
            Runnable afterWithdrawPublishProbe,
            Runnable postRetireProbe,
            Runnable postAcknowledgeProbe,
            Runnable postDiscardProbe,
            Runnable postPrepareInstallProbe) {
        this.postPublishProbe = Objects.requireNonNull(postPublishProbe, "post-publish probe");
        this.postAcquireProbe = Objects.requireNonNull(postAcquireProbe, "post-acquire probe");
        this.beforeLeaseAcquirePublishProbe = Objects.requireNonNull(
                beforeLeaseAcquirePublishProbe, "before lease acquire publish probe");
        this.beforeLeaseReleaseProbe = Objects.requireNonNull(
                beforeLeaseReleaseProbe, "before lease release probe");
        this.afterLeaseReleaseProbe = Objects.requireNonNull(
                afterLeaseReleaseProbe, "after lease release probe");
        this.beforeOwnerScopeCloseProbe = Objects.requireNonNull(
                beforeOwnerScopeCloseProbe, "before owner scope close probe");
        this.afterOwnerScopeCloseProbe = Objects.requireNonNull(
                afterOwnerScopeCloseProbe, "after owner scope close probe");
        this.afterWithdrawPublishProbe = Objects.requireNonNull(
                afterWithdrawPublishProbe, "after withdraw publish probe");
        this.postRetireProbe = Objects.requireNonNull(postRetireProbe, "post-retire probe");
        this.postAcknowledgeProbe = Objects.requireNonNull(
                postAcknowledgeProbe, "post-acknowledge probe");
        this.postDiscardProbe = Objects.requireNonNull(postDiscardProbe, "post-discard probe");
        this.postPrepareInstallProbe = Objects.requireNonNull(
                postPrepareInstallProbe, "post-prepare install probe");
    }

    /** Allocate a pure host identity. Losing this return value cannot retain state, targets, or a classloader. */
    public ExternalCapabilityPreparation allocatePreparation(
            String pluginId,
            String packageId,
            long pluginGeneration) {
        synchronized (lock) {
            rejectOwnerClash(pluginId, packageId);
            long publicationId = Math.incrementExact(nextPublicationId);
            ExternalCapabilityOwner owner = new ExternalCapabilityOwner(
                    pluginId, packageId, pluginGeneration, publicationId);
            ExternalCapabilityPreparation preparation = new ExternalCapabilityPreparation(owner);
            nextPublicationId = publicationId;
            return preparation;
        }
    }

    /** Install the preheld exact preparation before any adapter is allowed to insert a raw target. */
    public void installPreparation(ExternalCapabilityPreparation preparation) {
        Objects.requireNonNull(preparation, "external capability preparation");
        synchronized (lock) {
            if (preparation.isDiscarded()) {
                throw new IllegalStateException("external capability preparation is already discarded: "
                        + preparation.owner());
            }
            OwnerState existing = states.get(preparation.owner().publicationId());
            if (existing != null) {
                if (existing.preparation == preparation && existing.phase == Phase.PREPARED) {
                    return;
                }
                throw new IllegalStateException("external capability preparation identity is already installed: "
                        + preparation.owner());
            }
            rejectOwnerClash(preparation.owner().pluginId(), preparation.owner().packageId());
            states.put(preparation.owner().publicationId(), new OwnerState(
                    preparation.owner(),
                    preparation,
                    beforeLeaseAcquirePublishProbe,
                    beforeLeaseReleaseProbe,
                    afterLeaseReleaseProbe,
                    afterWithdrawPublishProbe));
            postPrepareInstallProbe.run();
        }
    }

    /**
     * Store one raw target inside an unpublished owner and return a parent-loader JDK proxy.
     * The returned proxy and its invocation handler never contain the target or its classloader.
     */
    public <T> T prepareProxy(
            ExternalCapabilityPreparation preparation,
            Class<T> capabilityType,
            T target) {
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(capabilityType, "capabilityType");
        Objects.requireNonNull(target, "target");
        validateSynchronousSpi(capabilityType);
        if (!capabilityType.isInstance(target)) {
            throw new IllegalArgumentException("external capability target does not implement "
                    + capabilityType.getName());
        }

        synchronized (lock) {
            OwnerState state = requirePrepared(preparation);
            long capabilityToken = Math.incrementExact(nextCapabilityToken);
            ExternalCapabilityHandle handle = new ExternalCapabilityHandle(
                    state.owner, capabilityToken, capabilityType.getName());
            state.targets.put(capabilityToken, new TargetEntry(capabilityType, target));
            nextCapabilityToken = capabilityToken;
            try {
                ClassLoader proxyLoader = capabilityType.getClassLoader();
                if (proxyLoader == null) {
                    proxyLoader = ExternalCapabilityInvocationRegistry.class.getClassLoader();
                }
                Object proxy = Proxy.newProxyInstance(
                        proxyLoader,
                        new Class<?>[]{capabilityType},
                        new HostInvocationHandler(handle, this));
                return capabilityType.cast(proxy);
            } catch (Throwable failure) {
                TargetEntry removed = state.targets.remove(handle.capabilityToken());
                if (removed != null) {
                    removed.clear();
                }
                rethrowFatal(failure);
                if (failure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("failed to create external capability proxy");
            }
        }
    }

    /** Capture parent-owned metadata before proxy publication, without retaining the supplier or a child failure. */
    @SuppressWarnings("unchecked")
    public <T> T captureMetadata(
            ExternalCapabilityPreparation preparation,
            Class<?> capabilityType,
            String label,
            MetadataSupplier<T> supplier) {
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(capabilityType, "capabilityType");
        Objects.requireNonNull(supplier, "supplier");
        OwnerState preparedState;
        synchronized (lock) {
            preparedState = requirePrepared(preparation);
            preparedState.preparationOperations = Math.incrementExact(preparedState.preparationOperations);
        }
        try {
            T raw = supplier.get();
            Object copied = CapabilityValueBoundary.copy(raw, boundaryLoader(capabilityType));
            synchronized (lock) {
                if (requirePrepared(preparation) != preparedState) {
                    throw new IllegalStateException("external capability preparation changed during metadata capture");
                }
            }
            return (T) copied;
        } catch (Throwable failure) {
            rethrowFatal(failure);
            if (failure instanceof ExternalCapabilityInvocationException boundaryFailure) {
                throw boundaryFailure;
            }
            throw new IllegalStateException("failed to capture external capability metadata '"
                    + safeLabel(label) + "' for " + capabilityType.getName()
                    + " (failureType=" + failure.getClass().getName() + ")");
        } finally {
            synchronized (lock) {
                preparedState.preparationOperations--;
                if (preparedState.preparationOperations < 0) {
                    throw new IllegalStateException("external capability preparation operation underflow");
                }
            }
        }
    }

    /** Atomically open admission for every proxy in one fully prepared owner batch. */
    public ExternalCapabilityPublication publish(ExternalCapabilityPreparation preparation) {
        Objects.requireNonNull(preparation, "preparation");
        synchronized (lock) {
            OwnerState state = requirePrepared(preparation);
            if (state.preparationOperations != 0) {
                throw new IllegalStateException("external capability metadata capture is still active: "
                        + state.owner);
            }
            ExternalCapabilityPublication publication = new ExternalCapabilityPublication(state.owner);
            state.leaseState.publish();
            state.publication = publication;
            state.phase = Phase.PUBLISHED;
            postPublishProbe.run();
            return publication;
        }
    }

    /** Discard an unpublished batch and clear every raw target, even when preparation failed part way through. */
    public boolean discardUnpublished(ExternalCapabilityPreparation preparation) {
        if (preparation == null) {
            return false;
        }
        synchronized (lock) {
            OwnerState state = states.get(preparation.owner().publicationId());
            if (state == null) {
                preparation.markDiscarded();
                return true;
            }
            if (state.preparation != preparation || state.phase != Phase.PREPARED) {
                return false;
            }
            if (state.preparationOperations != 0) {
                throw new IllegalStateException("cannot discard external capability preparation while metadata "
                        + "capture is active: " + state.owner);
            }
            preparation.markDiscarded();
            state.clearTargets();
            states.remove(state.owner.publicationId());
            postDiscardProbe.run();
            return true;
        }
    }

    /** Atomically reject new admission for one exact publication and return its in-flight drain. */
    public Optional<ExternalCapabilityDrain> withdraw(ExternalCapabilityPublication publication) {
        if (publication == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            OwnerState state = states.get(publication.owner().publicationId());
            if (state == null) {
                RetiredOwner retired = retirementProof(publication.owner());
                return retired == null ? Optional.empty() : Optional.of(retired.drain());
            }
            if (state.publication != publication
                    || (state.phase != Phase.PUBLISHED
                    && state.phase != Phase.WITHDRAWN)) {
                return Optional.empty();
            }
            Optional<ExternalCapabilityDrain> result = Optional.of(state.drain);
            state.leaseState.withdraw();
            state.phase = Phase.WITHDRAWN;
            return result;
        }
    }

    /**
     * Compensate a publish failure that happened after central admission opened but before its publication token
     * reached the caller. The unpublished case is deliberately left to {@link #discardUnpublished}.
     */
    public ExternalCapabilityDrain withdrawPublished(ExternalCapabilityPreparation preparation) {
        if (preparation == null) {
            return null;
        }
        synchronized (lock) {
            OwnerState state = states.get(preparation.owner().publicationId());
            if (state == null) {
                RetiredOwner retired = retirementProof(preparation.owner());
                return retired == null ? null : retired.drain();
            }
            if (state.preparation != preparation) {
                return null;
            }
            if (state.phase == Phase.PUBLISHED || state.phase == Phase.WITHDRAWN) {
                state.leaseState.withdraw();
                state.phase = Phase.WITHDRAWN;
                return state.drain;
            }
            return null;
        }
    }

    /** Clear raw targets and forget a publication only after its exact drain is at zero. */
    public void retireDrained(ExternalCapabilityDrain drain) {
        Objects.requireNonNull(drain, "drain");
        synchronized (lock) {
            OwnerState state = states.get(drain.owner().publicationId());
            if (state == null) {
                RetiredOwner proof = retirementProof(drain.owner());
                if (proof != null && proof.matches(drain)) {
                    return;
                }
                throw new IllegalStateException("unknown external capability drain: " + drain.owner());
            }
            if (state.owner != drain.owner()
                    || state.leaseState != drain.leaseState() || state.phase != Phase.WITHDRAWN) {
                throw new IllegalStateException("unknown external capability drain: " + drain.owner());
            }
            state.leaseState.withdraw();
            if (!state.leaseState.isDrained()) {
                throw new IllegalStateException("external capability publication is still in flight: "
                        + drain.owner());
            }
            RetiredOwner tombstone = RetiredOwner.from(state);
            state.clearTargets();
            retirementProofs.put(state.owner.publicationId(), tombstone);
            states.remove(state.owner.publicationId());
            postRetireProbe.run();
        }
    }

    /** Remove the host-only retirement tombstone after lifecycle has durably recorded completion. */
    public boolean acknowledgeRetired(ExternalCapabilityDrain drain) {
        Objects.requireNonNull(drain, "drain");
        synchronized (lock) {
            long publicationId = drain.owner().publicationId();
            RetiredOwner proof = retirementProofs.get(publicationId);
            if (proof == null || !proof.matches(drain)) {
                throw new IllegalStateException("external capability drain is not retired: " + drain.owner());
            }
            if (proof.phase() == RetirementPhase.ACKNOWLEDGED) {
                return true;
            }
            RetiredOwner acknowledged = proof.acknowledged();
            retirementProofs.put(publicationId, acknowledged);
            postAcknowledgeProbe.run();
            return true;
        }
    }

    /** Forget the final host-only proof only after lifecycle's completion flag is already stable. */
    public boolean forgetRetirementAcknowledgement(ExternalCapabilityDrain drain) {
        Objects.requireNonNull(drain, "drain");
        synchronized (lock) {
            RetiredOwner proof = retirementProofs.get(drain.owner().publicationId());
            if (proof == null) {
                return false;
            }
            if (!proof.matches(drain) || proof.phase() != RetirementPhase.ACKNOWLEDGED) {
                throw new IllegalStateException("external capability acknowledgement mismatch: "
                        + drain.owner());
            }
            retirementProofs.remove(drain.owner().publicationId());
            return true;
        }
    }

    private Object invoke(
            ExternalCapabilityHandle handle,
            Method method,
            Object[] arguments) throws Throwable {
        OwnerState state = null;
        TargetEntry entry = null;
        Object target = null;
        ActiveOwnerScope ownerScope = null;
        Object rawResult = null;
        Object copiedResult = null;
        Throwable primaryFailure = null;
        CleanupTracker cleanupFailures = new CleanupTracker();
        ExternalCapabilityLeaseState.LeaseToken leaseToken = new ExternalCapabilityLeaseState.LeaseToken();
        try {
            synchronized (lock) {
                state = states.get(handle.owner().publicationId());
                if (state == null || !state.owner.equals(handle.owner())) {
                    throw unavailable(handle);
                }
                entry = state.targets.get(handle.capabilityToken());
                if (entry == null || !entry.capabilityType.getName().equals(handle.capabilityTypeName())
                        || !method.getDeclaringClass().isAssignableFrom(entry.capabilityType)) {
                    throw unavailable(handle);
                }
                boolean nestedSameOwner = hasActiveOwner(state);
                ownerScope = new ActiveOwnerScope(state);
                ownerScope.enter();
                if (!nestedSameOwner) {
                    if (state.phase != Phase.PUBLISHED || !state.leaseState.tryAcquire(leaseToken)) {
                        throw unavailable(handle);
                    }
                } else if (state.phase != Phase.PUBLISHED && state.phase != Phase.WITHDRAWN) {
                    throw unavailable(handle);
                }
                target = entry.target;
                if (target == null) {
                    throw unavailable(handle);
                }
            }

            postAcquireProbe.run();
            try {
                rawResult = method.invoke(target, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
            copiedResult = method.getReturnType() == void.class
                    ? null
                    : CapabilityValueBoundary.copy(rawResult, boundaryLoader(entry.capabilityType));
        } catch (Throwable failure) {
            primaryFailure = failure;
        } finally {
            // Clear every stack-local path to the child target/result before releasing the outer admission lease.
            rawResult = null;
            target = null;
            entry = null;
            if (ownerScope != null) {
                closeOwnerScopeFully(ownerScope, cleanupFailures);
            }
            if (state != null) {
                releaseLeaseFully(state.leaseState, leaseToken, cleanupFailures);
            }
        }

        Throwable fatal = isFatal(primaryFailure) ? primaryFailure : cleanupFailures.firstFatal();
        if (fatal != null) {
            rethrowFatal(fatal);
        }
        if (primaryFailure != null) {
            throw sanitizeInvocationFailure(handle, method, primaryFailure, cleanupFailures);
        }
        if (!cleanupFailures.isEmpty()) {
            throw new ExternalCapabilityInvocationException(
                    "external capability cleanup failed (owner=" + handle.owner()
                            + ", capability=" + handle.capabilityTypeName()
                            + ", failureType=" + cleanupFailures.first().getClass().getName() + ")");
        }
        return copiedResult;
    }

    private static final class CleanupTracker {
        private Throwable first;
        private Throwable firstFatal;

        private void record(Throwable failure) {
            if (first == null) {
                first = failure;
            }
            if (firstFatal == null && isFatal(failure)) {
                firstFatal = failure;
            }
        }

        private boolean isEmpty() {
            return first == null;
        }

        private Throwable first() {
            return first;
        }

        private Throwable firstFatal() {
            return firstFatal;
        }
    }

    private static void closeOwnerScopeFully(
            ActiveOwnerScope ownerScope,
            CleanupTracker cleanupFailures) {
        while (true) {
            try {
                if (!ownerScope.isAttached()) {
                    return;
                }
                ownerScope.close();
            } catch (Throwable cleanupFailure) {
                cleanupFailures.record(cleanupFailure);
            }
        }
    }

    private static void releaseLeaseFully(
            ExternalCapabilityLeaseState leaseState,
            ExternalCapabilityLeaseState.LeaseToken leaseToken,
            CleanupTracker cleanupFailures) {
        while (true) {
            try {
                if (!leaseToken.isActive()) {
                    return;
                }
                leaseState.release(leaseToken);
            } catch (Throwable cleanupFailure) {
                cleanupFailures.record(cleanupFailure);
            }
        }
    }

    /** Preallocated scope guard that can undo a successful push even when a fatal is raised immediately afterwards. */
    private final class ActiveOwnerScope implements AutoCloseable {
        private final OwnerState ownerState;
        private ActiveOwnerScope previous;

        private ActiveOwnerScope(OwnerState ownerState) {
            this.ownerState = ownerState;
        }

        private void enter() {
            previous = activeOwners.get();
            activeOwners.set(this);
        }

        private boolean isAttached() {
            return containsScope(activeOwners.get(), this);
        }

        @Override
        public void close() {
            beforeOwnerScopeCloseProbe.run();
            ActiveOwnerScope current = activeOwners.get();
            if (current == this) {
                if (previous == null) {
                    activeOwners.remove();
                } else {
                    activeOwners.set(previous);
                }
            } else if (containsScope(current, this)) {
                activeOwners.remove();
                throw new IllegalStateException("external capability owner scope mismatch");
            }
            afterOwnerScopeCloseProbe.run();
        }
    }

    private Throwable sanitizeInvocationFailure(
            ExternalCapabilityHandle handle,
            Method method,
            Throwable failure,
            CleanupTracker cleanupFailures) {
        if (failure instanceof ExternalCapabilityInvocationException boundaryFailure
                && cleanupFailures.isEmpty()) {
            return boundaryFailure;
        }
        String diagnostic = "external capability invocation failed (owner=" + handle.owner()
                + ", capability=" + handle.capabilityTypeName()
                + ", method=" + method.getName()
                + ", failureType=" + failure.getClass().getName()
                + (cleanupFailures.isEmpty() ? "" : ", cleanupFailureType="
                + cleanupFailures.first().getClass().getName()) + ")";
        for (Class<?> declared : method.getExceptionTypes()) {
            if (!Throwable.class.isAssignableFrom(declared)
                    || declared == Throwable.class || declared == Exception.class
                    || !declared.isAssignableFrom(failure.getClass())
                    || !isParentOwned(declared, boundaryLoader(method.getDeclaringClass()))) {
                continue;
            }
            Throwable projected = instantiateDeclaredFailure(declared.asSubclass(Throwable.class), diagnostic);
            if (projected != null) {
                return projected;
            }
        }
        if (failure instanceof RuntimeException
                && isParentOwned(failure.getClass(), boundaryLoader(method.getDeclaringClass()))) {
            Throwable projected = instantiateDeclaredFailure(
                    failure.getClass().asSubclass(Throwable.class), diagnostic);
            if (projected != null) {
                return projected;
            }
        }
        return new ExternalCapabilityInvocationException(diagnostic);
    }

    private static Throwable instantiateDeclaredFailure(
            Class<? extends Throwable> type,
            String diagnostic) {
        try {
            Constructor<? extends Throwable> constructor = type.getConstructor(String.class);
            return constructor.newInstance(diagnostic);
        } catch (InvocationTargetException failure) {
            rethrowFatal(failure.getCause());
        } catch (ReflectiveOperationException ignored) {
            // Try the common redacted runtime exception shape below.
        }
        try {
            Constructor<? extends Throwable> constructor = type.getConstructor(String.class, Throwable.class);
            return constructor.newInstance(diagnostic, null);
        } catch (InvocationTargetException failure) {
            rethrowFatal(failure.getCause());
            return null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private OwnerState requirePrepared(ExternalCapabilityPreparation preparation) {
        OwnerState state = states.get(preparation.owner().publicationId());
        if (state == null || state.preparation != preparation || state.phase != Phase.PREPARED) {
            throw new IllegalStateException("unknown external capability preparation: " + preparation.owner());
        }
        return state;
    }

    private void rejectOwnerClash(String pluginId, String packageId) {
        for (OwnerState state : states.values()) {
            if (state.owner.pluginId().equals(pluginId)) {
                throw new IllegalStateException("external capability plugin owner is already live: " + pluginId);
            }
            if (state.owner.packageId().equals(packageId)) {
                throw new IllegalStateException("external capability package owner is already live: " + packageId);
            }
        }
    }

    private RetiredOwner retirementProof(ExternalCapabilityOwner owner) {
        RetiredOwner proof = retirementProofs.get(owner.publicationId());
        return proof != null && proof.owner().equals(owner) ? proof : null;
    }

    private boolean hasActiveOwner(OwnerState state) {
        for (ActiveOwnerScope scope = activeOwners.get(); scope != null; scope = scope.previous) {
            if (scope.ownerState == state) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsScope(ActiveOwnerScope current, ActiveOwnerScope expected) {
        for (ActiveOwnerScope scope = current; scope != null; scope = scope.previous) {
            if (scope == expected) {
                return true;
            }
        }
        return false;
    }

    private static ExternalCapabilityUnavailableException unavailable(ExternalCapabilityHandle handle) {
        return new ExternalCapabilityUnavailableException(
                "external capability publication is unavailable (owner=" + handle.owner()
                        + ", capability=" + handle.capabilityTypeName() + ")");
    }

    static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    private static String safeLabel(String label) {
        return label == null || label.isBlank() ? "unspecified" : label.trim();
    }

    private static ClassLoader boundaryLoader(Class<?> capabilityType) {
        ClassLoader loader = capabilityType.getClassLoader();
        return loader == null ? ExternalCapabilityInvocationRegistry.class.getClassLoader() : loader;
    }

    private static boolean isParentOwned(Class<?> type, ClassLoader boundaryLoader) {
        ClassLoader typeLoader = type.getClassLoader();
        if (typeLoader == null) {
            return true;
        }
        for (ClassLoader current = boundaryLoader; current != null; current = current.getParent()) {
            if (current == typeLoader) {
                return true;
            }
        }
        return false;
    }

    /** Reject signatures whose result or callback could outlive the synchronous invocation lease. */
    static void validateSynchronousSpi(Class<?> capabilityType) {
        if (!capabilityType.isInterface() || !Modifier.isPublic(capabilityType.getModifiers())) {
            throw new IllegalArgumentException("external capability SPI must be a public interface: "
                    + capabilityType.getName());
        }
        for (Method method : capabilityType.getMethods()) {
            if (method.getDeclaringClass() == Object.class || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?> returnType = method.getReturnType();
            if (isDeferredType(returnType) || isCallbackType(returnType)) {
                throw unsafeSignature(capabilityType, method, "deferred or callback return type");
            }
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (isDeferredType(parameterType) || isCallbackType(parameterType)) {
                    throw unsafeSignature(capabilityType, method, "callback-style parameter");
                }
            }
        }
    }

    private static boolean isDeferredType(Class<?> type) {
        return Future.class.isAssignableFrom(type)
                || CompletionStage.class.isAssignableFrom(type)
                || BaseStream.class.isAssignableFrom(type)
                || Iterator.class.isAssignableFrom(type)
                || ListIterator.class.isAssignableFrom(type)
                || Spliterator.class.isAssignableFrom(type)
                || Enumeration.class.isAssignableFrom(type)
                || Flow.Publisher.class.isAssignableFrom(type)
                || Flow.Subscriber.class.isAssignableFrom(type)
                || (Iterable.class.isAssignableFrom(type) && !Collection.class.isAssignableFrom(type))
                || AutoCloseable.class.isAssignableFrom(type)
                || type.getName().equals("org.reactivestreams.Publisher")
                || type.getName().equals("org.reactivestreams.Subscriber");
    }

    private static boolean isCallbackType(Class<?> type) {
        if (type == Runnable.class || Callable.class.isAssignableFrom(type)
                || Executor.class.isAssignableFrom(type)
                || Supplier.class.isAssignableFrom(type)
                || type.getName().startsWith("java.util.function.")) {
            return true;
        }
        if (!type.isInterface()
                || Collection.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type)
                || Optional.class.isAssignableFrom(type)) {
            return false;
        }
        long abstractMethods = java.util.Arrays.stream(type.getMethods())
                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                .filter(method -> method.getDeclaringClass() != Object.class)
                .map(ExternalCapabilityInvocationRegistry::methodSignature)
                .distinct()
                .count();
        return abstractMethods == 1L;
    }

    private static String methodSignature(Method method) {
        return method.getName() + java.util.Arrays.toString(method.getParameterTypes());
    }

    private static IllegalArgumentException unsafeSignature(
            Class<?> capabilityType,
            Method method,
            String reason) {
        return new IllegalArgumentException("external capability SPI requires an explicit lifecycle wrapper: "
                + capabilityType.getName() + "#" + method.getName() + " (" + reason + ")");
    }
}
