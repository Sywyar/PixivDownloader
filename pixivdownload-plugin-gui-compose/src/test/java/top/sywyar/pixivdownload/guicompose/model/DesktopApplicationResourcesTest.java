package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopApplicationResourcesTest {
    @Test
    void missingMaintainerCatalogFallsBackToEmptyList() {
        assertTrue(DesktopApplicationResources.loadMaintainers(null).isEmpty());
    }
}
