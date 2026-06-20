package top.sywyar.pixivdownload.download;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 原子文件删除：把一组待删文件先复制到 {@code data/delete-staging/} 下的独立子目录（暂存），再逐个删除原文件；
 * 任一删除失败就从暂存把已删掉的原文件复制回原位（回滚），使删除对调用方是「要么全删、要么全保留」，不再留下
 * 部分删除的中间态（删除失败时作品不会半损坏 / 裂图）。复制阶段（删任何原文件之前）失败同样视为删除失败，
 * 此时只需清掉暂存即等于回滚。删除全部成功后删掉暂存子目录（删暂存失败仅记小日志、忽略）。
 *
 * <p>无状态、线程安全：每次 {@link #deleteAtomically} 使用独立的暂存子目录，彼此不干扰。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StagedFileDeletion {

    private final AppMessages messages;

    /**
     * 原子删除给定文件集合（仅处理磁盘上确实存在的常规文件，{@code null} / 缺失 / 非常规文件忽略，重复路径去重）。
     *
     * @return {@code true} 表示全部删除成功（或集合中没有需要删除的文件）；{@code false} 表示有文件删除失败、
     *         已回滚到删除前状态（原文件复原），调用方应据此中止后续清理（如软删数据库）
     */
    public boolean deleteAtomically(Collection<Path> files) {
        List<Path> targets = existingRegularFiles(files);
        if (targets.isEmpty()) {
            return true;
        }

        Path stagingDir = RuntimeFiles.deleteStagingDirectory().resolve(UUID.randomUUID().toString());
        Map<Path, Path> stagedByOriginal = new LinkedHashMap<>();
        try {
            Files.createDirectories(stagingDir);
            int index = 0;
            for (Path original : targets) {
                Path staged = stagingDir.resolve(index + "_" + original.getFileName());
                Files.copy(original, staged, StandardCopyOption.COPY_ATTRIBUTES);
                stagedByOriginal.put(original, staged);
                index++;
            }
        } catch (IOException e) {
            // 复制阶段失败：尚未删除任何原文件，回滚 = 仅清理暂存
            log.warn(messages.getForLog("download.delete.log.stage-failed", e.getMessage()));
            cleanStaging(stagingDir);
            return false;
        }

        List<Path> deleted = new ArrayList<>(targets.size());
        for (Path original : targets) {
            try {
                deleteFile(original);
                deleted.add(original);
            } catch (IOException e) {
                log.warn(messages.getForLog("download.delete.log.delete-failed", original));
                rollback(deleted, stagedByOriginal);
                cleanStaging(stagingDir);
                return false;
            }
        }

        cleanStaging(stagingDir);
        return true;
    }

    /** 删除单个原文件。包内可见，仅供测试注入删除失败（不要在生产代码中改写其语义）。 */
    void deleteFile(Path original) throws IOException {
        Files.deleteIfExists(original);
    }

    /** 把已删掉的原文件从暂存复制回原位。回滚复制失败是真正的数据风险点：原文件已删又无法复原，记 error。 */
    private void rollback(List<Path> deleted, Map<Path, Path> stagedByOriginal) {
        for (Path original : deleted) {
            Path staged = stagedByOriginal.get(original);
            try {
                Files.copy(staged, original,
                        StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.error(messages.getForLog("download.delete.log.rollback-failed", original));
            }
        }
    }

    /** 删除暂存子目录树；暂存是可再生的临时区，删失败仅记小日志、忽略（不影响删除结果）。 */
    private void cleanStaging(Path stagingDir) {
        try (Stream<Path> tree = Files.walk(stagingDir)) {
            tree.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 单个暂存条目删不掉时继续清理其余
                }
            });
        } catch (IOException e) {
            log.warn(messages.getForLog("download.delete.log.staging-cleanup-failed", stagingDir));
        }
    }

    private static List<Path> existingRegularFiles(Collection<Path> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<Path> targets = new ArrayList<>(files.size());
        LinkedHashSet<Path> seen = new LinkedHashSet<>();
        for (Path path : files) {
            if (path == null) {
                continue;
            }
            if (seen.add(path.toAbsolutePath().normalize()) && Files.isRegularFile(path)) {
                targets.add(path);
            }
        }
        return targets;
    }
}
