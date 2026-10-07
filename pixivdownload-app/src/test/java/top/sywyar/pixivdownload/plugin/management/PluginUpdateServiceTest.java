package top.sywyar.pixivdownload.plugin.management;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.market.PluginMarketEntryView;
import top.sywyar.pixivdownload.plugin.market.PluginMarketService;
import top.sywyar.pixivdownload.plugin.market.PluginMarketView;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginUpdateServiceTest {
    @Test
    @DisplayName("同源更新区分兼容候选和 SDK 阻断，缓存随安装换代失效，查询失败不冒充没有更新")
    void summarizesVerifiedInstalledSources() {
        var repositories = mock(PluginRepositoryRegistry.class);
        var repository = mock(PluginRepository.class);
        when(repository.repositoryId()).thenReturn("official");
        when(repository.official()).thenReturn(true);
        when(repositories.featureEnabled()).thenReturn(true);
        when(repositories.enabledRepositories()).thenReturn(List.of(repository));
        var market = mock(PluginMarketService.class);
        var installer = mock(ExternalPluginInstaller.class);
        var lifecycle = mock(ExternalPluginLifecycleCoordinator.class);
        var inventory = mock(InstalledPluginInventorySnapshot.class);
        var installed = mock(InstalledPluginSnapshot.class);
        var plugin = mock(InstalledPlugin.class);
        var provenance = mock(PluginProvenanceRecord.class);
        when(installer.snapshotInstalledWithProvenance(anyInt(), anyLong())).thenReturn(inventory);
        when(inventory.entries()).thenReturn(List.of(installed));
        when(installed.plugin()).thenReturn(plugin);
        when(plugin.id()).thenReturn("fixture");
        when(plugin.version()).thenReturn("2.0.0");
        when(installed.provenance()).thenReturn(provenance);
        when(installed.artifactSha256()).thenReturn("digest");
        when(provenance.artifactSha256()).thenReturn("digest");
        when(provenance.source()).thenReturn(PluginPackageSource.LOCAL_UPLOAD);
        when(provenance.signature()).thenReturn(mock(SignatureMetadata.class));
        when(provenance.status()).thenReturn(VerificationStatus.VERIFIED);
        var entry = mock(PluginMarketEntryView.class);
        when(entry.pluginId()).thenReturn("fixture");
        when(entry.latestVersion()).thenReturn("4.0.0");
        when(entry.recommendedVersion()).thenReturn("3.0.0");
        when(entry.compatibilityReason()).thenReturn("9.0");
        var catalog = mock(PluginMarketView.class);
        when(catalog.entries()).thenReturn(List.of(entry));
        when(market.catalog(eq("official"), any())).thenReturn(catalog);
        var service = new PluginUpdateService(repositories, market, installer, lifecycle);
        var summary = service.summary();
        assertThat(summary.compatibleUpdates()).isEqualTo(1);
        assertThat(summary.sdkBlockedUpdates()).isEqualTo(1);
        assertThat(summary.checkFailed()).isFalse();
        assertThat(service.summary()).isSameAs(summary);
        verify(market, times(1)).catalog(eq("official"), any());
        when(lifecycle.lifecycleMutationEpoch()).thenReturn(2L);
        when(market.catalog(eq("official"), any())).thenThrow(new IllegalStateException("offline"));
        assertThat(service.summary().checkFailed()).isTrue();
        assertThat(service.summary().compatibleUpdates()).isZero();
        when(lifecycle.lifecycleMutationEpoch()).thenReturn(4L);
        when(provenance.source()).thenReturn(PluginPackageSource.MARKET_CATALOG);
        when(provenance.repositoryId()).thenReturn("another-repository");
        assertThat(service.summary().compatibleUpdates()).isZero();
        verify(market, times(2)).catalog(eq("official"), any());
    }
}
