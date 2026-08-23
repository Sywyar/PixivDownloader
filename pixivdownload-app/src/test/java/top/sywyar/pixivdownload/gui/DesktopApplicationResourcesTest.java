package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopApplicationResourcesTest {

    @Test
    @DisplayName("缺少维护者目录时回退为空列表")
    void missingMaintainerCatalogFallsBackToEmptyList() {
        assertThat(DesktopApplicationResources.loadMaintainers(null)).isEmpty();
    }
}
