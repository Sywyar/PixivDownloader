package top.sywyar.pixivdownload.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.spi.FileSystemProvider;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("初始化状态文件的字节边界")
class SetupConfigFileTest {
    private static final int LIMIT = 1024 * 1024;

    @TempDir
    Path directory;

    @Test
    @DisplayName("读取允许恰好达到字节上限的有效JSON并拒绝超限文件")
    void readsAtLimitAndRejectsOverflow() throws Exception {
        Path file = directory.resolve("setup.json");
        Files.writeString(file, "{}" + " ".repeat(LIMIT - 2), StandardCharsets.UTF_8);
        assertThat(SetupConfigFile.read(file, new ObjectMapper()).isSetupComplete()).isFalse();
        Files.writeString(file, "{}" + " ".repeat(LIMIT - 1), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SetupConfigFile.read(file, new ObjectMapper()))
                .isInstanceOf(IOException.class);
        assertThat(Files.size(file)).isEqualTo(LIMIT + 1);
    }

    @Test
    @DisplayName("大小观察后文件增大仍只读取上限加一字节并关闭通道")
    void growthAfterSizeObservationStaysBounded() throws Exception {
        Path file = directory.resolve("growing.json");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);
        BasicFileAttributes beforeGrowth = Files.readAttributes(file, BasicFileAttributes.class);
        Files.writeString(file, "{}" + " ".repeat(2 * LIMIT), StandardCharsets.UTF_8);
        AtomicInteger consumed = new AtomicInteger();
        SeekableByteChannel channel = spy(Files.newByteChannel(file));
        doAnswer(call -> {
            int count = (int) call.callRealMethod();
            if (count > 0) consumed.addAndGet(count);
            return count;
        }).when(channel).read(any(ByteBuffer.class));
        FileSystemProvider provider = mock(FileSystemProvider.class, CALLS_REAL_METHODS);
        FileSystem fileSystem = mock(FileSystem.class);
        Path observed = mock(Path.class);
        when(observed.getFileSystem()).thenReturn(fileSystem);
        when(fileSystem.provider()).thenReturn(provider);
        when(provider.readAttributes(observed, BasicFileAttributes.class))
                .thenReturn(beforeGrowth);
        when(provider.newByteChannel(eq(observed), any())).thenReturn(channel);
        try {
            assertThatThrownBy(() -> SetupConfigFile.read(observed, new ObjectMapper()))
                    .isInstanceOf(IOException.class);
            assertThat(consumed.get()).isEqualTo(LIMIT + 1);
            assertThat(channel.isOpen()).isFalse();
            verify(channel).close();
        } finally {
            channel.close();
        }
    }

    @Test
    @DisplayName("JSON null 不是有效初始化状态，拒绝读取及备份替换并保留原文件")
    void rejectsNullConfigWithoutReplacingFiles() throws Exception {
        Path file = directory.resolve("setup.json");
        Files.writeString(file, "null", StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        assertThatThrownBy(() -> SetupConfigFile.read(file, mapper)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> SetupConfigFile.write(file, new SetupConfig(), mapper))
                .isInstanceOf(IOException.class);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("null");
        assertThat(SetupConfigFile.backupPath(file)).doesNotExist();
    }

    @Test
    @DisplayName("拒绝写入空状态对象且不触及当前状态或备份")
    void rejectsNullWriteWithoutChangingStoredConfig() throws Exception {
        Path file = directory.resolve("setup.json");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);
        Files.writeString(SetupConfigFile.backupPath(file), "{\"mode\":\"solo\"}", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> SetupConfigFile.write(file, null, new ObjectMapper()))
                .isInstanceOf(IOException.class);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("{}");
        assertThat(Files.readString(SetupConfigFile.backupPath(file), StandardCharsets.UTF_8))
                .isEqualTo("{\"mode\":\"solo\"}");
    }
}
