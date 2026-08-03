package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.common.UuidUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InstallIdentity 安装身份标识")
class InstallIdentityTest {

    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @TempDir
    Path tempDir;

    private Path dataDir;

    @BeforeEach
    void setUp() {
        dataDir = tempDir.resolve("data");
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, dataDir.toString());
        InstallIdentity.resetForTests();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        InstallIdentity.resetForTests();
    }

    @Test
    @DisplayName("首次请求生成随机 UUID 并落盘，二次请求返回同一标识")
    void generatesAndPersistsIdentity() {
        String first = InstallIdentity.get();

        assertThat(first).matches(UUID_TEXT);
        assertThat(Files.isRegularFile(dataDir.resolve(RuntimeFiles.INSTALL_IDENTITY_FILE)))
                .as("标识文件必须写入 data 目录")
                .isTrue();
        assertThat(InstallIdentity.get())
                .as("进程内缓存必须返回同一标识")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("已存在的标识文件被复用，绝不重新生成或覆盖")
    void reusesExistingIdentity() throws IOException {
        Path file = dataDir.resolve(RuntimeFiles.INSTALL_IDENTITY_FILE);
        Files.createDirectories(dataDir);
        String existing = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        Files.writeString(file, existing + "\n", StandardCharsets.UTF_8);

        assertThat(InstallIdentity.get()).isEqualTo(existing);
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as("已存在的标识文件内容不得被覆盖")
                .contains(existing);
    }

    @Test
    @DisplayName("内容为非法 UUID 时拒绝使用并抛错，不静默重新生成")
    void rejectsCorruptIdentity() throws IOException {
        Path file = dataDir.resolve(RuntimeFiles.INSTALL_IDENTITY_FILE);
        Files.createDirectories(dataDir);
        Files.writeString(file, "not-a-uuid\n", StandardCharsets.UTF_8);

        assertThatThrownBy(InstallIdentity::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt install identity");
    }

    @Test
    @DisplayName("标识值符合标准 UUID v4 格式校验")
    void generatedIdentityMatchesUuidPattern() {
        assertThat(UuidUtils.UUID_PATTERN.matcher(InstallIdentity.get()).matches()).isTrue();
    }
}
