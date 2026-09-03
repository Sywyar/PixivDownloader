package top.sywyar.pixivdownload.plugin.management;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HostPrivilegeDetector 宿主高权限检测")
class HostPrivilegeDetectorTest {

    @Test
    @DisplayName("Windows 只把高完整性及以上令牌识别为高权限")
    void detectsWindowsIntegrityLevel() {
        assertThat(HostPrivilegeDetector.isElevated("Windows 11", "S-1-16-8192")).isFalse();
        assertThat(HostPrivilegeDetector.isElevated("Windows 11", "S-1-16-12288")).isTrue();
        assertThat(HostPrivilegeDetector.isElevated("Windows Server", "S-1-16-16384")).isTrue();
    }

    @Test
    @DisplayName("Unix 只把有效用户 ID 0 识别为高权限")
    void detectsUnixRoot() {
        assertThat(HostPrivilegeDetector.isElevated("Linux", "0\n")).isTrue();
        assertThat(HostPrivilegeDetector.isElevated("Mac OS X", "501\n")).isFalse();
    }
}
