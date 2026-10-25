package top.sywyar.pixivdownload.novel.narration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.asset.FileTransactionService;
import top.sywyar.pixivdownload.core.database.DatabaseAvailability;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** 参考音的角色级互斥与暂存；文件和主库记录的提交、回滚及恢复由宿主统一负责。 */
@Slf4j
@Component
public class NarrationReferenceVoiceStore {

    private static final List<String> REF_EXTENSIONS = List.of("wav", "mp3", "pcm");

    private final NarrationReferenceVoicePaths paths;
    private final DatabaseAvailability database;
    private final FileTransactionService files;
    private final ConcurrentMap<String, VoiceLock> locks = new ConcurrentHashMap<>();

    private static final class VoiceLock {
        int references;
    }

    public NarrationReferenceVoiceStore(NarrationReferenceVoicePaths paths, DatabaseAvailability database,
                                       FileTransactionService files) {
        this.paths = paths;
        this.database = database;
        this.files = files;
    }

    <T> T withLock(long castId, int characterId, Supplier<T> action) {
        String key = castId + ":" + characterId;
        VoiceLock lock = locks.compute(key, (ignored, current) -> {
            VoiceLock value = current == null ? new VoiceLock() : current;
            value.references++;
            return value;
        });
        try {
            synchronized (lock) {
                return action.get();
            }
        } finally {
            locks.computeIfPresent(key, (ignored, current) -> --current.references == 0 ? null : current);
        }
    }

    /** 暂存新音频；发布、旧扩展名删除及记录回调在同一文件事务中完成。 */
    void write(long castId, int characterId, byte[] data, String ext, Runnable commitRecord) {
        database.requireAvailable();
        Path target = paths.file(castId, characterId, ext);
        Path temporary = target.resolveSibling(characterId + ".reference.part");
        boolean staged = false;
        try {
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unsafe narration reference temporary file");
            }
            // 角色锁内复用固定暂存名，重启后的下一次写入回收中断残留。
            Files.deleteIfExists(temporary);
            try (var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                staged = true;
                output.write(data);
            }
            database.requireAvailable();
            files.changeFiles(Map.of(target, temporary), characterFiles(castId, characterId).stream()
                    .filter(file -> !file.equals(target)).toList(), commitRecord);
        } catch (IOException failure) {
            throw new UncheckedIOException("Write narration reference voice failed", failure);
        } finally {
            if (staged) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException failure) {
                    log.warn("Delete narration reference temporary file failed: {}", temporary, failure);
                }
            }
        }
    }

    /** 删除失败时保留文件与记录；不存在的角色文件不影响幂等删除。 */
    void deleteCharacterFiles(long castId, int characterId, Runnable commitRecord) {
        database.requireAvailable();
        try {
            files.changeFiles(Map.of(), characterFiles(castId, characterId), commitRecord);
        } catch (IOException failure) {
            throw new UncheckedIOException("Delete narration reference voice failed", failure);
        }
    }

    private List<Path> characterFiles(long castId, int characterId) {
        return REF_EXTENSIONS.stream().map(ext -> paths.file(castId, characterId, ext)).toList();
    }
}
