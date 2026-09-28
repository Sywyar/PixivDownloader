package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("下载工作台静态声明")
class DownloadWorkbenchPluginContributionTest {
    @Test
    @DisplayName("路由与导航声明可复用且调用方不能修改")
    void reusesImmutableDeclarations() {
        var plugin = new DownloadWorkbenchPlugin();
        assertSame(plugin.routes(), plugin.routes());
        assertSame(plugin.navigation(), plugin.navigation());
        assertThrows(UnsupportedOperationException.class, () -> plugin.routes().clear());
    }
}
