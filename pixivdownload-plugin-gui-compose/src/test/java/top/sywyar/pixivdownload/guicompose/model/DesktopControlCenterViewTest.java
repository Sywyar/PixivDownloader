package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Compose 首页存储指标")
class DesktopControlCenterViewTest {
    @Test
    @DisplayName("容量按 1024 进制紧凑显示")
    void formatsCompactBinarySizes() {
        assertEquals("0B", DesktopControlCenterView.formatCompactBinarySize(0L));
        assertEquals("1.5KB", DesktopControlCenterView.formatCompactBinarySize(1536L));
        assertEquals("100GB", DesktopControlCenterView.formatCompactBinarySize(100L << 30));
        assertEquals("1TB", DesktopControlCenterView.formatCompactBinarySize(1L << 40));
    }
}
