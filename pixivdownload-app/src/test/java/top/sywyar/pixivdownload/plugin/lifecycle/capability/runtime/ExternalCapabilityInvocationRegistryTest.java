package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryProjectionKey;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjection;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryActor;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryTag;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.ExternalRuntimeCapabilityAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("外置运行时能力调用边界")
class ExternalCapabilityInvocationRegistryTest {

    @Test
    @DisplayName("取得调用租约后的 OOME 与 ThreadDeath 会清理计数及活动 owner 作用域")
    void fatalAfterAcquireCleansLeaseAndOwnerScope() throws Exception {
        for (Error expected : new Error[]{new OutOfMemoryError("invoke-fatal"), new ThreadDeath()}) {
            ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry(
                    () -> {
                    }, () -> {
                        throw expected;
                    });
            ExternalCapabilityPreparation preparation = prepareOwner(registry, "demo", "demo", 1L);
            Echo proxy = registry.prepareProxy(preparation, Echo.class, () -> "unused");
            ExternalCapabilityPublication publication = registry.publish(preparation);

            assertThat(catchThrowable(proxy::call)).isSameAs(expected);

            Field scopes = ExternalCapabilityInvocationRegistry.class.getDeclaredField("activeOwners");
            scopes.setAccessible(true);
            assertThat(((ThreadLocal<?>) scopes.get(registry)).get()).isNull();
            ExternalCapabilityDrain drain = registry.withdraw(publication).orElseThrow();
            assertThat(drain.activeLeaseCount()).isZero();
            assertThat(drain.isDrained()).isTrue();
            registry.retireDrained(drain);
        }
    }

    @Test
    @DisplayName("撤回 admission 发布后的普通与致命错误可重试并返回同一精确 drain")
    void withdrawalHandoffFailuresRetryTheExactDrain() {
        for (Throwable expected : List.of(
                new IllegalStateException("withdraw-ordinary"),
                new OutOfMemoryError("withdraw-oome"),
                new ThreadDeath())) {
            AtomicReference<Throwable> nextFailure = new AtomicReference<>(expected);
            ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry(
                    () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> throwUnchecked(nextFailure.getAndSet(null)), () -> {
                    });
            ExternalCapabilityPreparation preparation = prepareOwner(registry, "demo", "demo", 1L);
            Echo proxy = registry.prepareProxy(preparation, Echo.class, () -> "unused");
            ExternalCapabilityPublication publication = registry.publish(preparation);

            assertThat(catchThrowable(() -> registry.withdraw(publication))).isSameAs(expected);
            assertThatThrownBy(proxy::call).isInstanceOf(ExternalCapabilityUnavailableException.class);

            ExternalCapabilityDrain drain = registry.withdraw(publication).orElseThrow();
            assertThat(registry.withdraw(publication)).containsSame(drain);
            assertThat(registry.withdrawPublished(preparation)).isSameAs(drain);
            assertThat(drain.activeLeaseCount()).isZero();
            assertThat(drain.isDrained()).isTrue();
            registry.retireDrained(drain);
            assertThat(registry.acknowledgeRetired(drain)).isTrue();
        }
    }

    @Test
    @DisplayName("retire 返回前的普通与致命错误保留 host tombstone 供精确重试")
    void retirementHandoffFailuresRetryTheExactDrain() {
        for (Throwable expected : List.of(
                new IllegalStateException("retire-ordinary"),
                new OutOfMemoryError("retire-oome"),
                new ThreadDeath())) {
            AtomicReference<Throwable> nextFailure = new AtomicReference<>(expected);
            ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry(
                    () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> throwUnchecked(nextFailure.getAndSet(null)));
            ExternalCapabilityPreparation preparation = prepareOwner(registry, "demo", "demo", 1L);
            registry.prepareProxy(preparation, Echo.class, () -> "unused");
            ExternalCapabilityPublication publication = registry.publish(preparation);
            ExternalCapabilityDrain drain = registry.withdraw(publication).orElseThrow();

            assertThat(catchThrowable(() -> registry.retireDrained(drain))).isSameAs(expected);
            registry.retireDrained(drain);
            assertThat(registry.withdraw(publication)).containsSame(drain);

            ExternalCapabilityPreparation replacement = prepareOwner(registry, "demo", "demo", 2L);
            assertThat(replacement.owner()).isNotEqualTo(preparation.owner());
            assertThat(registry.discardUnpublished(replacement)).isTrue();
            assertThat(registry.acknowledgeRetired(drain)).isTrue();
            assertThat(registry.forgetRetirementAcknowledgement(drain)).isTrue();
        }
    }

    @Test
    @DisplayName("ack 移除 retired tombstone 后的普通与致命错误可由轻量证明精确重试")
    void acknowledgementHandoffFailuresRetryTheExactDrain() {
        for (Throwable expected : List.of(
                new IllegalStateException("ack-ordinary"),
                new OutOfMemoryError("ack-oome"),
                new ThreadDeath())) {
            AtomicReference<Throwable> nextFailure = new AtomicReference<>(expected);
            ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry(
                    () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> {
                    }, () -> throwUnchecked(nextFailure.getAndSet(null)));
            ExternalCapabilityPreparation preparation = prepareOwner(registry, "demo", "demo", 1L);
            registry.prepareProxy(preparation, Echo.class, () -> "unused");
            ExternalCapabilityPublication publication = registry.publish(preparation);
            ExternalCapabilityDrain drain = registry.withdraw(publication).orElseThrow();
            registry.retireDrained(drain);

            assertThat(catchThrowable(() -> registry.acknowledgeRetired(drain))).isSameAs(expected);
            assertThat(registry.acknowledgeRetired(drain)).isTrue();
            assertThat(registry.forgetRetirementAcknowledgement(drain)).isTrue();
            assertThat(registry.forgetRetirementAcknowledgement(drain)).isFalse();
        }
    }

    @Test
    @DisplayName("中央 admission 已打开后失败仍撤回代理并释放精确 owner")
    void postPublishFailureIsCompensatedWithoutOrphanedTargets() {
        List<Throwable> failures = List.of(
                new IllegalStateException("ordinary"),
                new OutOfMemoryError("vm-fatal"),
                new ThreadDeath());
        for (Throwable expected : failures) {
            ExternalCapabilityInvocationRegistry registry =
                    new ExternalCapabilityInvocationRegistry(() -> throwUnchecked(expected));
            AtomicReference<Echo> proxy = new AtomicReference<>();
            ExternalRuntimeCapabilityAdapter adapter = new ExternalRuntimeCapabilityAdapter() {
                @Override
                public String capabilityName() {
                    return "echo";
                }

                @Override
                public PreparedContribution prepare(
                        ExternalCapabilityPreparation preparation,
                        ConfigurableApplicationContext context) {
                    proxy.set(registry.prepareProxy(preparation, Echo.class, () -> "ok"));
                    return preparation::owner;
                }

                @Override
                public void publish(PreparedContribution contribution) {
                }

                @Override
                public void withdraw(ExternalCapabilityOwner owner) {
                }
            };
            PluginCapabilityContributionRegistrar registrar = new PluginCapabilityContributionRegistrar(
                    List.of(), List.of(), List.of(adapter), registry);
            Throwable observed = null;
            try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
                child.refresh();
                PluginCapabilityContributionRegistrar.PreparedOwner prepared =
                        prepareOwner(registrar, "demo", "demo", 1L, child);
                try {
                    registrar.publish(prepared);
                } catch (Throwable failure) {
                    observed = failure;
                }
            }

            if (expected instanceof VirtualMachineError || expected instanceof ThreadDeath) {
                assertThat(observed).isSameAs(expected);
            } else {
                assertThat(observed).isInstanceOf(IllegalStateException.class);
            }
            assertThatThrownBy(proxy.get()::call)
                    .isInstanceOf(ExternalCapabilityUnavailableException.class);
            ExternalCapabilityPreparation replacement = prepareOwner(registry, "demo", "demo", 2L);
            assertThat(registry.discardUnpublished(replacement)).isTrue();
        }
    }

    @Test
    @DisplayName("撤回原子拒绝新调用并等待已取得租约的阻塞调用归零")
    void withdrawalDrainsBlockingInvocation() throws Exception {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation preparation = prepareOwner(registry, "demo", "demo", 7L);
        BlockingEcho target = new BlockingEcho();
        Echo proxy = registry.prepareProxy(preparation, Echo.class, target);

        assertThatThrownBy(proxy::call).isInstanceOf(ExternalCapabilityUnavailableException.class);
        ExternalCapabilityPublication publication = registry.publish(preparation);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                result.set(proxy.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "capability-blocking-call");
        caller.start();
        assertThat(target.entered.await(5, TimeUnit.SECONDS)).isTrue();

        ExternalCapabilityDrain drain = registry.withdraw(publication).orElseThrow();
        assertThat(drain.isDrained()).isFalse();
        assertThatThrownBy(proxy::call).isInstanceOf(ExternalCapabilityUnavailableException.class);

        target.release.countDown();
        caller.join(5000L);
        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(result.get()).isEqualTo("done");
        assertThat(drain.await(Duration.ofSeconds(1))).isTrue();
        registry.retireDrained(drain);
    }

    @Test
    @DisplayName("替换后旧代理永久失效且中央状态不再持有旧 target")
    void staleProxyCannotCallOrPinRetiredTarget() throws Exception {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation firstPreparation = prepareOwner(registry, "demo", "demo", 3L);
        Echo first = registry.prepareProxy(firstPreparation, Echo.class, () -> "first");
        ExternalCapabilityPublication firstPublication = registry.publish(firstPreparation);
        assertThat(first.call()).isEqualTo("first");
        ExternalCapabilityDrain firstDrain = registry.withdraw(firstPublication).orElseThrow();
        registry.retireDrained(firstDrain);

        ExternalCapabilityPreparation secondPreparation = prepareOwner(registry, "demo", "demo", 3L);
        Echo second = registry.prepareProxy(secondPreparation, Echo.class, () -> "second");
        ExternalCapabilityPublication secondPublication = registry.publish(secondPreparation);

        assertThatThrownBy(first::call).isInstanceOf(ExternalCapabilityUnavailableException.class);
        assertThat(second.call()).isEqualTo("second");
        Field states = ExternalCapabilityInvocationRegistry.class.getDeclaredField("states");
        states.setAccessible(true);
        assertThat(((Map<?, ?>) states.get(registry))).hasSize(1);

        ExternalCapabilityDrain secondDrain = registry.withdraw(secondPublication).orElseThrow();
        registry.retireDrained(secondDrain);
    }

    @Test
    @DisplayName("JDK 代理与 handler 只驻留父加载器且 handler 无 target 或 classloader 字段")
    void proxyAndHandlerAreTargetFreeAndParentLoaded() {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation preparation = prepareOwner(registry, "demo", "demo", 1L);
        Echo proxy = registry.prepareProxy(preparation, Echo.class, () -> "ok");

        assertThat(Proxy.isProxyClass(proxy.getClass())).isTrue();
        assertThat(proxy.getClass().getClassLoader()).isSameAs(Echo.class.getClassLoader());
        Object handler = Proxy.getInvocationHandler(proxy);
        assertThat(handler.getClass().getClassLoader())
                .isSameAs(ExternalCapabilityInvocationRegistry.class.getClassLoader());
        List<Field> instanceFields = Stream.of(handler.getClass().getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .toList();
        assertThat(instanceFields).extracting(Field::getType)
                .containsExactlyInAnyOrder(
                        ExternalCapabilityHandle.class,
                        ExternalCapabilityInvocationRegistry.class);
        assertThat(instanceFields).noneMatch(field -> field.getType() == ClassLoader.class
                || field.getName().toLowerCase().contains("target"));

        registry.discardUnpublished(preparation);
    }

    @Test
    @DisplayName("同 exact owner 的嵌套同步调用在撤回 admission 后复用外层租约")
    void sameOwnerNestedCallReusesActiveLeaseAcrossWithdrawal() throws Exception {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation preparation = prepareOwner(registry, "push", "push", 9L);
        Echo inner = registry.prepareProxy(preparation, Echo.class, () -> "nested-ok");
        NestedTarget target = new NestedTarget(inner);
        Echo outer = registry.prepareProxy(preparation, Echo.class, target);
        ExternalCapabilityPublication publication = registry.publish(preparation);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                result.set(outer.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "same-owner-nested-call");
        caller.start();
        assertThat(target.entered.await(5, TimeUnit.SECONDS)).isTrue();

        ExternalCapabilityDrain drain = registry.withdraw(publication).orElseThrow();
        target.proceed.countDown();
        caller.join(5000L);

        assertThat(failure.get()).isNull();
        assertThat(result.get()).isEqualTo("nested-ok");
        assertThat(drain.await(Duration.ofSeconds(1))).isTrue();
        registry.retireDrained(drain);
    }

    @Test
    @DisplayName("跨 owner 嵌套调用独立 admission 且目标 owner 撤回后失败关闭")
    void crossOwnerNestedCallRequiresSeparateAdmission() throws Exception {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation secondPreparation = prepareOwner(registry, "second", "second", 1L);
        Echo second = registry.prepareProxy(secondPreparation, Echo.class, () -> "should-not-run");
        ExternalCapabilityPublication secondPublication = registry.publish(secondPreparation);

        ExternalCapabilityPreparation firstPreparation = prepareOwner(registry, "first", "first", 1L);
        CrossOwnerTarget firstTarget = new CrossOwnerTarget(second);
        Echo first = registry.prepareProxy(firstPreparation, Echo.class, firstTarget);
        ExternalCapabilityPublication firstPublication = registry.publish(firstPreparation);
        AtomicReference<String> result = new AtomicReference<>();
        Thread caller = new Thread(() -> result.set(first.call()), "cross-owner-nested-call");
        caller.start();
        assertThat(firstTarget.entered.await(5, TimeUnit.SECONDS)).isTrue();

        ExternalCapabilityDrain secondDrain = registry.withdraw(secondPublication).orElseThrow();
        firstTarget.proceed.countDown();
        caller.join(5000L);

        assertThat(result.get()).isEqualTo("separate-admission-rejected");
        registry.retireDrained(secondDrain);
        ExternalCapabilityDrain firstDrain = registry.withdraw(firstPublication).orElseThrow();
        registry.retireDrained(firstDrain);
    }

    @Test
    @DisplayName("跨 owner 嵌套调用在双方活动时分别取得租约且撤回外层不影响内层 owner")
    void crossOwnerNestedCallAcquiresIndependentLease() {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation secondPreparation = prepareOwner(registry, "second", "second", 1L);
        Echo second = registry.prepareProxy(secondPreparation, Echo.class, () -> "second-ok");
        ExternalCapabilityPublication secondPublication = registry.publish(secondPreparation);
        ExternalCapabilityPreparation firstPreparation = prepareOwner(registry, "first", "first", 1L);
        Echo first = registry.prepareProxy(firstPreparation, Echo.class, second::call);
        ExternalCapabilityPublication firstPublication = registry.publish(firstPreparation);

        assertThat(first.call()).isEqualTo("second-ok");
        ExternalCapabilityDrain firstDrain = registry.withdraw(firstPublication).orElseThrow();
        registry.retireDrained(firstDrain);
        assertThat(second.call()).isEqualTo("second-ok");

        ExternalCapabilityDrain secondDrain = registry.withdraw(secondPublication).orElseThrow();
        registry.retireDrained(secondDrain);
    }

    @Test
    @DisplayName("未发布批次部分准备后丢弃会同时清除全部 raw target")
    void partialPreparationCanBeDiscardedWithoutResidualTargets() throws Exception {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation preparation = prepareOwner(registry, "demo", "demo", 2L);
        Echo first = registry.prepareProxy(preparation, Echo.class, () -> "first");
        Echo second = registry.prepareProxy(preparation, Echo.class, () -> "second");

        assertThat(registry.discardUnpublished(preparation)).isTrue();
        assertThatThrownBy(first::call).isInstanceOf(ExternalCapabilityUnavailableException.class);
        assertThatThrownBy(second::call).isInstanceOf(ExternalCapabilityUnavailableException.class);
        Field states = ExternalCapabilityInvocationRegistry.class.getDeclaredField("states");
        states.setAccessible(true);
        assertThat((Map<?, ?>) states.get(registry)).isEmpty();
    }

    @Test
    @DisplayName("VM fatal 与 ThreadDeath 保持原对象身份且租约仍完成清理")
    void fatalIdentityIsPreservedAfterCleanup() {
        assertFatalIdentity(new OutOfMemoryError("fatal"));
        assertFatalIdentity(new ThreadDeath());
    }

    @Test
    @DisplayName("普通 Error 被净化且 cause 与 suppressed 不携带 child Throwable")
    void ordinaryErrorIsSanitizedWithoutThrowableGraph() {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation preparation = prepareOwner(registry, "demo", "demo", 1L);
        AssertionError childFailure = new AssertionError("child details");
        Failing proxy = registry.prepareProxy(preparation, Failing.class, () -> {
            throw childFailure;
        });
        ExternalCapabilityPublication publication = registry.publish(preparation);

        assertThatThrownBy(proxy::fail)
                .isInstanceOf(ExternalCapabilityInvocationException.class)
                .hasCause(null)
                .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty())
                .hasMessageNotContaining("child details");
        ExternalCapabilityDrain drain = registry.withdraw(publication).orElseThrow();
        assertThat(drain.isDrained()).isTrue();
        registry.retireDrained(drain);
    }

    @Test
    @DisplayName("Future Stream Iterator 与 callback 形态在准备期直接拒绝")
    void deferredAndCallbackSignaturesAreRejected() {
        assertRejected(AsyncCapability.class, () -> CompletableFuture.completedFuture("x"));
        assertRejected(StreamCapability.class, Stream::empty);
        assertRejected(IteratorCapability.class, List.<String>of()::iterator);
        assertRejected(CallbackCapability.class, callback -> callback.accept("x"));
    }

    @Test
    @DisplayName("List Map Set Optional record array 与 Gallery 风格 DTO 图被深复制")
    void outboundCoreValueGraphIsDefensivelyCopied() {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation preparation = prepareOwner(registry, "demo", "demo", 1L);
        byte[] bytes = new byte[]{1, 2, 3};
        List<Map<String, Optional<byte[]>>> list = new ArrayList<>();
        Map<String, Optional<byte[]>> map = new LinkedHashMap<>();
        map.put("audio", Optional.of(bytes));
        list.add(map);
        Set<String> tags = new LinkedHashSet<>(List.of("one", "two"));
        ValueGraph original = new ValueGraph(list, tags);
        GraphCapability proxy = registry.prepareProxy(preparation, GraphCapability.class, () -> original);
        ExternalCapabilityPublication publication = registry.publish(preparation);

        ValueGraph copied = proxy.graph();
        byte[] copiedBytes = copied.items().get(0).get("audio").orElseThrow();
        assertThat(copied).isNotSameAs(original);
        assertThat(copied.items()).isNotSameAs(list);
        assertThat(copied.items().get(0)).isNotSameAs(map);
        assertThat(copied.tags()).isNotSameAs(tags);
        assertThat(copiedBytes).isNotSameAs(bytes).containsExactly(1, 2, 3);
        bytes[0] = 9;
        map.put("later", Optional.empty());
        tags.add("later");
        assertThat(copiedBytes).containsExactly(1, 2, 3);
        assertThat(copied.items().get(0)).doesNotContainKey("later");
        assertThat(copied.tags()).doesNotContain("later");

        ExternalCapabilityDrain drain = registry.withdraw(publication).orElseThrow();
        registry.retireDrained(drain);
    }

    @Test
    @DisplayName("真实 Gallery DTO 嵌套 record 与集合图不会把 child 返回对象直接交给宿主")
    void galleryDtoGraphIsDeepCopied() {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation preparation = prepareOwner(registry, "gallery", "gallery", 1L);
        GalleryWorkKey workKey = new GalleryWorkKey("pixiv", "illust", "1");
        GalleryProjection projection = new GalleryProjection(
                new GalleryProjectionKey(workKey, GalleryKind.IMAGE),
                "title",
                "summary",
                "/thumb/1",
                new GalleryActor("pixiv", "10", "author", "/avatar/10"),
                List.of(new GalleryTag("pixiv", "tag", "Tag")),
                null,
                null,
                null,
                Set.of(GalleryMediaKind.IMAGE),
                null,
                null,
                "original",
                Map.of("width", "100"));
        GalleryProjectionPage page = new GalleryProjectionPage(
                List.of(projection), null, false, List.of());
        GalleryPageCapability proxy = registry.prepareProxy(
                preparation, GalleryPageCapability.class, () -> page);
        ExternalCapabilityPublication publication = registry.publish(preparation);

        GalleryProjectionPage copiedPage = proxy.page();
        GalleryProjection copiedProjection = copiedPage.projections().get(0);
        assertThat(copiedPage).isNotSameAs(page);
        assertThat(copiedPage.projections()).isNotSameAs(page.projections());
        assertThat(copiedProjection).isNotSameAs(projection);
        assertThat(copiedProjection.key()).isNotSameAs(projection.key());
        assertThat(copiedProjection.key().workKey()).isNotSameAs(projection.key().workKey());
        assertThat(copiedProjection.author()).isNotSameAs(projection.author());
        assertThat(copiedProjection.tags()).isNotSameAs(projection.tags());
        assertThat(copiedProjection.tags().get(0)).isNotSameAs(projection.tags().get(0));
        assertThat(copiedProjection.attributes()).isNotSameAs(projection.attributes());
        assertThat(copiedPage).isEqualTo(page);

        ExternalCapabilityDrain drain = registry.withdraw(publication).orElseThrow();
        registry.retireDrained(drain);
    }

    @Test
    @DisplayName("child-loader 返回对象图无法复制时失败关闭且不返回对象")
    void unsupportedChildObjectGraphFailsClosed() throws Exception {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation preparation = prepareOwner(registry, "demo", "demo", 1L);
        try (URLClassLoader childLoader = new URLClassLoader(new URL[0], Marker.class.getClassLoader())) {
            Object childObject = Proxy.newProxyInstance(
                    childLoader,
                    new Class<?>[]{Marker.class},
                    (proxy, method, arguments) -> null);
            ObjectCapability proxy = registry.prepareProxy(
                    preparation, ObjectCapability.class, () -> childObject);
            ExternalCapabilityPublication publication = registry.publish(preparation);

            assertThatThrownBy(proxy::value)
                    .isInstanceOf(ExternalCapabilityInvocationException.class)
                    .hasMessageContaining("unsupported outbound value")
                    .hasCause(null);
            ExternalCapabilityDrain drain = registry.withdraw(publication).orElseThrow();
            registry.retireDrained(drain);
        }
    }

    private static void assertFatalIdentity(Error fatal) {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation preparation = prepareOwner(registry, "demo", "demo", 1L);
        Failing proxy = registry.prepareProxy(preparation, Failing.class, () -> {
            throw fatal;
        });
        ExternalCapabilityPublication publication = registry.publish(preparation);
        Throwable observed = null;
        try {
            proxy.fail();
        } catch (Throwable failure) {
            observed = failure;
        }
        assertThat(observed).isSameAs(fatal);
        ExternalCapabilityDrain drain = registry.withdraw(publication).orElseThrow();
        assertThat(drain.isDrained()).isTrue();
        registry.retireDrained(drain);
    }

    private static <T> void assertRejected(Class<T> type, T target) {
        ExternalCapabilityInvocationRegistry registry = new ExternalCapabilityInvocationRegistry();
        ExternalCapabilityPreparation preparation = prepareOwner(registry, "demo", "demo", 1L);
        assertThatThrownBy(() -> registry.prepareProxy(preparation, type, target))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit lifecycle wrapper");
        registry.discardUnpublished(preparation);
    }

    private static ExternalCapabilityPreparation prepareOwner(
            ExternalCapabilityInvocationRegistry registry,
            String pluginId,
            String packageId,
            long generation) {
        ExternalCapabilityPreparation preparation = registry.allocatePreparation(
                pluginId, packageId, generation);
        registry.installPreparation(preparation);
        return preparation;
    }

    private static PluginCapabilityContributionRegistrar.PreparedOwner prepareOwner(
            PluginCapabilityContributionRegistrar registrar,
            String pluginId,
            String packageId,
            long generation,
            ConfigurableApplicationContext context) {
        PluginCapabilityContributionRegistrar.PreparedOwner prepared = registrar.allocateOwner(
                pluginId, packageId, generation);
        registrar.prepareInto(prepared, context);
        return prepared;
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) failure;
    }

    public interface Echo {
        String call();
    }

    public interface Failing {
        void fail();
    }

    public interface AsyncCapability {
        CompletionStage<String> call();
    }

    public interface StreamCapability {
        Stream<String> call();
    }

    public interface IteratorCapability {
        Iterator<String> call();
    }

    public interface CallbackCapability {
        void call(Consumer<String> callback);
    }

    public interface GraphCapability {
        ValueGraph graph();
    }

    public interface ObjectCapability {
        Object value();
    }

    public interface GalleryPageCapability {
        GalleryProjectionPage page();
    }

    public interface Marker {
    }

    public record ValueGraph(
            List<Map<String, Optional<byte[]>>> items,
            Set<String> tags
    ) {
    }

    private static final class BlockingEcho implements Echo {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String call() {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timeout");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test interrupted");
            }
            return "done";
        }
    }

    private static final class NestedTarget implements Echo {
        private final Echo nested;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch proceed = new CountDownLatch(1);

        private NestedTarget(Echo nested) {
            this.nested = nested;
        }

        @Override
        public String call() {
            entered.countDown();
            await(proceed);
            return nested.call();
        }
    }

    private static final class CrossOwnerTarget implements Echo {
        private final Echo nested;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch proceed = new CountDownLatch(1);

        private CrossOwnerTarget(Echo nested) {
            this.nested = nested;
        }

        @Override
        public String call() {
            entered.countDown();
            await(proceed);
            try {
                return nested.call();
            } catch (ExternalCapabilityUnavailableException expected) {
                return "separate-admission-rejected";
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timeout");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted");
        }
    }
}
