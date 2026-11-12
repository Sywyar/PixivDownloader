package top.sywyar.pixivdownload.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.core.download.InteractiveDownloadExecutionLane;
import top.sywyar.pixivdownload.schedule.ScheduleHostConfiguration;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("异步基础设施配置")
class AsyncConfigTest {

    @Test
    @DisplayName("默认异步调用传播当前观测且复用线程不残留上次上下文")
    void asyncCallsPropagateObservationAndCleanReusedWorker() throws IOException {
        contextRunner().run(context -> {
            var registry = context.getBean(ObservationRegistry.class);
            var probe = context.getBean(AsyncProbe.class);
            var executor = context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);
            Thread worker = probe.call(Thread::currentThread).get(5, TimeUnit.SECONDS);
            assertThat(worker).isNotSameAs(Thread.currentThread());
            for (int i = 0; i < 2; i++) {
                Observation parent = Observation.start("async.parent", registry);
                try (var scope = parent.openScope()) {
                    assertThat(probe.call(registry::getCurrentObservation).get(5, TimeUnit.SECONDS))
                            .isSameAs(parent);
                    assertThat(registry.getCurrentObservation()).isSameAs(parent);
                } finally {
                    parent.stop();
                }
                // 绕过装饰器读取复用线程，避免下一次上下文安装掩盖清理失败。
                assertThat(executor.getThreadPoolExecutor().submit(registry::getCurrentObservation)
                        .get(5, TimeUnit.SECONDS)).isNull();
                assertThat(probe.call(registry::getCurrentObservation).get(5, TimeUnit.SECONDS)).isNull();
                assertThat(probe.call(Thread::currentThread).get(5, TimeUnit.SECONDS)).isSameAs(worker);
            }
        });
    }

    @Test
    @DisplayName("嵌套观测恢复父作用域且异步异常后清理工作线程")
    void nestedScopeAndFailureRestoreObservation() throws IOException {
        contextRunner().run(context -> {
            var registry = context.getBean(ObservationRegistry.class);
            var probe = context.getBean(AsyncProbe.class);
            var executor = context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);
            var failure = new IllegalStateException("async test failure");
            Observation parent = Observation.start("async.parent", registry);
            try (var scope = parent.openScope()) {
                var result = probe.call(() -> {
                    assertThat(registry.getCurrentObservation()).isSameAs(parent);
                    Observation child = Observation.start("async.child", registry);
                    try (var childScope = child.openScope()) {
                        assertThat(registry.getCurrentObservation()).isSameAs(child);
                    } finally {
                        child.stop();
                    }
                    assertThat(registry.getCurrentObservation()).isSameAs(parent);
                    throw failure;
                });
                assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCause(failure);
                assertThat(registry.getCurrentObservation()).isSameAs(parent);
            } finally {
                parent.stop();
            }
            assertThat(executor.getThreadPoolExecutor().submit(registry::getCurrentObservation)
                    .get(5, TimeUnit.SECONDS)).isNull();
            assertThat(probe.call(registry::getCurrentObservation).get(5, TimeUnit.SECONDS)).isNull();
        });
    }

    @Test
    @DisplayName("关闭传播配置后异步调用不继承观测")
    void contextPropagationCanBeDisabled() throws IOException {
        contextRunner().withPropertyValues("spring.task.execution.propagate-context=false").run(context -> {
            var registry = context.getBean(ObservationRegistry.class);
            var probe = context.getBean(AsyncProbe.class);
            Observation parent = Observation.start("async.parent", registry);
            try (var scope = parent.openScope()) {
                assertThat(probe.call(registry::getCurrentObservation).get(5, TimeUnit.SECONDS)).isNull();
                assertThat(registry.getCurrentObservation()).isSameAs(parent);
            } finally {
                parent.stop();
            }
        });
    }

    @Test
    @DisplayName("观测传播不复制业务 MDC 或出站代理覆盖且不影响下载归档专用池")
    void propagationKeepsBusinessContextAndManualPoolsIsolated() throws IOException {
        contextRunner().run(context -> {
            var registry = context.getBean(ObservationRegistry.class);
            var probe = context.getBean(AsyncProbe.class);
            String previousMdc = MDC.get("operationVerificationId");
            assertThat(OutboundProxyOverride.isActive()).isFalse();
            Observation parent = Observation.start("async.parent", registry);
            try (var scope = parent.openScope()) {
                MDC.put("operationVerificationId", "async-test");
                OutboundProxyOverride.setDirect();
                assertThat(probe.call(() -> {
                    assertThat(MDC.get("operationVerificationId")).isNull();
                    assertThat(OutboundProxyOverride.isActive()).isFalse();
                    return registry.getCurrentObservation();
                }).get(5, TimeUnit.SECONDS)).isSameAs(parent);
                for (String name : new String[]{"downloadTaskExecutor", "archiveTaskExecutor"}) {
                    var executor = context.getBean(name, ThreadPoolTaskExecutor.class);
                    assertThat(executor.submit(registry::getCurrentObservation).get(5, TimeUnit.SECONDS)).isNull();
                }
                assertThat(MDC.get("operationVerificationId")).isEqualTo("async-test");
                assertThat(OutboundProxyOverride.isActive()).isTrue();
                assertThat(OutboundProxyOverride.current()).isNull();
            } finally {
                parent.stop();
                OutboundProxyOverride.clear();
                if (previousMdc == null) {
                    MDC.remove("operationVerificationId");
                } else {
                    MDC.put("operationVerificationId", previousMdc);
                }
            }
        });
    }

    @Test
    @DisplayName("计划编排池沿用 builder 传播观测而计划作品池保持隔离")
    void schedulePropagationFollowsExecutorOwnership() throws IOException {
        contextRunner().run(context -> {
            var registry = context.getBean(ObservationRegistry.class);
            var configuration = new ScheduleHostConfiguration();
            var runExecutor = configuration.scheduleRunTaskExecutor(
                    context.getBean(ThreadPoolTaskExecutorBuilder.class));
            var workExecutor = configuration.scheduleWorkTaskExecutor();
            runExecutor.initialize();
            workExecutor.initialize();
            Observation parent = Observation.start("schedule.parent", registry);
            try (var scope = parent.openScope()) {
                assertThat(runExecutor.submit(registry::getCurrentObservation).get(5, TimeUnit.SECONDS))
                        .isSameAs(parent);
                assertThat(workExecutor.submit(registry::getCurrentObservation).get(5, TimeUnit.SECONDS)).isNull();
            } finally {
                parent.stop();
                runExecutor.shutdown();
                workExecutor.shutdown();
            }
        });
    }

    private ApplicationContextRunner contextRunner() throws IOException {
        var properties = new ResourcePropertySource("classpath:application.properties");
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(properties))
                .withPropertyValues("spring.task.execution.pool.core-size=1", "spring.task.execution.pool.max-size=1")
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withUserConfiguration(AsyncConfig.class)
                .withBean(DownloadConfig.class)
                .withBean(MultiModeConfig.class)
                .withBean(AsyncProbe.class)
                .withBean(ObservationRegistry.class, () -> {
                    var registry = ObservationRegistry.create();
                    registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
                        @Override
                        public boolean supportsContext(Observation.Context context) {
                            return true;
                        }
                    });
                    return registry;
                });
    }

    static class AsyncProbe {
        @Async
        public <T> CompletableFuture<T> call(Callable<T> task) throws Exception {
            return CompletableFuture.completedFuture(task.call());
        }
    }

    @Test
    @DisplayName("宿主调度器取消延迟任务时应立即移除队列句柄")
    void schedulerRemovesCancelledTasks() {
        ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) new AsyncConfig().taskScheduler();
        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("交互下载通道保持任务对象并委托既有下载执行器")
    void interactiveDownloadLaneDelegatesExactTask() {
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        InteractiveDownloadExecutionLane lane =
                new AsyncConfig().interactiveDownloadExecutionLane(submitted::set);
        Runnable task = () -> {
        };

        lane.execute(task);

        assertThat(submitted).hasValue(task);
    }

    @Test
    @DisplayName("交互下载通道同步传播宿主执行器拒绝")
    void interactiveDownloadLanePropagatesRejection() {
        RejectedExecutionException rejection = new RejectedExecutionException("full");
        InteractiveDownloadExecutionLane lane =
                new AsyncConfig().interactiveDownloadExecutionLane(task -> {
                    throw rejection;
                });

        assertThatThrownBy(() -> lane.execute(() -> {
        })).isSameAs(rejection);
    }

    @Test
    @DisplayName("下载执行器应拒绝超过固定排队上限的任务")
    void downloadExecutorRejectsBeyondQueueCapacity() throws Exception {
        DownloadConfig config = new DownloadConfig();
        config.setMaxConcurrent(1);
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().downloadTaskExecutor(config);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 100; i++) {
                executor.execute(() -> {
                });
            }

            assertThatThrownBy(() -> executor.execute(() -> {
            })).isInstanceOf(TaskRejectedException.class);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

}
