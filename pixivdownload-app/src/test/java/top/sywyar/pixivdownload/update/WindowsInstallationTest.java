package top.sywyar.pixivdownload.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer.InstallationKind.*;

@DisplayName("安装登记只读探测")
class WindowsInstallationTest {
    @TempDir Path temp;

    @Test @DisplayName("便携目录不会因另一处安装登记而变成迁移实例")
    void portableDoesNotClaimAnotherInstallation() throws Exception {
        Path old = temp.resolve("elsewhere");
        var result = WindowsInstallation.inspect(temp, List.of(old.toString(), old.toString(), ""));
        assertThat(result.kind()).isEqualTo(PORTABLE);
        try (var files = Files.list(temp)) { assertThat(files).isEmpty(); }
        Files.writeString(temp.resolve("unins000.exe"), "incomplete installation");
        assertThat(WindowsInstallation.inspect(temp, List.of("", "", "")).kind()).isEqualTo(REPAIR_REQUIRED);
    }

    @Test @DisplayName("旧版安装、搬移副本、登记丢失和未完成修复分别呈现")
    void distinguishesInstalledMovedAndIncomplete() throws Exception {
        Files.writeString(temp.resolve("unins000.exe"), "fixture");
        Files.writeString(temp.resolve("unins000.dat"), "fixture");
        var registered = List.of(temp.toString(), temp.toString(), '"' + temp.resolve("unins000.exe").toString() + '"');
        assertThat(WindowsInstallation.inspect(temp, registered).kind()).isEqualTo(INSTALLED);
        assertThat(WindowsInstallation.inspect(temp, List.of("", "", "")).kind()).isEqualTo(REPAIR_REQUIRED);
        Path previous = temp.resolveSibling("原位置 [copy]");
        Files.writeString(temp.resolve(WindowsInstallation.MARKER), "\uFEFFPixivDownload installation 1\r\n" + previous + "\r\n");
        assertThat(WindowsInstallation.inspect(temp, registered).kind()).isEqualTo(MOVED);
        Files.writeString(temp.resolve(WindowsInstallation.MARKER), "PixivDownload relocation 1\n\n");
        assertThat(WindowsInstallation.inspect(temp, registered).kind()).isEqualTo(REPAIR_REQUIRED);
        Files.writeString(temp.resolve(WindowsInstallation.MARKER), "PixivDownload installation 1\n" + temp + "\n");
        assertThat(WindowsInstallation.inspect(temp, registered).kind()).isEqualTo(INSTALLED);
        var wrongCommand = List.of(temp.toString(), temp.toString(), '"' + previous.resolve("unins000.exe").toString() + '"');
        assertThat(WindowsInstallation.inspect(temp, wrongCommand).kind()).isEqualTo(REPAIR_REQUIRED);
    }

    @Test @DisplayName("损坏、超大和未知版本标记不按便携版处理")
    void rejectsInvalidMarker() throws Exception {
        for (String marker : List.of("wrong", "PixivDownload installation 2\n" + temp + "\n", "x".repeat(131073))) {
            Files.writeString(temp.resolve(WindowsInstallation.MARKER), marker);
            assertThatThrownBy(() -> WindowsInstallation.inspect(temp, List.of("", "", ""))).isInstanceOf(IOException.class);
        }
    }

    @Test @EnabledOnOs(OS.WINDOWS) @DisplayName("真实只读注册表查询完整返回三个字符串")
    void readsRegistryWithoutChangingIt() throws Exception {
        assertThat(WindowsInstallation.queryRegistry()).hasSize(3).doesNotContainNull();
    }
}
