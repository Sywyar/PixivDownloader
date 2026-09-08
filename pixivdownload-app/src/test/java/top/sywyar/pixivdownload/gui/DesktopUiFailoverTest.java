package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DesktopUiFailoverTest {
    private final AtomicInteger unavailable = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicReference<DesktopUiSession> active = new AtomicReference<>();
    private RuntimeException notificationFailure;

    @Test
    @DisplayName("默认 GUI 初始化错误后启动其它 GUI 并提示实际回退")
    void launchFailureFallsBackAndWarns() throws Exception {
        DesktopUiPluginSource broken = source("broken", true);
        DesktopUiPluginSource healthy = source("healthy", false);
        DesktopUiSession session = mock(DesktopUiSession.class);
        when(provider(broken).launch(any())).thenThrow(new ExceptionInInitializerError("enum reflection"));
        when(provider(healthy).launch(any())).thenReturn(session);
        try (DesktopUiFailover ui = controller(List.of(healthy, broken))) {
            ui.start("");
            assertThat(active.get()).isSameAs(ui);
            assertThat(failures.get()).isEqualTo(1);
            assertThat(unavailable.get()).isZero();
            verify(session).showMessage(eq(DesktopUiSession.MessageLevel.WARNING), any(), contains("broken"));
            ui.activate();
            verify(session).activate();
        }
        verify(session).close();
    }

    @Test
    @DisplayName("空列表或所有 GUI 崩溃时仅调用一次现有无 GUI 提示")
    void unavailableUsesExistingPromptOnce() throws Exception {
        try (DesktopUiFailover ui = controller(List.of())) {
            ui.start("");
            ui.start("");
        }
        assertThat(unavailable.get()).isEqualTo(1);
        unavailable.set(0);
        DesktopUiPluginSource first = source("first", true);
        DesktopUiPluginSource second = source("second", false);
        when(provider(first).launch(any())).thenThrow(new LinkageError("missing method"));
        when(provider(second).launch(any())).thenThrow(new AssertionError("renderer"));
        try (DesktopUiFailover ui = controller(List.of(first, second))) {
            ui.start("");
            ui.start("");
            assertThat(active.get()).isNull();
        }
        assertThat(unavailable.get()).isEqualTo(1);
        verify(provider(first), times(1)).launch(any());
        verify(provider(second), times(1)).launch(any());
    }

    @Test
    @DisplayName("运行中 GUI 通过上下文上报崩溃后关闭旧会话并忽略重复回调")
    void runtimeFailureClosesOldSessionAndDoesNotLoop() throws Exception {
        notificationFailure = new IllegalStateException("failure observer unavailable");
        DesktopUiPluginSource first = source("first", true);
        DesktopUiPluginSource second = source("second", false);
        DesktopUiSession original = mock(DesktopUiSession.class);
        DesktopUiSession replacement = mock(DesktopUiSession.class);
        AtomicReference<DesktopUiContext> context = new AtomicReference<>();
        CountDownLatch switched = new CountDownLatch(1);
        when(provider(first).launch(any())).thenAnswer(call -> {
            context.set(call.getArgument(0));
            return original;
        });
        when(provider(second).launch(any())).thenReturn(replacement);
        doAnswer(call -> { switched.countDown(); return null; }).when(replacement).showMessage(any(), any(), any());
        try (DesktopUiFailover ui = controller(List.of(first, second))) {
            ui.start("");
            context.get().reportFailure(new AssertionError("render failed"));
            assertThat(switched.await(5, TimeUnit.SECONDS)).isTrue();
            context.get().reportFailure(new AssertionError("stale callback"));
            ui.activate();
            verify(original).close();
            verify(replacement).activate();
            assertThat(failures.get()).isEqualTo(1);
            assertThat(unavailable.get()).isZero();
        }
    }

    @Test
    @DisplayName("GUI 在 launch 返回前异步报错也不会漏报或阻塞回退")
    void failureBeforeLaunchReturnsIsRetained() throws Exception {
        DesktopUiPluginSource first = source("first", true);
        DesktopUiPluginSource second = source("second", false);
        DesktopUiSession original = mock(DesktopUiSession.class);
        when(provider(first).launch(any())).thenAnswer(call -> {
            DesktopUiContext context = call.getArgument(0);
            Thread renderer = new Thread(() -> context.reportFailure(new AssertionError("early failure")));
            renderer.start();
            renderer.join(2000);
            assertThat(renderer.isAlive()).isFalse();
            return original;
        });
        when(provider(second).launch(any())).thenReturn(mock(DesktopUiSession.class));
        try (DesktopUiFailover ui = controller(List.of(first, second))) {
            ui.start("");
            verify(original).close();
            verify(provider(second)).launch(any());
        }
    }

    @Test
    @DisplayName("GUI 元数据抛错时仍尝试其余提供者")
    void brokenDefaultMetadataIsIsolated() throws Exception {
        DesktopUiPluginSource broken = source("broken", false);
        DesktopUiPluginSource healthy = source("healthy", false);
        when(provider(broken).defaultProvider()).thenThrow(new NoClassDefFoundError("metadata"));
        when(provider(healthy).launch(any())).thenReturn(mock(DesktopUiSession.class));
        try (DesktopUiFailover ui = controller(List.of(broken, healthy))) {
            ui.start("broken");
            verify(provider(healthy)).launch(any());
            assertThat(failures.get()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("JVM 致命错误保持原对象抛出")
    void fatalVmErrorsAreNotConvertedToFallback() throws Exception {
        DesktopUiPluginSource source = source("fatal", true);
        StackOverflowError failure = new StackOverflowError("VM failure");
        when(provider(source).launch(any())).thenThrow(failure);
        try (DesktopUiFailover ui = controller(List.of(source))) {
            assertThatThrownBy(() -> ui.start("")).isSameAs(failure);
            assertThat(unavailable.get()).isZero();
        }
    }

    @Test
    @DisplayName("启动前收到崩溃事件时跳过故障 GUI 并回退到可用提供者")
    void failureBeforeSelectionExcludesProvider() throws Exception {
        DesktopUiPluginSource broken = source("broken", true);
        DesktopUiPluginSource healthy = source("healthy", false);
        when(provider(healthy).launch(any())).thenReturn(mock(DesktopUiSession.class));
        try (DesktopUiFailover ui = controller(List.of(broken, healthy))) {
            ui.reportFailure("broken", new LinkageError("metadata"));
            ui.start("broken");
            verify(provider(broken), never()).launch(any());
            verify(provider(healthy)).launch(any());
            assertThat(unavailable.get()).isZero();
        }
    }

    private DesktopUiFailover controller(List<DesktopUiPluginSource> sources) {
        return new DesktopUiFailover(sources, (id, failure) -> new DesktopUiContext(false, 6999, "download",
                Path.of("config.yaml"), id, mock(DesktopUiHost.class), List.of(), List::of,
                token -> token.key(), () -> "system", failure), active::set, unavailable::incrementAndGet,
                (source, failure) -> {
                    failures.incrementAndGet();
                    if (notificationFailure != null) throw notificationFailure;
                });
    }

    private static DesktopUiPluginSource source(String id, boolean defaultProvider) {
        PixivFeaturePlugin plugin = mock(PixivFeaturePlugin.class, withSettings().extraInterfaces(DesktopUiProvider.class));
        when(plugin.id()).thenReturn(id);
        when(((DesktopUiProvider) plugin).defaultProvider()).thenReturn(defaultProvider);
        return new DesktopUiPluginSource(id, false, plugin, plugin.getClass().getClassLoader());
    }

    private static DesktopUiProvider provider(DesktopUiPluginSource source) {
        return (DesktopUiProvider) source.plugin();
    }
}
