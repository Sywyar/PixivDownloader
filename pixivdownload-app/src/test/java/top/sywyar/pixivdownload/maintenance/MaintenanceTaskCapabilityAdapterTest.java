package top.sywyar.pixivdownload.maintenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar.PreparedOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.ExternalRuntimeCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.MaintenanceTaskCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPublication;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityUnavailableException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("外置维护任务 capability 生命周期测试")
class MaintenanceTaskCapabilityAdapterTest {

    @Test
    @DisplayName("撤回旧代后拒绝新执行并等待已在途维护任务退出")
    void withdrawalRejectsNewCallsAndDrainsInFlightExecution() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MaintenanceTask target = new MaintenanceTask() {
            @Override
            public String name() {
                return "external-maintenance";
            }

            @Override
            public void execute(MaintenanceContext context) throws Exception {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("maintenance test task release timed out");
                }
            }
        };
        MaintenanceTask core = task("core-maintenance");
        MaintenanceTaskRegistry registry = new MaintenanceTaskRegistry(List.of(core));
        ExternalCapabilityInvocationRegistry invocationRegistry = new ExternalCapabilityInvocationRegistry();
        MaintenanceTaskCapabilityAdapter adapter =
                new MaintenanceTaskCapabilityAdapter(registry, invocationRegistry);
        PluginCapabilityContributionRegistrar registrar = new PluginCapabilityContributionRegistrar(
                List.of(), List.of(), List.<ExternalRuntimeCapabilityAdapter>of(adapter), invocationRegistry);

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.getBeanFactory().registerSingleton("externalMaintenanceTask", target);
            child.refresh();
            PreparedOwner prepared = registrar.allocateOwner("duplicate", "duplicate", 7L);
            registrar.prepareInto(prepared, child);
            ExternalCapabilityPublication publication = registrar.publish(prepared);
            MaintenanceTask proxy = registry.tasks().stream()
                    .filter(task -> !task.name().equals("core-maintenance"))
                    .findFirst().orElseThrow();

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> running = executor.submit(() -> {
                    try {
                        proxy.execute(new MaintenanceContext("manual", 1L));
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

                ExternalCapabilityDrain drain = registrar.withdraw(publication).orElseThrow();
                assertThat(drain.isDrained()).isFalse();
                assertThat(drain.activeLeaseCount()).isEqualTo(1);
                assertThat(proxy.name()).isEqualTo("external-maintenance");
                assertThat(drain.activeLeaseCount()).isEqualTo(1);
                assertThatThrownBy(() -> proxy.execute(new MaintenanceContext("manual", 2L)))
                        .isInstanceOf(ExternalCapabilityUnavailableException.class);
                assertThat(drain.activeLeaseCount()).isEqualTo(1);

                release.countDown();
                running.get(5, TimeUnit.SECONDS);
                assertThat(drain.await(Duration.ofSeconds(5))).isTrue();
                registrar.retireDrained(drain);
                registrar.acknowledgeRetired(drain);
                assertThat(registrar.releaseRetirementProof(drain)).isTrue();

                assertThat(registry.tasks()).extracting(MaintenanceTask::name)
                        .containsExactly("core-maintenance");
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("根上下文与外置子上下文保留 Bean 工厂方法上的顺序")
    void factoryMethodOrderIsPreservedAcrossRootAndChildContexts() {
        try (AnnotationConfigApplicationContext root = new AnnotationConfigApplicationContext()) {
            root.register(MaintenanceTaskRegistry.class, FactoryOrderedCoreTasks.class);
            root.refresh();
            MaintenanceTaskRegistry registry = root.getBean(MaintenanceTaskRegistry.class);
            ExternalCapabilityInvocationRegistry invocationRegistry = new ExternalCapabilityInvocationRegistry();
            MaintenanceTaskCapabilityAdapter adapter =
                    new MaintenanceTaskCapabilityAdapter(registry, invocationRegistry);
            PluginCapabilityContributionRegistrar registrar = new PluginCapabilityContributionRegistrar(
                    List.of(), List.of(), List.<ExternalRuntimeCapabilityAdapter>of(adapter), invocationRegistry);

            try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
                child.setParent(root);
                child.register(FactoryOrderedExternalTask.class);
                child.refresh();
                PreparedOwner prepared = registrar.allocateOwner("external", "external", 3L);
                registrar.prepareInto(prepared, child);
                registrar.publish(prepared);

                assertThat(registry.tasks()).extracting(MaintenanceTask::name)
                        .containsExactly("core-first", "external-middle", "core-last");
            }
        }
    }

    @Test
    @DisplayName("外置任务空名称在 publication 前被拒绝且不污染核心快照")
    void blankExternalTaskNameIsRejectedBeforePublication() {
        MaintenanceTaskRegistry registry = new MaintenanceTaskRegistry(List.of(task("core-maintenance")));
        ExternalCapabilityInvocationRegistry invocationRegistry = new ExternalCapabilityInvocationRegistry();
        MaintenanceTaskCapabilityAdapter adapter =
                new MaintenanceTaskCapabilityAdapter(registry, invocationRegistry);
        PluginCapabilityContributionRegistrar registrar = new PluginCapabilityContributionRegistrar(
                List.of(), List.of(), List.<ExternalRuntimeCapabilityAdapter>of(adapter), invocationRegistry);

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.getBeanFactory().registerSingleton("invalidMaintenanceTask", new MaintenanceTask() {
                @Override
                public String name() {
                    return null;
                }

                @Override
                public void execute(MaintenanceContext context) {
                }
            });
            child.refresh();
            PreparedOwner prepared = registrar.allocateOwner("invalid", "invalid", 1L);

            assertThatThrownBy(() -> registrar.prepareInto(prepared, child))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("failureType=java.lang.IllegalArgumentException");
            assertThat(registry.tasks()).extracting(MaintenanceTask::name)
                    .containsExactly("core-maintenance");
        }
    }

    private static MaintenanceTask task(String name) {
        return new MaintenanceTask() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void execute(MaintenanceContext context) {
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    static class FactoryOrderedCoreTasks {

        @Bean
        @Order(200)
        MaintenanceTask coreLast() {
            return task("core-last");
        }

        @Bean
        @Order(100)
        MaintenanceTask coreFirst() {
            return task("core-first");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FactoryOrderedExternalTask {

        @Bean
        @Order(150)
        MaintenanceTask externalMiddle() {
            return task("external-middle");
        }
    }
}
