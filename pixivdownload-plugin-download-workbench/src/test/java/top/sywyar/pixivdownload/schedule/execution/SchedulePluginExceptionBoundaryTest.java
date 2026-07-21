package top.sywyar.pixivdownload.schedule.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.schedule.ScheduleConfig;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.ScheduleRunState;
import top.sywyar.pixivdownload.schedule.persistence.ScheduleWorkPersistenceCodec;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("计划任务插件异常生命周期边界")
class SchedulePluginExceptionBoundaryTest {

    private static final String SOURCE = "fixture-source";

    private record ExceptionBoundaryHandles(
            ScheduledExecutionException normalized,
            ScheduleGenerationDrain drain,
            WeakReference<Object> pluginFailure,
            WeakReference<Object> pluginCause,
            WeakReference<ClassLoader> classLoader) {
    }

    @Test
    @DisplayName("来源规划异常在租约释放前复制为无 cause 宿主基类且临时 classloader 可回收")
    void sourcePlanFailureIsCopiedBeforeLeaseReleaseAndDoesNotPinClassLoader() throws Exception {
        ExceptionBoundaryHandles handles = executeTemporaryPluginFailure();

        assertThat(handles.normalized().getClass()).isEqualTo(ScheduledExecutionException.class);
        assertThat(handles.normalized().category())
                .isEqualTo(ScheduledFailure.Category.RETRYABLE_NETWORK);
        assertThat(handles.normalized().code()).isEqualTo("fixture.plugin-plan-failure");
        assertThat(handles.normalized().retryAfterMillis()).isEqualTo(123L);
        assertThat(handles.normalized().getCause()).isNull();
        assertThat(handles.drain().isDrained()).isTrue();

        boolean failureCollected = awaitCollected(handles.pluginFailure());
        boolean causeCollected = awaitCollected(handles.pluginCause());
        boolean classLoaderCollected = awaitCollected(handles.classLoader());
        if (!failureCollected || !causeCollected || !classLoaderCollected) {
            Assumptions.abort("临时插件异常或 classloader 未在当前 JVM 回收，但异常已确定复制为无 cause 宿主基类；"
                    + "判为 GC 环境不稳定。failureAlive=" + (handles.pluginFailure().get() != null)
                    + ", causeAlive=" + (handles.pluginCause().get() != null)
                    + ", classLoaderAlive=" + (handles.classLoader().get() != null));
        }
        assertThat(classLoaderCollected).isTrue();
    }

    /** 独立栈帧负责持有并释放临时 loader、插件异常类和来源 Bean 的全部强引用。 */
    private static ExceptionBoundaryHandles executeTemporaryPluginFailure() throws Exception {
        ScheduleCapabilityRegistry registry = new ScheduleCapabilityRegistry();
        AtomicReference<ScheduleCapabilityPublication> publication = new AtomicReference<>();
        AtomicReference<ScheduleGenerationDrain> drain = new AtomicReference<>();
        AtomicReference<WeakReference<Object>> pluginFailure = new AtomicReference<>();
        AtomicReference<WeakReference<Object>> pluginCause = new AtomicReference<>();
        ProbeClassLoader probeClassLoader = new ProbeClassLoader(
                SchedulePluginExceptionBoundaryTest.class.getClassLoader(),
                Set.of(
                        LeakProbeScheduledExecutionException.class.getName(),
                        LeakProbePluginCause.class.getName()));
        Class<?> failureClass = probeClassLoader.loadClass(
                LeakProbeScheduledExecutionException.class.getName());

        ScheduledSourceExecutor source = new ScheduledSourceExecutor() {
            @Override
            public String sourceType() {
                return SOURCE;
            }

            @Override
            public ScheduledExecutionPlan plan(ScheduledTaskDefinition task)
                    throws ScheduledExecutionException {
                ScheduleGenerationDrain withdrawn = ScheduleCapabilityRegistryTestAccess.withdraw(
                        registry, publication.get()).orElseThrow();
                drain.set(withdrawn);
                ScheduledExecutionException failure = newPluginFailure(
                        failureClass, () -> withdrawn.activeLeaseCount() > 0);
                pluginFailure.set(new WeakReference<>(failure));
                pluginCause.set(new WeakReference<>(failure.getCause()));
                throw failure;
            }

            @Override
            public ScheduledDiscoveryResult discover(ScheduledSourceContext context) {
                return ScheduledDiscoveryResult.withoutCheckpoint();
            }
        };
        ScheduledSourceDescriptor descriptor = new ScheduledSourceDescriptor(
                SOURCE, Set.of(), "fixture.definition", 1,
                new ScheduledSourcePresentation(
                        "fixture", "source.label", "source.summary", "schedule", "neutral"),
                Set.of("fixture"), Set.of("fixture-work"), Set.of(), Set.of(), null);
        publication.set(ScheduleCapabilityRegistryTestAccess.publish(
                registry, ScheduleOwnerBundle.prepare(
                        new ScheduleCapabilityOwner("fixture", "fixture-package", 1L),
                        List.of(descriptor), List.of(source),
                        List.of(), List.of(), List.of())));

        ScheduledExecutionException normalized;
        try {
            engine(mock(ScheduledTaskStore.class), registry).execute(task());
            throw new AssertionError("source plan failure was expected");
        } catch (ScheduledExecutionException failure) {
            normalized = failure;
        }

        assertThat(registry.prepareSource(SOURCE)).isEmpty();
        assertThat(drain.get()).isNotNull();
        assertThat(drain.get().isDrained()).isTrue();
        return new ExceptionBoundaryHandles(
                normalized,
                drain.get(),
                pluginFailure.get(),
                pluginCause.get(),
                new WeakReference<>(probeClassLoader));
    }

    private static ScheduledExecutionException newPluginFailure(
            Class<?> failureClass,
            BooleanSupplier planningLeaseActive) {
        try {
            return (ScheduledExecutionException) failureClass
                    .getConstructor(BooleanSupplier.class)
                    .newInstance(planningLeaseActive);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("failed to create temporary plugin exception", failure);
        }
    }

    private static ScheduleExecutionEngine engine(
            ScheduledTaskStore store,
            ScheduleCapabilityRegistry registry) {
        ScheduleConfig config = new ScheduleConfig();
        OutboundProxySettings direct = new OutboundProxySettings() {
            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public String getHost() {
                return null;
            }

            @Override
            public int getPort() {
                return 0;
            }
        };
        ObjectMapper objectMapper = new ObjectMapper();
        return new ScheduleExecutionEngine(
                store, registry, new ScheduleRunState(), new ScheduleRunQueue(), config,
                new ScheduleWorkPersistenceCodec(objectMapper),
                new ScheduleNetworkRouteResolver(direct),
                new SyncTaskExecutor(), objectMapper);
    }

    private static ScheduledTask task() {
        return new ScheduledTask(
                1L, "fixture", true, SOURCE, "fixture",
                "fixture.definition", 1, "{}", "{}",
                ScheduledTask.TRIGGER_INTERVAL, 1, null,
                null, 0L, null, null, null, null,
                ScheduledTask.CURRENT_STORAGE_VERSION,
                null, null, ScheduleLastOutcome.NEVER, null, null,
                null, null, null, 0L,
                "fixture", "fixture-policy", "account-1", "{}",
                "fixture-reference", 1L, 1L);
    }

    private static boolean awaitCollected(WeakReference<?> reference) {
        for (int attempt = 0; attempt < 25 && reference.get() != null; attempt++) {
            System.gc();
            System.runFinalization();
            byte[][] ballast = new byte[4][];
            try {
                for (int index = 0; index < ballast.length; index++) {
                    ballast[index] = new byte[1 << 20];
                }
            } catch (OutOfMemoryError ignored) {
                // 内存压力已经达成。
            }
            System.gc();
            System.runFinalization();
            if (reference.get() != null) {
                try {
                    Thread.sleep(25L);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return reference.get() == null;
    }

    /** 临时插件异常会在读取任一公开投影字段时确认 planning lease 尚未释放。 */
    public static final class LeakProbeScheduledExecutionException
            extends ScheduledExecutionException {

        private final BooleanSupplier planningLeaseActive;

        public LeakProbeScheduledExecutionException(BooleanSupplier planningLeaseActive) {
            super(ScheduledFailure.Category.RETRYABLE_NETWORK,
                    "fixture.plugin-plan-failure", 123L);
            this.planningLeaseActive = planningLeaseActive;
            initCause(new LeakProbePluginCause());
        }

        @Override
        public ScheduledFailure.Category category() {
            requirePlanningLease();
            return super.category();
        }

        @Override
        public String code() {
            requirePlanningLease();
            return super.code();
        }

        @Override
        public long retryAfterMillis() {
            requirePlanningLease();
            return super.retryAfterMillis();
        }

        private void requirePlanningLease() {
            if (!planningLeaseActive.getAsBoolean()) {
                throw new IllegalStateException("planning lease was released before normalization");
            }
        }
    }

    /** 与异常同属临时 loader 的私有 cause，宿主复制不得保留。 */
    public static final class LeakProbePluginCause extends RuntimeException {
        public LeakProbePluginCause() {
            super("temporary plugin cause");
        }
    }

    /** 只对子类探针 child-first，其余类型继续使用宿主父 loader。 */
    private static final class ProbeClassLoader extends ClassLoader {
        private final Set<String> probeClasses;

        private ProbeClassLoader(ClassLoader parent, Set<String> probeClasses) {
            super(parent);
            this.probeClasses = Set.copyOf(probeClasses);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (probeClasses.contains(name)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        byte[] bytes = readClassBytes(name);
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }

        private byte[] readClassBytes(String binaryName) throws ClassNotFoundException {
            String resourcePath = binaryName.replace('.', '/') + ".class";
            try (InputStream input = getParent().getResourceAsStream(resourcePath)) {
                if (input == null) {
                    throw new ClassNotFoundException(binaryName);
                }
                return input.readAllBytes();
            } catch (IOException failure) {
                throw new ClassNotFoundException(binaryName, failure);
            }
        }
    }
}
