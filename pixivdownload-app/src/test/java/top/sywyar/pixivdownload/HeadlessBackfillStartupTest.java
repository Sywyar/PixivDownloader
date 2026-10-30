package top.sywyar.pixivdownload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.UnavailableDataSource;
import top.sywyar.pixivdownload.gui.backfill.DesktopBackfillRunner;
import top.sywyar.pixivdownload.gui.backfill.HeadlessBackfillStartup;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;

import javax.sql.DataSource;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("无头启动自动回填")
class HeadlessBackfillStartupTest {
    @Test
    @DisplayName("真实启动入口只在自身就绪后提交一次，并使用当前数据库和配置")
    void submitsOnceWithEffectiveConfiguration() {
        var application = PixivDownloadApplication.buildHeadlessApplication();
        var target = mock(DatabaseTarget.class);
        when(target.available()).thenReturn(true);
        when(target.displayName()).thenReturn("selected-database");
        var runner = mock(DesktopBackfillRunner.class);
        try (var context = context(application, target, mock(DataSource.class), runner)) {
            verify(runner, never()).start(any(), any());
            context.publishEvent(ready(new SpringApplication(), context));
            verify(runner, never()).start(any(), any());
            context.publishEvent(ready(application, context));
            context.publishEvent(ready(application, context));
            var options = org.mockito.ArgumentCaptor.forClass(ArtworksBackFill.Options.class);
            verify(runner, times(1)).start(options.capture(), any());
            var value = options.getValue();
            assertSame(target, value.target());
            assertEquals("selected-database", value.dbPath());
            assertEquals("fixture-proxy", value.proxyHost());
            assertEquals(32123, value.proxyPort());
            assertFalse(value.useProxy());
            assertEquals(context.getBean(DownloadConfig.class).getRootFolder(), value.rootFolder());
            assertTrue(value.automatic());
            assertFalse(value.alwaysNetwork());
            assertFalse(value.dryRun());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("目标缺席或连接池不可用时保留恢复启动，不提交回填")
    void skipsUnavailableDatabase(boolean missingTarget) {
        var application = PixivDownloadApplication.buildHeadlessApplication();
        var target = mock(DatabaseTarget.class);
        when(target.available()).thenReturn(!missingTarget);
        var runner = mock(DesktopBackfillRunner.class);
        try (var context = context(application, target, new UnavailableDataSource(), runner)) {
            assertDoesNotThrow(() -> context.publishEvent(ready(application, context)));
            verify(runner, never()).start(any(), any());
        }
    }

    @Test
    @DisplayName("执行器拒绝不会使已就绪的服务器启动失败或重复重试")
    void submissionFailureDoesNotBreakServer() {
        var application = PixivDownloadApplication.buildHeadlessApplication();
        var target = mock(DatabaseTarget.class);
        when(target.available()).thenReturn(true);
        when(target.displayName()).thenReturn("fixture");
        var runner = mock(DesktopBackfillRunner.class);
        when(runner.start(any(), any())).thenThrow(new IllegalStateException("BACKFILL_BUSY"));
        try (var context = context(application, target, mock(DataSource.class), runner)) {
            assertDoesNotThrow(() -> context.publishEvent(ready(application, context)));
            assertDoesNotThrow(() -> context.publishEvent(ready(application, context)));
            verify(runner, times(1)).start(any(), any());
            assertTrue(context.isActive());
        }
    }

    private static AnnotationConfigApplicationContext context(SpringApplication application, DatabaseTarget target,
            DataSource dataSource, DesktopBackfillRunner runner) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(DatabaseTarget.class, () -> target);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(DesktopBackfillRunner.class, () -> runner);
        var proxy = new ProxyConfig();
        proxy.setHost("fixture-proxy");
        proxy.setPort(32123);
        proxy.setEnabled(false);
        context.registerBean(ProxyConfig.class, () -> proxy);
        var download = new DownloadConfig();
        download.setRootFolder("fixture-works");
        context.registerBean(DownloadConfig.class, () -> download);
        var listeners = application.getListeners().stream().filter(HeadlessBackfillStartup.class::isInstance).toList();
        assertEquals(1, listeners.size());
        context.addApplicationListener(listeners.get(0));
        context.refresh();
        return context;
    }

    private static ApplicationReadyEvent ready(SpringApplication application, AnnotationConfigApplicationContext context) {
        return new ApplicationReadyEvent(application, new String[0], context, Duration.ZERO);
    }
}
