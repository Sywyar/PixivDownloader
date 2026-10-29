package top.sywyar.pixivdownload.transfer.archive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("迁移归档的流式读写与信任边界")
class TransferArchiveTest {
    @TempDir Path temporary;

    @Test @DisplayName("预检不写目标文件，解包只使用受控编号而非归档路径")
    void roundTripsWithoutUsingLogicalPathsAsFilesystemTargets() throws Exception {
        Path archive = temporary.resolve("settings.pdbackup");
        var codec = new TransferArchive();
        var descriptor = new TransferArchive.Descriptor("host", TransferArchive.Category.CONFIG,
                "config/config.yaml", 1);
        codec.write(archive, null, "fixture", List.of(source(descriptor, "download.root-folder: D:/作品")));
        var preview = codec.inspect(archive, null);
        assertThat(preview.items()).hasSize(1);
        assertThat(preview.items().get(0).descriptor()).isEqualTo(descriptor);
        try (var files = Files.list(temporary)) {
            assertThat(files.map(Path::getFileName)).containsExactly(Path.of("settings.pdbackup"));
        }
        Path staging = temporary.resolve("staging");
        codec.extract(archive, null, staging, preview);
        assertThat(Files.readString(staging.resolve("0"))).isEqualTo("download.root-folder: D:/作品");
        assertThat(staging.resolve("config")).doesNotExist();
    }

    @Test @DisplayName("含凭据的归档必须加密，错误密码与篡改均不产生可用预览")
    void protectsSecretsAndAuthenticatesTheWholeStream() throws Exception {
        Path archive = temporary.resolve("secrets.pdbackup");
        var codec = new TransferArchive();
        var source = source(new TransferArchive.Descriptor("sample", TransferArchive.Category.CREDENTIALS,
                "credentials.json", 1), "unique-secret-value");
        assertThatThrownBy(() -> codec.write(archive, null, "fixture", List.of(source)))
                .hasMessage("transfer.password-required");
        assertThat(archive).doesNotExist();
        char[] password = "test-only-strong-password".toCharArray();
        codec.write(archive, password, "fixture", List.of(source));
        byte[] encrypted = Files.readAllBytes(archive);
        assertThat(new String(encrypted, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("unique-secret-value", "credentials.json");
        assertThat(codec.inspect(archive, password).encrypted()).isTrue();
        assertThatThrownBy(() -> codec.inspect(archive, "wrong".toCharArray()))
                .isInstanceOf(java.io.IOException.class);
        encrypted[encrypted.length - 1] ^= 1;
        Files.write(archive, encrypted);
        assertThatThrownBy(() -> codec.inspect(archive, password)).isInstanceOf(java.io.IOException.class);
    }

    @Test @DisplayName("拒绝跨目录、Windows 别名和大小写重复的逻辑路径")
    void rejectsUnsafeNamesAndAliases() throws Exception {
        var codec = new TransferArchive();
        for (String path : List.of("../outside", "C:/outside", "a\\b", "a/../b", "a/CON.txt", "a./b", "a/b ")) {
            assertThatThrownBy(() -> new TransferArchive.Descriptor("host", TransferArchive.Category.STATE, path, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var first = source(new TransferArchive.Descriptor("host", TransferArchive.Category.RESOURCE, "gui/state.json", 1), "one");
        var second = source(new TransferArchive.Descriptor("host", TransferArchive.Category.RESOURCE, "GUI/state.json", 1), "two");
        assertThatThrownBy(() -> codec.write(temporary.resolve("duplicate"), null, "fixture", List.of(first, second)))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test @DisplayName("确认后源文件变化、累计超限和截断均拒绝，失败不发布归档")
    void rejectsChangesTruncationAndCumulativeOverrun() throws Exception {
        Path archive = temporary.resolve("settings.pdbackup");
        var codec = new TransferArchive();
        var descriptor = new TransferArchive.Descriptor("host", TransferArchive.Category.RESOURCE, "state.json", 1);
        codec.write(archive, null, "fixture", List.of(source(descriptor, "before")));
        var preview = codec.inspect(archive, null);
        Path replacement = temporary.resolve("replacement");
        codec.write(replacement, null, "fixture", List.of(source(descriptor, "after")));
        Files.copy(replacement, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThatThrownBy(() -> codec.extract(archive, null, temporary.resolve("staging"), preview))
                .hasMessage("transfer.archive-changed");
        assertThat(temporary.resolve("staging")).doesNotExist();
        var small = new TransferArchive(new TransferArchive.Limits(5, 3, 8192));
        Path tooLarge = temporary.resolve("large");
        assertThatThrownBy(() -> small.write(tooLarge, null, "fixture", List.of(source(descriptor, "123456"))))
                .hasMessage("transfer.archive-limit");
        assertThat(tooLarge).doesNotExist();
        byte[] bytes = Files.readAllBytes(archive);
        Files.write(archive, Arrays.copyOf(bytes, bytes.length / 2));
        assertThatThrownBy(() -> codec.inspect(archive, null)).isInstanceOf(java.io.IOException.class);
    }

    private static TransferArchive.Source source(TransferArchive.Descriptor descriptor, String text) {
        return new TransferArchive.Source(descriptor,
                () -> new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test @DisplayName("跨认证分段的大文件往返，尾部截断和追加均拒绝")
    void authenticatesMultipleSegmentsAndRejectsMissingZipEnd() throws Exception {
        byte[] content = new byte[3 * 1024 * 1024];
        new java.util.Random(42).nextBytes(content);
        var descriptor = new TransferArchive.Descriptor("host", TransferArchive.Category.RESOURCE, "image.bin", 1);
        var source = new TransferArchive.Source(descriptor, () -> new ByteArrayInputStream(content));
        var codec = new TransferArchive();
        var database = new TransferArchive.Descriptor("host", TransferArchive.Category.DATABASE, "main/rows", 1);
        assertThatThrownBy(() -> codec.write(temporary.resolve("unsafe-database"), null, "fixture", List.of(source(database, "rows"))))
                .hasMessage("transfer.password-required");
        var state = new TransferArchive.Descriptor("host", TransferArchive.Category.STATE, "state.json", 1);
        assertThatThrownBy(() -> codec.write(temporary.resolve("unsafe-state"), null, "fixture", List.of(source(state, "{}"))))
                .hasMessage("transfer.password-required");
        for (char[] password : new char[][] {null, "archive-fixture-password".toCharArray()}) {
            Path archive = temporary.resolve(password == null ? "plain" : "encrypted");
            var preview = codec.write(archive, password, "fixture", List.of(source));
            Path staging = temporary.resolve(archive.getFileName() + "-staging");
            codec.extract(archive, password, staging, preview);
            assertThat(Files.readAllBytes(staging.resolve("0"))).isEqualTo(content);
            byte[] bytes = Files.readAllBytes(archive);
            Files.write(archive, Arrays.copyOf(bytes, bytes.length - 1));
            assertThatThrownBy(() -> codec.inspect(archive, password)).isInstanceOf(java.io.IOException.class);
            Files.write(archive, Arrays.copyOf(bytes, bytes.length + 1));
            assertThatThrownBy(() -> codec.inspect(archive, password)).isInstanceOf(java.io.IOException.class);
        }
    }
}
