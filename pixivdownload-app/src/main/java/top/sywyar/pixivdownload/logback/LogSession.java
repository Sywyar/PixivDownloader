package top.sywyar.pixivdownload.logback;

import ch.qos.logback.core.PropertyDefinerBase;
import ch.qos.logback.core.spi.LifeCycle;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 在 Logback 创建文件 appender 前分配日志会话；同一目录只有一个进程能够轮换和写入日志。
 */
public final class LogSession extends PropertyDefinerBase implements LifeCycle {
    static final int HISTORY_COUNT = 5;
    private static final String PREFIX = "pixiv-download_";
    private final String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss_SSS"))
            + "_" + UUID.randomUUID();
    private final List<String> warnings = new ArrayList<>();
    private FileChannel channel;
    private FileLock lock;
    private boolean started;

    @Override
    public void start() {
        if (started) return;
        started = true;
        context.register(this);
        context.putObject(LogSession.class.getName(), this);
        context.putProperty("LOG_FILES_ENABLED", "false");
        Path directory = RuntimeFiles.logDirectory();
        context.putProperty("LOG_DIRECTORY", directory.toAbsolutePath().toString());
        try {
            Files.createDirectories(directory);
            channel = FileChannel.open(directory.resolve(".session.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException occupied) {
                return;
            }
            if (lock == null) return;
            Files.createDirectories(directory.resolve("html"));
            context.putProperty("LOG_FILES_ENABLED", "true");
            cleanHistory(directory);
        } catch (IOException failure) {
            warnings.add(failure.toString());
        }
    }

    private void cleanHistory(Path directory) {
        // 两种格式按同一会话名清理；一次删除失败不妨碍其它文件清理。
        TreeSet<String> sessions = new TreeSet<>();
        for (Path folder : List.of(directory, directory.resolve("html"))) {
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.matches("pixiv-download_(?:startup|\\d{4}-\\d{2}-\\d{2}_\\d{6}(?:_\\d{3}_[0-9a-f-]{36})?)\\.(?:log|html)"))
                        .map(name -> name.substring(PREFIX.length(), name.lastIndexOf('.')))
                        .forEach(sessions::add);
            } catch (IOException failure) {
                warnings.add(failure.toString());
            }
        }
        // 遗留 startup 文件没有会话时间戳，只作为最旧的一次历史保留。
        List<String> ordered = new ArrayList<>(sessions);
        if (ordered.remove("startup")) ordered.add(0, "startup");
        for (String session : ordered.subList(0, Math.max(0, ordered.size() - HISTORY_COUNT))) {
            for (Path file : List.of(directory.resolve(PREFIX + session + ".log"),
                    directory.resolve("html").resolve(PREFIX + session + ".html"))) {
                try {
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.delete(file);
                } catch (IOException failure) {
                    warnings.add(failure.toString());
                }
            }
        }
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    @Override
    public String getPropertyValue() {
        start();
        return timestamp;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public void stop() {
        try {
            if (lock != null) lock.close();
        } catch (IOException failure) {
            addWarn("Unable to close log session", failure);
        } finally {
            try {
                if (channel != null) channel.close();
            } catch (IOException failure) {
                addWarn("Unable to close log session channel", failure);
            }
            started = false;
        }
    }
}
