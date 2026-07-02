package top.sywyar.pixivdownload.core.asset;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.DeleteStagingManifest;
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
 * 原子文件删除：把一组待删文件先复制到 {@code data/delete-staging/} 下的独立子目录（暂存，并写一份恢复清单
 * {@code manifest.properties} 记录每个原文件路径与暂存副本名），再逐个删除原文件；任一删除失败就从暂存把已删掉的
 * 原文件复制回原位（回滚），使删除对调用方是「要么全删、要么全保留」，不再留下部分删除的中间态（删除失败时作品
 * 不会半损坏 / 裂图）。写清单 / 复制阶段（删任何原文件之前）失败同样视为删除失败，此时只需清掉暂存即等于回滚。
 *
 * <p>删除全部成功、或删除失败且回滚<b>完全</b>成功时，删掉暂存子目录（删暂存失败仅记小日志、忽略）。
 * 回滚中任一文件复制失败时<b>保留</b>暂存子目录（含恢复清单）作为最后备份、绝不清理——这些原文件已删却未能复原，
 * 只能由 {@link DeleteStagingManifest#recoverLeftovers 启动恢复} 或人工据清单恢复。同理，进程在删除中途崩溃时
 * 暂存子目录也会留存，由启动恢复据清单复原。
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
            List<DeleteStagingManifest.Entry> manifestEntries = new ArrayList<>(targets.size());
            int index = 0;
            for (Path original : targets) {
                String stagedName = index + "_" + original.getFileName();
                stagedByOriginal.put(original, stagingDir.resolve(stagedName));
                manifestEntries.add(new DeleteStagingManifest.Entry(
                        original.toAbsolutePath().normalize(), stagedName));
                index++;
            }
            // 先写恢复清单再复制原文件：进程在删除中途崩溃时，下次启动可据清单把已删原文件从暂存复制回原位。
            DeleteStagingManifest.write(stagingDir, manifestEntries);
            for (Map.Entry<Path, Path> staged : stagedByOriginal.entrySet()) {
                Files.copy(staged.getKey(), staged.getValue(), StandardCopyOption.COPY_ATTRIBUTES);
            }
        } catch (IOException e) {
            // 写清单 / 复制阶段失败：尚未删除任何原文件，回滚 = 仅清理暂存
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
                if (rollback(deleted, stagedByOriginal, stagingDir)) {
                    cleanStaging(stagingDir);
                } else {
                    // 回滚未完全成功：保留暂存子目录（含恢复清单）作为最后备份，绝不清理；交由启动恢复 / 人工据清单恢复。
                    log.error(messages.getForLog("download.delete.log.staging-retained", stagingDir));
                }
                return false;
            }
        }

        cleanStaging(stagingDir);
        return true;
    }

    /** 删除单个原文件。protected 仅供测试注入删除失败（不要在生产代码中改写其语义）。 */
    protected void deleteFile(Path original) throws IOException {
        Files.deleteIfExists(original);
    }

    /** 把暂存副本复制回原位（回滚单个原文件）。protected 仅供测试注入回滚复制失败（不要在生产代码中改写其语义）。 */
    protected void restoreFile(Path staged, Path original) throws IOException {
        Files.copy(staged, original,
                StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 把已删掉的原文件从暂存逐个复制回原位。回滚复制失败是真正的数据风险点（原文件已删又无法复原），
     * 记 error 并附原文件路径与暂存目录供人工恢复。
     *
     * @return {@code true} 表示全部已删原文件都复原成功；{@code false} 表示至少有一个复原失败，
     *         调用方必须<b>保留</b>暂存子目录（含恢复清单）、不可清理
     */
    private boolean rollback(List<Path> deleted, Map<Path, Path> stagedByOriginal, Path stagingDir) {
        boolean fullyRestored = true;
        for (Path original : deleted) {
            try {
                restoreFile(stagedByOriginal.get(original), original);
            } catch (IOException e) {
                log.error(messages.getForLog("download.delete.log.rollback-failed", original, stagingDir));
                fullyRestored = false;
            }
        }
        return fullyRestored;
    }

    /** 删除暂存子目录树；仅在删除全部成功、或回滚全部成功后调用。删失败仅记小日志、忽略（不影响删除结果）。 */
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
