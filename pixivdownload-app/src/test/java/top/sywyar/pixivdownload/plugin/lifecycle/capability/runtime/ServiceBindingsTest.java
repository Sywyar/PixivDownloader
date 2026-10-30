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
        assertThat(binding.diagnostic().code()).isEqualTo("CONSUMER_NOT_ACTIVE");
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
        assertThat(binding.diagnostic().code()).isEqualTo("AMBIGUOUS");
        assertThat(binding.diagnostic().dependencies().get(1).providers()).hasSize(2);
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
        assertThat(binding.diagnostic().code()).isEqualTo("ACTIVATION_FAILED");
        assertThat(binding.diagnostic().toString()).doesNotContain("private");
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
        assertThat(binding.diagnostic().code()).isEqualTo("CLOSE_FAILED");
        assertThat(binding.diagnostic().dependencies().get(0).providers().get(0).publicationId())
                .isEqualTo(provider.owner().publicationId());
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

    @Test @DisplayName("调度拒绝有纯值诊断，查询不重试，显式重试恢复且撤回后不串代")
    void rejectedSchedulingAndRelations() {
        var f = new Fixture(); var consumer = f.publish(f.consumer);
        var reject = new AtomicBoolean(true);
        f.registry.configureBindings(f.tasks, command -> {
            if (reject.get()) throw new RejectedExecutionException("credential-canary");
            f.work.add(command);
        });
        var binding = f.services.bindAll(List.of(KEY), scope -> {});
        var diagnostic = binding.diagnostic();
        assertThat(diagnostic.code()).isEqualTo("SCHEDULING_REJECTED");
        assertThat(diagnostic.toString()).doesNotContain("credential-canary");
        assertThat(f.work).isEmpty();
        assertThat(f.registry.serviceRelations().relations()).singleElement().satisfies(relation -> {
            assertThat(relation.owner().publicationId()).isEqualTo(consumer.owner().publicationId());
            assertThat(relation.bindings()).containsExactly(diagnostic);
        });
        assertThat(f.work).isEmpty();
        reject.set(false); binding.retry(); f.flush();
        assertThat(binding.state()).isEqualTo(ServiceBinding.State.ACTIVE);
        f.registry.withdraw(consumer); f.flush();
        assertThat(binding.diagnostic().code()).isEqualTo("CLOSED");
        assertThat(binding.diagnostic().consumer()).isEqualTo(diagnostic.consumer());
        assertThat(f.registry.serviceRelations().relations()).allSatisfy(relation -> assertThat(relation.bindings()).isEmpty());
    }

    @Test @DisplayName("活动绑定调度拒绝后恢复正常诊断且不重建有效轮次")
    void activeRoundRecoversSchedulingDiagnostic() {
        var f = new Fixture();
        f.publish(f.consumer);
        f.provider("provider", 1, KEY);
        var activations = new AtomicInteger();
        var proxy = new AtomicReference<Echo>();
        var binding = f.services.bind(List.of(KEY), scope -> {
            activations.incrementAndGet();
            proxy.set(scope.require(KEY));
        });
        f.flush();
        var original = proxy.get();
        var reject = new AtomicBoolean(true);
        f.registry.configureBindings(f.tasks, command -> {
            if (reject.get()) throw new RejectedExecutionException();
            f.work.add(command);
        });
        f.provider("unrelated", 1, SECOND);
        assertThat(binding.diagnostic().code()).isEqualTo("SCHEDULING_REJECTED");
        reject.set(false);
        f.registry.serviceRelations();
        assertThat(binding.diagnostic().code()).isEqualTo("SCHEDULING_REJECTED");
        assertThat(f.work).isEmpty();
        binding.retry();
        f.flush();
        assertThat(binding.diagnostic().code()).isEqualTo("ACTIVE");
        assertThat(binding.state()).isEqualTo(ServiceBinding.State.ACTIVE);
        assertThat(activations).hasValue(1);
        assertThat(proxy.get()).isSameAs(original);
        assertThat(original.echo("ready")).isEqualTo("provider:ready");
    }

    @Test @DisplayName("集合绑定接受零到多个实现，第三实现变化不重放命令，旧成员不转投新实例")
    void collectionMembershipAndExactPublication() {
        var f = new Fixture();
        var consumer = f.publish(f.consumer);
        var scopes = new ArrayList<ServiceBindingScope>();
        var closes = new AtomicInteger();
        var binding = f.services.bindAll(List.of(KEY), scope -> {
            scopes.add(scope);
            scope.acquire(() -> closes::incrementAndGet);
        });
        f.flush();
        assertThat(binding.state()).isEqualTo(ServiceBinding.State.ACTIVE);
        assertThat(scopes.get(0).findAll(KEY)).isEmpty();
        assertThat(f.services.findAll(KEY)).isEmpty();
        var first = f.provider("one", 1, KEY); f.flush();
        var old = scopes.get(1).findAll(KEY).get(0);
        assertThat(old.publication().publicationId()).isEqualTo(first.owner().publicationId());
        assertThat(old.service().echo("a")).isEqualTo("one:a");
        f.provider("two", 1, KEY); f.flush();
        assertUnavailable(() -> old.service().echo("stale-round"));
        f.provider("unknown-third", 1, KEY); f.flush();
        assertThat(scopes.get(3).findAll(KEY)).hasSize(3);
        assertThat(f.services.findAll(KEY)).extracting(p -> p.service().echo("b"))
                .containsExactly("one:b", "two:b", "unknown-third:b");
        assertThatThrownBy(() -> f.services.require(KEY)).hasMessage("AMBIGUOUS");
        assertThatThrownBy(() -> scopes.get(3).require(KEY)).hasMessage("AMBIGUOUS");
        var snapshot = f.services.findAll(KEY);
        var drain = f.registry.withdraw(first).orElseThrow();
        assertThat(drain.isDrained()).isFalse();
        f.flush();
        assertThat(drain.isDrained()).isTrue();
        f.registry.retireDrained(drain); f.registry.acknowledgeRetired(drain);
        f.provider("one", 2, KEY); f.flush();
        assertUnavailable(() -> snapshot.get(0).service().echo("replacement"));
        assertThat(scopes.get(scopes.size() - 1).findAll(KEY)).hasSize(3);
        f.registry.withdraw(consumer); f.flush();
        assertThat(binding.state()).isEqualTo(ServiceBinding.State.CLOSED);
        assertThat(closes).hasValue(scopes.size());
        int previous = scopes.size();
        f.provider("later", 1, KEY); f.flush();
        assertThat(scopes).hasSize(previous);
    }

    @Test @DisplayName("诊断截断明确可见，管理投影有累计预算，完整操作影响不漏掉被截断的提供者")
    void boundedDiagnosticsDoNotTruncateImpact() throws Exception {
        var f = new Fixture(); f.publish(f.consumer);
        for (int i = 0; i < 129; i++) f.provider("provider-" + i + "-" + "x".repeat(70), 1, KEY);
        var bindings = new ArrayList<ServiceBinding>();
        for (int i = 0; i < 128; i++) bindings.add(f.services.bind(List.of(KEY), scope -> fail("ambiguous selection")));
        f.flush();
        var diagnostic = bindings.get(0).diagnostic();
        assertThat(diagnostic.code()).isEqualTo("AMBIGUOUS");
        assertThat(diagnostic.truncated()).isTrue();
        assertThat(diagnostic.dependencies()).singleElement().satisfies(dependency -> {
            assertThat(dependency.truncated()).isTrue();
            assertThat(dependency.providers()).hasSizeLessThan(129);
        });
        var report = f.registry.serviceRelations();
        assertThat(report.truncated()).isTrue();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(report).length).isLessThan(4 * 1024 * 1024);
        assertThat(f.registry.serviceImpact("provider-128-" + "x".repeat(70)).consumers()).containsExactly("consumer");
        assertThat(f.work).isEmpty();
        assertThat(bindings).allSatisfy(binding -> assertThat(binding.state()).isEqualTo(ServiceBinding.State.AMBIGUOUS));
    }

    @Test @DisplayName("集合中的普通失败由消费者隔离，其它事件接收者继续接收一次")
    void collectionFailureIsolation() {
        var f = new Fixture(); f.publish(f.consumer);
        var calls = new AtomicInteger();
        var bad = f.prepare("failing-sink", 1);
        f.registry.prepareService(bad, KEY, value -> { throw new IllegalArgumentException("private-payload"); });
        f.publish(bad);
        for (String id : List.of("first-sink", "second-sink")) {
            var p = f.prepare(id, 1);
            f.registry.prepareService(p, KEY, value -> { calls.incrementAndGet(); return value; });
            f.publish(p);
        }
        var failures = new ArrayList<ServiceAccessException.Reason>();
        for (var member : f.services.findAll(KEY)) {
            try { member.service().echo("event"); }
            catch (ServiceAccessException failure) { failures.add(failure.reason()); }
        }
        assertThat(calls).hasValue(2);
        assertThat(failures).containsExactly(ServiceAccessException.Reason.INVOCATION_FAILED);
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean done = false;
        while (!done) try { latch.await(); done = true; } catch (InterruptedException ignored) { }
    }
}
