package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.api.resource.*;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleState;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("依赖绑定按精确 publication 撤回、排空和重建")
class ServiceBindingsTest {
    public interface Echo { String echo(String input); }
    private static final ServiceKey<Echo> KEY = new ServiceKey<>("binding-contract", Echo.class);
    private static final ServiceKey<Echo> SECOND = new ServiceKey<>("second-contract", Echo.class);

    private static final class Fixture {
        final PluginLifecycleState admission = new PluginLifecycleState();
        final PluginRuntimeTaskRegistry tasks = new PluginRuntimeTaskRegistry();
        final Queue<Runnable> work = new ConcurrentLinkedQueue<>();
        final ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry(admission);
        final ExternalCapabilityPreparation consumer;
        final PluginServices services;
        Fixture() {
            registry.configureBindings(tasks, work::add);
            consumer = prepare("consumer", 1);
            services = registry.servicesFor(consumer, List.of(KEY, SECOND));
        }
        ExternalCapabilityPreparation prepare(String id, long generation) {
            var p = registry.allocatePreparation(id, id, generation);
            registry.installPreparation(p);
            return p;
        }
        ExternalCapabilityPublication publish(ExternalCapabilityPreparation p) {
            var publication = registry.publish(p);
            admission.set(publication.owner().pluginId(), PluginRuntimePhase.STARTED);
            registry.serviceBindingsChanged();
            return publication;
        }
        ExternalCapabilityPublication provider(String id, long generation, ServiceKey<Echo> key) {
            var p = prepare(id, generation);
            registry.prepareService(p, key, value -> id + ":" + value);
            return publish(p);
        }
        void flush() { for (Runnable r; (r = work.poll()) != null;) r.run(); }
    }

    @Test @DisplayName("准备期只登记意图，缺席恢复及同代重启均获得新代理")
    void publicationAndReplacement() {
        var f = new Fixture();
        var scopes = new ArrayList<ServiceBindingScope>();
        var proxies = new ArrayList<Echo>();
        var closes = new AtomicInteger();
        var binding = f.services.bind(List.of(KEY), scope -> {
            scopes.add(scope); proxies.add(scope.require(KEY));
            scope.acquire(() -> closes::incrementAndGet);
        });
        var first = f.provider("provider", 1, KEY);
        f.flush();
        assertThat(binding.state()).isEqualTo(ServiceBinding.State.WAITING);
        assertThat(scopes).isEmpty();
        var consumer = f.publish(f.consumer); f.flush();
        assertThat(binding.state()).isEqualTo(ServiceBinding.State.ACTIVE);
        assertThat(proxies.get(0).echo("值😀")).isEqualTo("provider:值😀");
        var drain = f.registry.withdraw(first).orElseThrow();
        assertThat(drain.isDrained()).isFalse();
        assertUnavailable(() -> proxies.get(0).echo("old"));
        f.flush();
        assertThat(drain.isDrained()).isTrue();
        assertThat(closes).hasValue(1);
        assertThat(binding.state()).isEqualTo(ServiceBinding.State.WAITING);
        f.registry.retireDrained(drain); f.registry.acknowledgeRetired(drain);
        var second = f.provider("provider", 1, KEY); f.flush();
        assertThat(proxies).hasSize(2);
        assertUnavailable(() -> proxies.get(0).echo("old"));
        var secondDrain = f.registry.withdraw(second).orElseThrow(); f.flush();
        f.registry.retireDrained(secondDrain); f.registry.acknowledgeRetired(secondDrain);
        f.provider("provider", 2, KEY); f.flush();
        assertThat(proxies).hasSize(3);
        f.registry.withdraw(consumer); f.flush();
        assertThat(binding.state()).isEqualTo(ServiceBinding.State.CLOSED);
        assertThat(closes).hasValue(3);
        f.provider("other", 3, KEY); f.flush();
        assertThat(proxies).hasSize(3);
    }

    @Test @DisplayName("所有依赖齐备且唯一才激活，歧义撤回后恢复")
    void allDependenciesAndAmbiguity() {
        var f = new Fixture(); f.publish(f.consumer);
        var calls = new AtomicInteger();
        var binding = f.services.bind(List.of(KEY, SECOND), scope -> calls.incrementAndGet());
        f.provider("one", 1, KEY); f.flush();
        assertThat(binding.state()).isEqualTo(ServiceBinding.State.WAITING);
        f.provider("two", 1, SECOND); f.flush();
        assertThat(calls).hasValue(1);
        var duplicate = f.provider("duplicate", 1, SECOND); f.flush();
        assertThat(binding.state()).isEqualTo(ServiceBinding.State.AMBIGUOUS);
        f.registry.withdraw(duplicate); f.flush();
        assertThat(calls).hasValue(2);
    }

    @Test @DisplayName("激活失败回滚部分资源，显式重试不重放已有业务")
    void rollbackAndExplicitRetry() {
        var f = new Fixture(); f.publish(f.consumer); f.provider("provider", 1, KEY);
        var attempts = new AtomicInteger(); var closes = new AtomicInteger();
        var binding = f.services.bind(List.of(KEY), scope -> {
            scope.acquire(() -> closes::incrementAndGet);
            if (attempts.incrementAndGet() == 1) throw new IllegalArgumentException("private");
        });
        f.flush();
        assertThat(binding.state()).isEqualTo(ServiceBinding.State.FAILED);
        assertThat(closes).hasValue(1);
        f.registry.serviceBindingsChanged(); f.flush();
        assertThat(attempts).hasValue(1);
        binding.retry(); f.flush();
        assertThat(binding.state()).isEqualTo(ServiceBinding.State.ACTIVE);
        assertThat(attempts).hasValue(2);
        binding.close(); f.flush();
        assertThat(closes).hasValue(2);
    }

    @Test @DisplayName("清理失败保留租约，重试成功前禁止替代订阅")
    void cleanupFailureRetainsLease() {
        var f = new Fixture(); f.publish(f.consumer); var provider = f.provider("provider", 1, KEY);
        var attempts = new AtomicInteger(); var activated = new AtomicInteger();
        var binding = f.services.bind(List.of(KEY), scope -> {
            activated.incrementAndGet();
            scope.acquire(() -> () -> { if (attempts.incrementAndGet() == 1) throw new Exception("secret"); });
        }); f.flush();
        var drain = f.registry.withdraw(provider).orElseThrow();
        f.provider("replacement", 2, KEY); f.flush();
        assertThat(binding.state()).isEqualTo(ServiceBinding.State.CLOSE_FAILED);
        assertThat(drain.isDrained()).isFalse(); assertThat(activated).hasValue(1);
        binding.retry(); f.flush();
        assertThat(drain.isDrained()).isTrue(); assertThat(activated).hasValue(2);
    }

    @Test @DisplayName("忽略中断的回调真实退出前保留资源，迟到回调跳过")
    void callbackDrainAndLateNotification() throws Exception {
        var f = new Fixture(); f.publish(f.consumer); var provider = f.provider("provider", 1, KEY);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var callback = new AtomicReference<Runnable>(); var closes = new AtomicInteger();
        var binding = f.services.bind(List.of(KEY), scope -> {
            scope.acquire(() -> closes::incrementAndGet);
            callback.set(scope.guard(() -> { entered.countDown(); awaitIgnoringInterrupt(release); }));
        }); f.flush();
        Runnable old = callback.get(); var thread = new Thread(old); thread.setDaemon(true); thread.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            thread.interrupt();
            var drain = f.registry.withdraw(provider).orElseThrow(); f.flush();
            var taskDrain = f.tasks.prepareQuiesce("consumer");
            assertThat(taskDrain.isDrained()).isFalse();
            assertThat(binding.state()).isEqualTo(ServiceBinding.State.DRAINING);
            assertThat(closes).hasValue(0); assertThat(drain.isDrained()).isFalse();
            old.run();
            release.countDown(); thread.join(5000); f.flush();
            assertThat(thread.isAlive()).isFalse();
            assertThat(drain.isDrained()).isTrue(); assertThat(taskDrain.isDrained()).isTrue();
            assertThat(closes).hasValue(1);
        } finally { release.countDown(); thread.join(5000); }
    }

    @Test @DisplayName("依赖声明和绑定数量有界，重复通知合并且停用清除待激活意图")
    void boundsAndWaitingConsumerStop() {
        var f = new Fixture(); var publication = f.publish(f.consumer);
        assertThatThrownBy(() -> f.services.bind(List.of(), scope -> {})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.services.bind(List.of(KEY, KEY), scope -> {})).isInstanceOf(IllegalArgumentException.class);
        var other = new ServiceKey<>("undeclared-contract", Echo.class);
        assertThatThrownBy(() -> f.services.bind(List.of(other), scope -> {})).hasMessage("UNDECLARED");
        var bindings = new ArrayList<ServiceBinding>();
        for (int i = 0; i < 128; i++) bindings.add(f.services.bind(List.of(KEY), scope -> fail("must not activate")));
        assertThatThrownBy(() -> f.services.bind(List.of(KEY), scope -> {})).hasMessage("CAPACITY");
        for (int i = 0; i < 1000; i++) f.registry.serviceBindingsChanged();
        assertThat(f.work).hasSize(128);
        f.registry.withdraw(publication); f.flush();
        assertThat(bindings).allMatch(binding -> binding.state() == ServiceBinding.State.CLOSED);
        f.provider("provider", 1, KEY); f.flush();
    }

    @Test @DisplayName("业务回调返回但任务包装器未退出时继续排空，退出后自动建立替代绑定")
    void wrapperExitBeforeResourceClose() throws Exception {
        var f = new Fixture(); f.publish(f.consumer); var provider = f.provider("provider", 1, KEY);
        var tail = new CountDownLatch(1); var release = new CountDownLatch(1); var pause = new AtomicBoolean();
        f.registry.configureBindings(new PluginRuntimeTaskRegistry() {
            @Override public PluginResources resourcesForPlugin(String id) {
                return () -> {
                    var delegate = f.tasks.resourcesForPlugin(id).openScope();
                    return new PluginResourceScope() {
                        @Override public <T extends AutoCloseable> T acquire(java.util.function.Supplier<T> factory) {
                            return delegate.acquire(factory);
                        }
                        @Override public Runnable guard(Runnable callback) {
                            return delegate.guard(() -> {
                                callback.run();
                                if (pause.compareAndSet(true, false)) { tail.countDown(); awaitIgnoringInterrupt(release); }
                            });
                        }
                        @Override public void close() { delegate.close(); }
                    };
                };
            }
        }, f.work::add);
        var callback = new AtomicReference<Runnable>(); var closes = new AtomicInteger(); var activations = new AtomicInteger();
        var binding = f.services.bind(List.of(KEY), scope -> {
            activations.incrementAndGet(); scope.acquire(() -> closes::incrementAndGet);
            callback.set(scope.guard(() -> {}));
        }); f.flush();
        pause.set(true);
        var thread = new Thread(callback.get()); thread.setDaemon(true); thread.start();
        try {
            assertThat(tail.await(5, TimeUnit.SECONDS)).isTrue();
            var drain = f.registry.withdraw(provider).orElseThrow(); f.provider("replacement", 2, KEY); f.flush();
            assertThat(binding.state()).isEqualTo(ServiceBinding.State.DRAINING);
            assertThat(drain.isDrained()).isFalse(); assertThat(closes).hasValue(0); assertThat(activations).hasValue(1);
            release.countDown(); thread.join(5000); f.flush();
            assertThat(thread.isAlive()).isFalse(); assertThat(binding.state()).isEqualTo(ServiceBinding.State.ACTIVE);
            assertThat(drain.isDrained()).isTrue(); assertThat(closes).hasValue(1); assertThat(activations).hasValue(2);
        } finally { release.countDown(); thread.join(5000); }
    }

    @Test @DisplayName("绑定服务调用忽略取消时先排空旧轮，替代绑定不能提前订阅")
    void serviceCallDrainBeforeReplacement() throws Exception {
        var f = new Fixture(); f.publish(f.consumer);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var p = f.prepare("provider", 1);
        f.registry.prepareService(p, KEY, input -> {
            entered.countDown(); awaitIgnoringInterrupt(release); return input;
        });
        var publication = f.publish(p);
        var proxy = new AtomicReference<Echo>(); var closes = new AtomicInteger(); var activations = new AtomicInteger();
        var binding = f.services.bind(List.of(KEY), scope -> {
            activations.incrementAndGet(); proxy.set(scope.require(KEY));
            scope.acquire(() -> closes::incrementAndGet);
        }); f.flush();
        Echo old = proxy.get(); var result = new AtomicReference<String>();
        var thread = new Thread(() -> result.set(old.echo("entered"))); thread.setDaemon(true); thread.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            thread.interrupt();
            var drain = f.registry.withdraw(publication).orElseThrow();
            f.provider("replacement", 2, KEY); f.flush();
            assertThat(binding.state()).isEqualTo(ServiceBinding.State.DRAINING);
            assertThat(drain.isDrained()).isFalse(); assertThat(closes).hasValue(0); assertThat(activations).hasValue(1);
            assertUnavailable(() -> old.echo("late"));
            release.countDown(); thread.join(5000); f.flush();
            assertThat(thread.isAlive()).isFalse(); assertThat(result).hasValue("entered");
            assertThat(drain.isDrained()).isTrue(); assertThat(closes).hasValue(1); assertThat(activations).hasValue(2);
            assertThat(proxy.get().echo("new")).isEqualTo("replacement:new");
            assertUnavailable(() -> old.echo("late"));
        } finally { release.countDown(); thread.join(5000); }
    }

    @Test @DisplayName("激活期间消费者撤回，部分资源回滚且其它绑定可独立推进")
    void consumerWithdrawDuringActivation() throws Exception {
        var f = new Fixture(); var consumer = f.publish(f.consumer); f.provider("provider", 1, KEY);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var closes = new AtomicInteger();
        var binding = f.services.bind(List.of(KEY), scope -> {
            scope.acquire(() -> closes::incrementAndGet);
            entered.countDown(); awaitIgnoringInterrupt(release);
        });
        var thread = new Thread(f.work.remove()); thread.setDaemon(true); thread.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = f.services.bind(List.of(KEY), scope -> {}); f.flush();
            assertThat(second.state()).isEqualTo(ServiceBinding.State.ACTIVE);
            var drain = f.registry.withdraw(consumer).orElseThrow(); f.flush();
            assertThat(second.state()).isEqualTo(ServiceBinding.State.CLOSED);
            assertThat(drain.isDrained()).isFalse(); assertThat(closes).hasValue(0);
            release.countDown(); thread.join(5000); f.flush();
            assertThat(thread.isAlive()).isFalse(); assertThat(drain.isDrained()).isTrue();
            assertThat(binding.state()).isEqualTo(ServiceBinding.State.CLOSED); assertThat(closes).hasValue(1);
        } finally { release.countDown(); thread.join(5000); }
    }

    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ServiceAccessException.class).hasMessage("UNAVAILABLE");
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean done = false;
        while (!done) try { latch.await(); done = true; } catch (InterruptedException ignored) { }
    }
}
