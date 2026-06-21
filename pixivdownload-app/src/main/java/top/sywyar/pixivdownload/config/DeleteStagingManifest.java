package top.sywyar.pixivdownload.config;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
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
 * 静态运行期路径里被调用（见 {@link RuntimeFiles#recoverDeleteStagingLeftovers()}）。日志走与 {@link RuntimeFiles}
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

    private DeleteStagingManifest() {
    }

    /** 一条恢复记录：原文件的绝对路径 + 它在暂存子目录内的副本文件名。 */
    public record Entry(Path originalFile, String stagedFileName) {
    }

    /**
     * 写出恢复清单（覆盖既有）。由原子删除在「复制原文件之前」调用：清单先于删除落盘，崩溃后启动方能据此恢复。
     */
    public static void write(Path stagingDir, List<Entry> entries) throws IOException {
        Properties props = new Properties();
        props.setProperty(VERSION_KEY, Integer.toString(VERSION));
        props.setProperty(COUNT_KEY, Integer.toString(entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            props.setProperty(i + ORIGINAL_SUFFIX, entry.originalFile().toString());
            props.setProperty(i + STAGED_SUFFIX, entry.stagedFileName());
        }
        Path manifest = stagingDir.resolve(MANIFEST_FILE_NAME);
        try (Writer writer = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8)) {
            props.store(writer, "PixivDownload delete-staging recovery manifest");
        }
    }

    /**
     * 启动恢复入口：遍历暂存根目录下每个操作子目录，按其恢复清单把「仍缺失的原文件」从暂存复制回原位。
     * 某子目录全部原文件就位后才删除该子目录；清单缺失 / 损坏 / 任一恢复失败时<b>保留</b>子目录并记日志，供人工恢复。
     * 顶层非目录残留一律不动（保守，不再无条件清扫，避免误删未知文件）。
     */
    public static void recoverLeftovers(Path stagingRoot) {
        if (stagingRoot == null || !Files.isDirectory(stagingRoot)) {
            return;
        }
        List<Path> subdirectories;
        try (Stream<Path> children = Files.list(stagingRoot)) {
            subdirectories = children.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            log.warn(MessageBundles.get("runtime.log.delete-staging.scan-failed", stagingRoot));
            return;
        }
        for (Path subdirectory : subdirectories) {
            recoverSubdirectory(subdirectory);
        }
    }

    private static void recoverSubdirectory(Path subdirectory) {
        Optional<List<Entry>> entries = read(subdirectory);
        if (entries.isEmpty()) {
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
        deleteDirectoryTree(subdirectory);
        log.info(MessageBundles.get("runtime.log.delete-staging.recovered", subdirectory));
    }

    /**
     * 若原文件仍缺失则从暂存副本复制回原位（原目录可能已随删除被移除，按需重建）。
     *
     * @return 该条目是否已就位：原文件本来就在（未删 / 已被进程内回滚复原，不覆盖），或这次复制成功
     */
    private static boolean restoreIfMissing(Path subdirectory, Entry entry) {
        Path original = entry.originalFile();
        if (Files.exists(original)) {
            return true;
        }
        Path staged = subdirectory.resolve(entry.stagedFileName());
        if (!Files.isRegularFile(staged)) {
            // 原文件已删且暂存副本也不可用：这一份无法恢复，记 error 并据此保留子目录。
            log.error(MessageBundles.get("runtime.log.delete-staging.staged-missing", original, staged));
            return false;
        }
        try {
            Path parent = original.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(staged, original,
                    StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            log.info(MessageBundles.get("runtime.log.delete-staging.restored", original));
            return true;
        } catch (IOException e) {
            log.error(MessageBundles.get("runtime.log.delete-staging.restore-failed", original, subdirectory));
            return false;
        }
    }

    /**
     * 读取并校验恢复清单。文件缺失 / 解析失败 / 版本不符 / 条目不完整时一律返回空（调用方据此保留子目录）。
     */
    static Optional<List<Entry>> read(Path stagingDir) {
        Path manifest = stagingDir.resolve(MANIFEST_FILE_NAME);
        if (!Files.isRegularFile(manifest)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
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
        if (count < 0) {
            return Optional.empty();
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String original = props.getProperty(i + ORIGINAL_SUFFIX);
            String staged = props.getProperty(i + STAGED_SUFFIX);
            if (original == null || original.isBlank() || staged == null || staged.isBlank()
                    || isUnsafeStagedName(stagingDir, staged)) {
                return Optional.empty();
            }
            try {
                entries.add(new Entry(Paths.get(original), staged));
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

    /** 删除整个暂存子目录树（含恢复清单）；仅在该子目录全部原文件就位后调用。删失败仅记小日志、忽略。 */
    private static void deleteDirectoryTree(Path directory) {
        try (Stream<Path> tree = Files.walk(directory)) {
            tree.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 单个条目删不掉时继续清理其余
                }
            });
        } catch (IOException e) {
            log.warn(MessageBundles.get("runtime.log.delete-staging.cleanup-failed", directory));
        }
    }
}
