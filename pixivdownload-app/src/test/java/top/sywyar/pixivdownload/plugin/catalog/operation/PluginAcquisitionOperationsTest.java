package top.sywyar.pixivdownload.plugin.catalog.operation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.catalog.*;
import top.sywyar.pixivdownload.plugin.catalog.error.*;
import top.sywyar.pixivdownload.plugin.catalog.manifest.*;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.install.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.lifecycle.*;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginAcquisitionOperationsTest {
    private final PluginCatalogAcquisitionService acquisition = mock(PluginCatalogAcquisitionService.class);
    private final ExternalPluginLifecycleCoordinator coordinator = mock(ExternalPluginLifecycleCoordinator.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2025-01-01T00:00:00Z"));
    private final String fingerprint = "a".repeat(64);
    private PluginAcquisitionOperations service;

    @BeforeEach
    void prepare() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> now.get());
        when(acquisition.preview("official", "sample", "1.0.0"))
                .thenReturn(new PluginCatalogInstallPreview.View(fingerprint, List.of(
                        new PluginCatalogInstallPreview.Item("sample", "1.0.0", "official", null, "b".repeat(64),
                                null, null, "INSTALL", List.of(), List.of(), "PACKAGE_DECIDES")), List.of()));
        service = new PluginAcquisitionOperations(acquisition, coordinator, clock);
    }

    private PluginAcquisitionOperations.Snapshot prepareOperation() {
        return service.prepare("official", "sample", "1.0.0", fingerprint, null);
    }

    private PluginInstallReport report() {
        return new PluginInstallReport(PluginInstallOutcome.INSTALLED, true, false, "sample", "1.0.0", null,
                List.of(), List.of(), List.of(), List.of(), "existing-transaction", true, false, null,
                ExternalPluginOperation.IDLE, PluginRuntimePhase.STARTED, false, false, List.of());
    }

    @Test
    @DisplayName("准备无安装副作用，下载中可查询且重复执行不启动第二次安装")
    void queriesWhileDownloadingAndDeduplicatesExecution() throws Exception {
        var prepared = prepareOperation();
        assertThat(prepared.started()).isFalse();
        assertThat(prepared.repositoryId()).isEqualTo("official");
        assertThat(prepared.pluginId()).isEqualTo("sample");
        assertThat(prepared.version()).isEqualTo("1.0.0");
        verify(acquisition).preview("official", "sample", "1.0.0");
        verify(acquisition, never()).installPreviewed(anyString(), anyString(), anyString(), any(), anyString(), any());
        var download = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            Consumer<ExternalPluginOperationSnapshot> progress = call.getArgument(5);
            progress.accept(new ExternalPluginOperationSnapshot("sample", ExternalPluginOperation.DOWNLOADING, null, null));
            download.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            progress.accept(new ExternalPluginOperationSnapshot("sample", ExternalPluginOperation.INSTALLING, null, null));
            when(coordinator.operation("sample")).thenReturn(Optional.of(new ExternalPluginOperationSnapshot(
                    "sample", ExternalPluginOperation.ROLLING_BACK, "existing-transaction", null)));
            var during = service.get(prepared.id());
            assertThat(during.transactionId()).isEqualTo("existing-transaction");
            assertThat(during.operation()).isEqualTo(ExternalPluginOperation.ROLLING_BACK);
            return report();
        }).when(acquisition).installPreviewed(eq("official"), eq("sample"), eq("1.0.0"), isNull(), eq(fingerprint), any());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(() -> service.execute(prepared.id()));
            assertThat(download.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.get(prepared.id()).operation()).isEqualTo(ExternalPluginOperation.DOWNLOADING);
            assertThat(service.execute(prepared.id()).finished()).isFalse();
            assertThat(service.list()).singleElement().satisfies(value -> assertThat(value.started()).isTrue());
            var competing = prepareOperation();
            assertThatThrownBy(() -> service.execute(competing.id())).isInstanceOfSatisfying(PluginCatalogException.class,
                    failure -> assertThat(failure.code()).isEqualTo(PluginCatalogErrorCode.OPERATION_IN_PROGRESS));
            now.set(now.get().plus(PluginAcquisitionOperations.RETENTION));
            assertThat(service.list()).singleElement().satisfies(value -> assertThat(value.id()).isEqualTo(prepared.id()));
            release.countDown();
            assertThat(running.get(5, TimeUnit.SECONDS).report().transactionId()).isEqualTo("existing-transaction");
            assertThat(service.execute(prepared.id()).finished()).isTrue();
            verify(acquisition, times(1)).installPreviewed(anyString(), anyString(), anyString(), any(), anyString(), any());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("目录失败与部分成功可重复查询，重复请求不清掉原错误或再次执行")
    void retainsFailureAndPartialSuccess() {
        var prepared = prepareOperation();
        var dependency = top.sywyar.pixivdownload.plugin.install.PluginDependencyInstallResult.from(report());
        when(acquisition.installPreviewed(anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new PluginCatalogException(PluginCatalogErrorCode.DOWNLOAD_FAILED, "download failed")
                        .withDependencyInstallResults(List.of(dependency)));
        var first = service.execute(prepared.id());
        assertThat(first.finished()).isTrue();
        assertThat(first.failure().dependencyInstallResults()).singleElement()
                .satisfies(value -> assertThat(value.transactionId()).isEqualTo("existing-transaction"));
        assertThat(service.execute(prepared.id())).isEqualTo(first);
        verify(acquisition, times(1)).installPreviewed(anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("过期与进程重建后的身份只返回未知，不会重新安装")
    void expiredAndPreviousProcessIdsNeverExecute() {
        var expired = prepareOperation();
        now.set(now.get().plus(PluginAcquisitionOperations.RETENTION));
        assertThatThrownBy(() -> service.execute(expired.id())).isInstanceOfSatisfying(PluginCatalogException.class,
                failure -> assertThat(failure.code()).isEqualTo(PluginCatalogErrorCode.OPERATION_NOT_FOUND));
        assertThat(service.list()).isEmpty();
        var anotherProcess = new PluginAcquisitionOperations(acquisition, coordinator);
        assertThatThrownBy(() -> anotherProcess.execute(expired.id())).isInstanceOf(PluginCatalogException.class);
        verify(acquisition, never()).installPreviewed(anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("记录容量有界，淘汰未执行记录后旧身份仍不触发副作用")
    void boundsHistoryWithoutReplayingEvictedOperations() {
        var first = prepareOperation();
        for (int i = 0; i < PluginAcquisitionOperations.MAX_RECORDS; i++) prepareOperation();
        assertThat(service.list()).hasSize(PluginAcquisitionOperations.MAX_RECORDS);
        assertThatThrownBy(() -> service.execute(first.id())).isInstanceOf(PluginCatalogException.class);
        verify(acquisition, never()).installPreviewed(anyString(), anyString(), anyString(), any(), anyString(), any());
    }
}
