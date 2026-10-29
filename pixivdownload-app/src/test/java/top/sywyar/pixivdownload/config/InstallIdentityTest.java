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
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InstallIdentity 安装身份标识")
class InstallIdentityTest {

    /** 真实 UUID v4（version 4 / RFC 4122 variant）。 */
    private static final String V4_ID = "abcdefab-cdef-4abc-8def-abcdefabcdef";

    /** 外形合法但 version 为 1 的普通 UUID。 */
    private static final String VERSION_NOT_4 = "11111111-2222-1333-8444-555555555555";

    /** 外形合法、version 为 4 但 variant 非 RFC 4122（Microsoft variant）的 UUID。 */
    private static final String VARIANT_NOT_2 = "11111111-2222-4333-ccc4-555555555555";

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
    @DisplayName("迁移预检不创建身份，绕过进程缓存并拒绝超长内容")
    void readsIdentityWithoutCreatingOrCaching() throws IOException {
        assertThat(InstallIdentity.readExisting()).isEmpty();
        assertThat(dataDir).doesNotExist();
        InstallIdentity.get();
        Path file = dataDir.resolve(RuntimeFiles.INSTALL_IDENTITY_FILE);
        Files.writeString(file, V4_ID, StandardCharsets.UTF_8);
        assertThat(InstallIdentity.readExisting()).contains(V4_ID);
        Files.writeString(file, V4_ID + " ".repeat(257), StandardCharsets.UTF_8);
        assertThatThrownBy(InstallIdentity::readExisting).isInstanceOf(IOException.class);
        assertThat(Files.readString(file)).hasSize(V4_ID.length() + 257);
    }

    @Test
    @DisplayName("首次请求生成随机 UUID v4 并落盘，二次请求返回同一标识")
    void generatesAndPersistsIdentity() {
        String first = InstallIdentity.get();

        assertThat(first).matches(UuidUtils.UUID_PATTERN);
        UUID uuid = UUID.fromString(first);
        assertThat(uuid.version()).isEqualTo(4);
        assertThat(uuid.variant()).isEqualTo(2);
        assertThat(Files.isRegularFile(dataDir.resolve(RuntimeFiles.INSTALL_IDENTITY_FILE)))
                .as("标识文件必须写入 data 目录")
                .isTrue();
        assertThat(InstallIdentity.get())
                .as("进程内缓存必须返回同一标识")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("已存在的合法 v4 标识文件被复用，绝不重新生成或覆盖")
    void reusesExistingIdentity() throws IOException {
        Path file = dataDir.resolve(RuntimeFiles.INSTALL_IDENTITY_FILE);
        Files.createDirectories(dataDir);
        Files.writeString(file, V4_ID + "\n", StandardCharsets.UTF_8);

        assertThat(InstallIdentity.get()).isEqualTo(V4_ID);
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as("已存在的标识文件内容不得被覆盖")
                .contains(V4_ID);
    }

    @Test
    @DisplayName("已存在的大写 v4 标识被规范化为小写复用")
    void normalizesUpperCaseV4Identity() throws IOException {
        Path file = dataDir.resolve(RuntimeFiles.INSTALL_IDENTITY_FILE);
        Files.createDirectories(dataDir);
        Files.writeString(file, V4_ID.toUpperCase(Locale.ROOT) + "\n", StandardCharsets.UTF_8);

        assertThat(InstallIdentity.get()).isEqualTo(V4_ID);
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as("已存在的文件内容不得被覆盖")
                .contains(V4_ID.toUpperCase(Locale.ROOT));
    }

    @Test
    @DisplayName("内容为非法文本时拒绝使用并抛错，不静默重新生成")
    void rejectsCorruptIdentity() throws IOException {
        Path file = dataDir.resolve(RuntimeFiles.INSTALL_IDENTITY_FILE);
        Files.createDirectories(dataDir);
        Files.writeString(file, "not-a-uuid\n", StandardCharsets.UTF_8);

        assertThatThrownBy(InstallIdentity::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt install identity");
    }

    @Test
    @DisplayName("外形合法但 version 非 4 的 UUID 视为损坏被拒绝")
    void rejectsUuidWithWrongVersion() throws IOException {
        Path file = dataDir.resolve(RuntimeFiles.INSTALL_IDENTITY_FILE);
        Files.createDirectories(dataDir);
        Files.writeString(file, VERSION_NOT_4 + "\n", StandardCharsets.UTF_8);

        assertThatThrownBy(InstallIdentity::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt install identity");
    }

    @Test
    @DisplayName("外形合法、version 为 4 但 variant 非 RFC 4122 的 UUID 视为损坏被拒绝")
    void rejectsUuidWithWrongVariant() throws IOException {
        Path file = dataDir.resolve(RuntimeFiles.INSTALL_IDENTITY_FILE);
        Files.createDirectories(dataDir);
        Files.writeString(file, VARIANT_NOT_2 + "\n", StandardCharsets.UTF_8);

        assertThatThrownBy(InstallIdentity::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt install identity");
    }

    @Test
    @DisplayName("已存在损坏文件不被覆盖，进程内缓存不产生第二个值")
    void corruptFileIsNeverOverwritten() throws IOException {
        Path file = dataDir.resolve(RuntimeFiles.INSTALL_IDENTITY_FILE);
        Files.createDirectories(dataDir);
        Files.writeString(file, "corrupt-content\n", StandardCharsets.UTF_8);

        assertThatThrownBy(InstallIdentity::get)
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .as("损坏文件内容不得被覆盖或重新生成")
                .isEqualTo("corrupt-content\n");
        assertThatThrownBy(InstallIdentity::get)
                .isInstanceOf(IllegalStateException.class);
    }
}
