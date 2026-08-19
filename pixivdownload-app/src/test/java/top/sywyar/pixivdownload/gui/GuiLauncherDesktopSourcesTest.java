package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GuiLauncherDesktopSourcesTest {
    @Test
    void reusesSourcesUntilTheRuntimeDiscoveryChanges() {
        PluginDiscoveryResult initial = PluginDiscoveryResult.empty();
        PluginDiscoveryResult changed = new PluginDiscoveryResult(
                List.of(), List.of(new PluginLoadFailure("broken.jar", "invalid")));
        PluginDiscoveryResult[] current = {initial};
        AtomicInteger builds = new AtomicInteger();
        List<DesktopUiContext.PluginSource> initialSources = List.of();
        var sources = GuiLauncher.memoizedDesktopUiSources(
                () -> current[0], discovery -> {
                    builds.incrementAndGet();
                    return List.of();
                }, initial, initialSources);

        assertThat(sources.get()).isSameAs(initialSources);
        assertThat(sources.get()).isSameAs(initialSources);
        assertThat(builds).hasValue(0);

        current[0] = changed;
        sources.get();
        sources.get();
        assertThat(builds).hasValue(1);
    }
}
