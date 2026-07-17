package top.sywyar.pixivdownload.novel.narration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.RuntimePathProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * 多角色朗读「参考音 / 标准音」的<b>磁盘存储</b>（{@code data/narration-voice/{castId}/{characterId}.{ext}}）。
 * 把所有落盘 / 清理 / 并发保护收敛于此，供 {@link NarrationReferenceVoiceService}（写入 / 删除单个角色）与
 * {@link NovelNarrationCastService}（整册替换删除被移除角色、删除整册目录）共用，避免文件操作散落、产生孤儿文件。
 *
 * <p>一致性保护：
 * <ul>
 *   <li><b>按 {@code (castId, characterId)} 串行</b>——{@link #lockFor} 返回稳定的每键监视器，调用方在其上
 *       {@code synchronized} 把「校验角色行 → 写文件 → 写库 → 推进缓存键」整段串起来，规避同角色并发上传 /
 *       生成 / 删除互相破坏引用。</li>
 *   <li><b>原子写入</b>——先写临时文件再 {@code move}（优先 {@code ATOMIC_MOVE}，平台不支持时回退 {@code REPLACE_EXISTING}），
 *       避免读者读到半截文件；并清掉同角色其它扩展名的旧文件（如 wav→mp3 切换），避免一角色多份残留。</li>
 * </ul>
 */
@Slf4j
@Component
public class NarrationReferenceVoiceStore {

    /** 受支持的参考音扩展名（清理时逐一尝试删除）。 */
    private static final List<String> REF_EXTENSIONS = List.of("wav", "mp3", "pcm");

    private final RuntimePathProvider runtimePathProvider;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public NarrationReferenceVoiceStore(RuntimePathProvider runtimePathProvider) {
        this.runtimePathProvider = runtimePathProvider;
    }

    /** 取某角色文件操作的串行锁（稳定的每键监视器）；调用方在其上 {@code synchronized} 串起文件 + DB 变更。 */
    public Object lockFor(long castId, int characterId) {
        return locks.computeIfAbsent(castId + ":" + characterId, k -> new Object());
    }

    /**
     * 原子写入某角色参考音字节：写临时文件 → move 覆盖目标 → 清理同角色其它扩展名旧文件。失败抛
     * {@link UncheckedIOException}（不会留下半截目标文件）。
     */
    public void write(long castId, int characterId, byte[] data, String ext) {
        Path target = runtimePathProvider.narrationVoiceFile(castId, characterId, ext);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(tmp, data);
            moveReplace(tmp, target);
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new UncheckedIOException("write narration reference voice failed: " + target, e);
        }
        deleteOtherExtensions(castId, characterId, ext);
    }

    /** 删除某角色的全部扩展名参考音文件（最大努力，绝不抛出）。 */
    public void deleteCharacterFiles(long castId, int characterId) {
        for (String e : REF_EXTENSIONS) {
            deleteQuietly(runtimePathProvider.narrationVoiceFile(castId, characterId, e));
        }
    }

    /** 删除整册参考音目录 {@code data/narration-voice/{castId}/}（删除花名册时；最大努力，绝不抛出）。 */
    public void deleteCastDirectory(long castId) {
        Path dir = runtimePathProvider.narrationVoiceDirectory(castId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException e) {
            log.warn("delete narration reference voice cast directory failed: {} ({})", dir, e.getMessage());
        }
    }

    private void deleteOtherExtensions(long castId, int characterId, String keepExt) {
        String keep = keepExt == null ? "" : keepExt.trim().toLowerCase(Locale.ROOT);
        for (String e : REF_EXTENSIONS) {
            if (e.equals(keep)) {
                continue;
            }
            deleteQuietly(runtimePathProvider.narrationVoiceFile(castId, characterId, e));
        }
    }

    private static void moveReplace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            // 部分平台（含 Windows 覆盖已存在文件）不支持原子移动，回退为非原子覆盖。
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("delete narration reference voice file failed: {} ({})", file, e.getMessage());
        }
    }
}
