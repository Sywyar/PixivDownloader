package top.sywyar.pixivdownload.config;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.common.UuidUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * 安装身份标识：{@code data/install_identity.txt} 中的随机 UUID v4，用于识别不同用户的安装。
 *
 * <p>只读语义：文件存在且内容为合法 UUID 时直接复用；文件不存在（首次运行）时生成一次并落盘。
 * 已存在的标识绝不覆盖、绝不重新生成；多进程并发首启时以 {@code CREATE_NEW} 竞争，败者读取胜者
 * 刚写入的值。进程内缓存，每个 JVM 只读取一次磁盘。
 */
@Slf4j
@UtilityClass
public class InstallIdentity {

    private static volatile String cached;

    public static String get() {
        String value = cached;
        if (value != null) {
            return value;
        }
        synchronized (InstallIdentity.class) {
            if (cached != null) {
                return cached;
            }
            cached = loadOrCreate();
            return cached;
        }
    }

    private static String loadOrCreate() {
        Path file = RuntimeFiles.resolveInstallIdentityPath();
        if (Files.isRegularFile(file)) {
            String existing = readIdentity(file);
            if (existing != null) {
                log.info("install identity loaded from {}", file.toAbsolutePath());
                return existing;
            }
            throw new IllegalStateException("corrupt install identity file: " + file.toAbsolutePath());
        }
        return create(file);
    }

    private static String create(Path file) {
        String id = UUID.randomUUID().toString();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, id + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            String raced = readIdentity(file);
            if (raced == null) {
                throw new IllegalStateException("install identity file appeared with corrupt content: "
                        + file.toAbsolutePath(), e);
            }
            return raced;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create install identity file: " + file, e);
        }
        log.info("install identity generated at {}", file.toAbsolutePath());
        return id;
    }

    private static String readIdentity(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            return UuidUtils.UUID_PATTERN.matcher(content).matches() ? content : null;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read install identity file: " + file, e);
        }
    }

    /** 仅供测试重置进程内缓存。 */
    static void resetForTests() {
        synchronized (InstallIdentity.class) {
            cached = null;
        }
    }
}
