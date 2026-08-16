package top.sywyar.pixivdownload.config;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 作品删除暂存区（{@code data/delete-staging/<操作 id>/}）的<b>恢复清单</b>读写与<b>启动恢复</b>。
 *
 * <p>原子删除在删除任何原文件之前，会先把「每个原文件的绝对路径 + 它在暂存子目录里的副本文件名」写进
 * 该子目录下的 {@value #MANIFEST_FILE_NAME}。这样即使进程在「已暂存、已删部分原文件、尚未完成回滚或软删」
 * 之间崩溃，下次启动也能据清单把仍缺失的原文件从暂存复制回原位、把这次中断的删除回滚掉，而不会误删唯一备份、
 * 把半删除状态永久化。
 *
 * <p>纯 JDK 实现（{@link Properties} + UTF-8），不引入 Spring / Jackson —— 因为它要在 Spring 上下文启动之前的
 * 静态运行期路径里被调用（见 {@link RuntimeFiles#recoverDeleteStagingLeftovers(String)}）。日志走与 {@link RuntimeFiles}
 * 一致的静态 {@link MessageBundles}（启动期、可能无请求上下文）。
 */
@Slf4j
public final class DeleteStagingManifest {

    /** 暂存子目录内的恢复清单文件名（{@link Properties} 文本格式，UTF-8）。 */
    static final String MANIFEST_FILE_NAME = "manifest.properties";

    private static final String VERSION_KEY = "version";
    private static final String COUNT_KEY = "count";
    private static final String ORIGINAL_SUFFIX = ".original";
    private static final String STAGED_SUFFIX = ".staged";
    private static final int VERSION = 1;
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    static final int MAX_ENTRIES = 10_000;
    static final int MAX_ORIGINAL_PATH_CHARS = 32_768;
    static final int MAX_STAGED_FILE_NAME_CHARS = 255;

    private DeleteStagingManifest() {
    }

    /** 一条恢复记录：原文件的绝对路径 + 它在暂存子目录内的副本文件名。 */
    public record Entry(Path originalFile, String stagedFileName) {
    }

    /**
     * 写出恢复清单（覆盖既有）。由原子删除在「复制原文件之前」调用：清单先于删除落盘，崩溃后启动方能据此恢复。
     */
    public static void write(Path stagingDir, List<Entry> entries) throws IOException {
        if (entries == null || entries.isEmpty()) {
            throw new IOException("delete-staging manifest has no entries");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new IOException("delete-staging manifest has too many entries");
        }
        Properties props = new Properties();
        props.setProperty(VERSION_KEY, Integer.toString(VERSION));
        props.setProperty(COUNT_KEY, Integer.toString(entries.size()));
        Set<Path> originals = new HashSet<>();
        Set<String> stagedNames = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry == null) {
                throw new IOException("delete-staging manifest entry is missing");
            }
            Path original = entry.originalFile();
            String staged = entry.stagedFileName();
            if (original == null || !original.isAbsolute()
                    || original.toString().length() > MAX_ORIGINAL_PATH_CHARS) {
                throw new IOException("delete-staging original path must be absolute");
            }
            Path normalizedOriginal = original.normalize();
            if (staged == null || staged.isBlank()
                    || staged.length() > MAX_STAGED_FILE_NAME_CHARS
                    || isUnsafeStagedName(stagingDir, staged)) {
                throw new IOException("delete-staging staged file name is unsafe");
            }
            if (!originals.add(normalizedOriginal) || !stagedNames.add(staged)) {
                throw new IOException("delete-staging manifest contains duplicate entries");
            }
            props.setProperty(i + ORIGINAL_SUFFIX, normalizedOriginal.toString());
            props.setProperty(i + STAGED_SUFFIX, staged);
        }
        Path manifest = stagingDir.resolve(MANIFEST_FILE_NAME);
        PlainFilePathGuard.requirePlainParent(manifest, false);
        if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)
                && !PlainFilePathGuard.isPlainRegularFile(manifest)) {
            throw new IOException("delete-staging manifest path is unsafe");
        }
        try (Writer writer = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8)) {
            props.store(writer, "PixivDownload delete-staging recovery manifest");
        }
        if (Files.size(manifest) > MAX_MANIFEST_BYTES) {
            Files.deleteIfExists(manifest);
            throw new IOException("delete-staging manifest is too large");
        }
    }

    /**
     * 启动恢复入口：遍历暂存根目录下每个操作子目录，按其恢复清单把「仍缺失的原文件」从暂存复制回原位。
     * 某子目录全部原文件就位后才删除该子目录；清单缺失 / 损坏 / 任一恢复失败时<b>保留</b>子目录并记日志，供人工恢复。
     * 顶层非目录残留一律不动（保守，不再无条件清扫，避免误删未知文件）。
     */
    public static void recoverLeftovers(Path stagingRoot, Collection<Path> allowedRoots) {
        if (stagingRoot == null || !PlainFilePathGuard.isPlainDirectory(stagingRoot)) {
            return;
        }
        List<Path> normalizedAllowedRoots = normalizeAllowedRoots(allowedRoots);
        List<Path> subdirectories;
        try (Stream<Path> children = Files.list(stagingRoot)) {
            subdirectories = children.filter(PlainFilePathGuard::isPlainDirectory).toList();
        } catch (IOException e) {
            log.warn(MessageBundles.get("runtime.log.delete-staging.scan-failed", stagingRoot));
            return;
        }
        for (Path subdirectory : subdirectories) {
            recoverSubdirectory(subdirectory, normalizedAllowedRoots);
        }
    }

    private static void recoverSubdirectory(Path subdirectory, List<Path> allowedRoots) {
        Optional<List<Entry>> entries = read(subdirectory);
        if (entries.isEmpty() || entries.get().stream()
                .anyMatch(entry -> !isWithinAllowedRoot(entry.originalFile(), allowedRoots))) {
            // 清单缺失 / 损坏：无法确定哪些原文件已被删，保守保留整个子目录（含唯一备份）供人工恢复。
            log.warn(MessageBundles.get("runtime.log.delete-staging.manifest-unreadable", subdirectory));
            return;
        }
        boolean fullyRecovered = true;
        for (Entry entry : entries.get()) {
            if (!restoreIfMissing(subdirectory, entry)) {
                fullyRecovered = false;
            }
        }
        if (!fullyRecovered) {
            log.warn(MessageBundles.get("runtime.log.delete-staging.recovery-incomplete", subdirectory));
            return;
        }
        if (cleanRecoveredSubdirectory(subdirectory, entries.get())) {
            log.info(MessageBundles.get("runtime.log.delete-staging.recovered", subdirectory));
        } else {
            log.warn(MessageBundles.get("runtime.log.delete-staging.cleanup-failed", subdirectory));
        }
    }

    private static List<Path> normalizeAllowedRoots(Collection<Path> allowedRoots) {
        if (allowedRoots == null || allowedRoots.isEmpty()) {
            return List.of();
        }
        return allowedRoots.stream()
                .filter(root -> root != null)
                .map(root -> root.toAbsolutePath().normalize())
                .filter(root -> root.getParent() != null)
                .distinct()
                .toList();
    }

    private static boolean isWithinAllowedRoot(Path path, List<Path> allowedRoots) {
        return allowedRoots.stream().anyMatch(path::startsWith);
    }

    /**
     * 若原文件仍缺失则从暂存副本复制回原位（原目录可能已随删除被移除，按需重建）。
     *
     * @return 该条目是否已就位：原文件本来就在（未删 / 已被进程内回滚复原，不覆盖），或这次复制成功
     */
    private static boolean restoreIfMissing(Path subdirectory, Entry entry) {
        Path original = entry.originalFile();
        Path staged = subdirectory.resolve(entry.stagedFileName());
        if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
            try {
                return PlainFilePathGuard.isPlainRegularFile(original)
                        && PlainFilePathGuard.isPlainRegularFile(staged)
                        && Files.mismatch(staged, original) == -1L;
            } catch (IOException e) {
                return false;
            }
        }
        if (!PlainFilePathGuard.isPlainRegularFile(staged)) {
            // 原文件已删且暂存副本也不可用：这一份无法恢复，记 error 并据此保留子目录。
            log.error(MessageBundles.get("runtime.log.delete-staging.staged-missing", original, staged));
            return false;
        }
        try {
            Path parent = original.getParent();
            if (parent != null) {
                PlainFilePathGuard.requirePlainParent(original, true);
            }
            copyRestoredFile(staged, original);
            PlainFilePathGuard.requirePlainRegularFile(original);
            log.info(MessageBundles.get("runtime.log.delete-staging.restored", original));
            return true;
        } catch (IOException e) {
            log.error(MessageBundles.get("runtime.log.delete-staging.restore-failed", original, subdirectory));
            return false;
        }
    }

    static void copyRestoredFile(Path staged, Path original) throws IOException {
        Files.copy(staged, original,
                StandardCopyOption.COPY_ATTRIBUTES,
                LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * 读取并校验恢复清单。文件缺失 / 解析失败 / 版本不符 / 条目不完整时一律返回空（调用方据此保留子目录）。
     */
    static Optional<List<Entry>> read(Path stagingDir) {
        Path manifest = stagingDir.resolve(MANIFEST_FILE_NAME);
        if (!PlainFilePathGuard.isPlainRegularFile(manifest)) {
            return Optional.empty();
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(manifest, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (bytes.length > MAX_MANIFEST_BYTES) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!Integer.toString(VERSION).equals(props.getProperty(VERSION_KEY))) {
            return Optional.empty();
        }
        int count;
        try {
            count = Integer.parseInt(props.getProperty(COUNT_KEY, ""));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (count <= 0 || count > MAX_ENTRIES || props.size() != 2 + count * 2) {
            return Optional.empty();
        }
        List<Entry> entries = new ArrayList<>(count);
        Set<Path> originals = new HashSet<>();
        Set<String> stagedNames = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String original = props.getProperty(i + ORIGINAL_SUFFIX);
            String staged = props.getProperty(i + STAGED_SUFFIX);
            if (original == null || original.isBlank() || staged == null || staged.isBlank()
                    || original.length() > MAX_ORIGINAL_PATH_CHARS
                    || staged.length() > MAX_STAGED_FILE_NAME_CHARS
                    || isUnsafeStagedName(stagingDir, staged)) {
                return Optional.empty();
            }
            try {
                Path originalPath = Paths.get(original);
                if (!originalPath.isAbsolute()) {
                    return Optional.empty();
                }
                Path normalizedOriginal = originalPath.normalize();
                if (!originals.add(normalizedOriginal) || !stagedNames.add(staged)) {
                    return Optional.empty();
                }
                entries.add(new Entry(normalizedOriginal, staged));
            } catch (InvalidPathException e) {
                return Optional.empty();
            }
        }
        return Optional.of(entries);
    }

    /**
     * 暂存副本名必须是暂存子目录内的<b>单个普通文件名</b>。恢复时它是复制的<b>来源</b>
     * （{@code stagingDir.resolve(stagedName)}），生产写入的名称（{@code index_文件名}）天然满足；
     * 但损坏 / 手改的清单可能塞入绝对路径、含路径分隔符或 {@code ..} 逃逸的值，信任它会读到子目录之外的任意文件。
     * 凡不是「子目录内单个文件名」的一律视为清单损坏。
     */
    private static boolean isUnsafeStagedName(Path stagingDir, String stagedName) {
        if (stagedName.indexOf('/') >= 0 || stagedName.indexOf('\\') >= 0) {
            return true;
        }
        Path candidate;
        try {
            candidate = Paths.get(stagedName);
        } catch (InvalidPathException e) {
            return true;
        }
        if (candidate.isAbsolute() || candidate.getNameCount() != 1) {
            return true;
        }
        String single = candidate.getFileName().toString();
        if (single.equals(".") || single.equals("..")) {
            return true;
        }
        Path parent = stagingDir.resolve(candidate).normalize().getParent();
        return parent == null || !parent.equals(stagingDir.normalize());
    }

    /** 只清理清单声明的暂存副本；存在未知条目时保留整个目录供人工恢复。 */
    private static boolean cleanRecoveredSubdirectory(Path directory, List<Entry> entries) {
        if (!PlainFilePathGuard.isPlainDirectory(directory)) {
            return false;
        }
        Path manifest = directory.resolve(MANIFEST_FILE_NAME);
        Set<Path> expected = new HashSet<>();
        expected.add(manifest.normalize());
        for (Entry entry : entries) {
            expected.add(directory.resolve(entry.stagedFileName()).normalize());
        }
        try (Stream<Path> children = Files.list(directory)) {
            if (children.map(Path::normalize).anyMatch(child -> !expected.contains(child))) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        if (!PlainFilePathGuard.isPlainRegularFile(manifest)) {
            return false;
        }
        for (Entry entry : entries) {
            Path staged = directory.resolve(entry.stagedFileName());
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)
                    && !PlainFilePathGuard.isPlainRegularFile(staged)) {
                return false;
            }
        }
        try {
            for (Entry entry : entries) {
                Files.deleteIfExists(directory.resolve(entry.stagedFileName()));
            }
            Files.delete(manifest);
            Files.delete(directory);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
