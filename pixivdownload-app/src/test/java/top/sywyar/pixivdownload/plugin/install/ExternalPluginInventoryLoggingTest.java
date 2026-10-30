package top.sywyar.pixivdownload.plugin.install;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件清点重复告警")
class ExternalPluginInventoryLoggingTest {
    @Test
    @DisplayName("相同故障只告警一次，内容变化、移除重现和恢复后再次故障均重新告警")
    void deduplicatesWithoutTrustingInvalidArtifacts(@TempDir Path directory) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ExternalPluginInstaller.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        Path jar = directory.resolve("legacy.jar");
        try (var installer = new ExternalPluginInstaller(directory)) {
            assertThat(installer.recoverPendingTransactions().safeToScan()).isTrue();
            writeJar(jar, "legacya", false);
            for (int i = 0; i < 5; i++) assertThat(installer.listInstalled()).isEmpty();
            assertThat(warnings(appender)).isEqualTo(1);
            var timestamp = Files.getLastModifiedTime(jar);
            long size = Files.size(jar);
            writeJar(jar, "legacyb", false);
            assertThat(Files.size(jar)).isEqualTo(size);
            Files.setLastModifiedTime(jar, timestamp);
            assertThat(installer.listInstalled()).isEmpty();
            assertThat(warnings(appender)).isEqualTo(2);
            Files.delete(jar);
            assertThat(installer.listInstalled()).isEmpty();
            writeJar(jar, "legacyb", false);
            assertThat(installer.listInstalled()).isEmpty();
            assertThat(warnings(appender)).isEqualTo(3);
            writeJar(jar, "legacyb", true);
            assertThat(installer.listInstalled()).hasSize(1);
            writeJar(jar, "legacyb", false);
            assertThat(installer.listInstalled()).isEmpty();
            assertThat(installer.listInstalled()).isEmpty();
            assertThat(warnings(appender)).isEqualTo(4);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private long warnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().filter(event -> event.getFormattedMessage().startsWith("Skipping unreadable plugin package"))
                .count();
    }

    private void writeJar(Path jar, String id, boolean valid) throws Exception {
        String descriptor = "plugin.id=" + id + "\nplugin.version=1.0.0\nplugin.class=example.Plugin\n"
                + (valid ? "pixiv.execution-mode=host-process-full-trust\n" : "");
        byte[] bytes = descriptor.getBytes(StandardCharsets.UTF_8);
        var crc = new CRC32();
        crc.update(bytes);
        var entry = new ZipEntry("plugin.properties");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCrc(crc.getValue());
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(entry);
            zip.write(bytes);
            zip.closeEntry();
        }
    }
}
