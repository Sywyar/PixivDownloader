package top.sywyar.pixivdownload.download;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.FileSystemUtils;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadCompletion;
import top.sywyar.pixivdownload.core.artwork.download.PartialArtwork.PageRepair;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.FileTransactionService;
import top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException;
import top.sywyar.pixivdownload.core.work.service.DownloadPathGuard;
import top.sywyar.pixivdownload.download.media.ImageOutputService.MediaSink;
import top.sywyar.pixivdownload.download.web.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Keeps downloads private until a resumed page or a complete replacement and its records can be published together. */
final class ArtworkRebuild implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ArtworkRebuild.class);
    private final Map<Integer, ArtworkMediaManifest> media = new ConcurrentHashMap<>();
    private final Map<Integer, Path> sources = new ConcurrentHashMap<>();
    private Path initialFolder;
    private FileTransactionService sourceTransactions;
    private Set<String> initialFormats = Set.of();
    private boolean recording;
    private Path frameArchive;
    private volatile boolean preserveScratch;
    private Path directory;

    // The caller holds the artwork file lock through cleanup. A retry can discard interrupted scratch files.
    Path start(RuntimePathProvider paths, long artworkId) throws IOException {
        Path root = paths.dataDirectory().resolve("artwork-rebuild");
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid artwork staging root");
        Path target = root.resolve(Long.toString(artworkId));
        FileSystemUtils.deleteRecursively(target);
        directory = Files.createDirectory(target);
        return directory;
    }

    MediaSink wholeArtwork() {
        return staging((page, stem, manifest) -> media.put(page, manifest), false);
    }

    MediaSink initialPage(Path folder, String base) {
        boolean occupied = !Files.notExists(folder.resolve(base + "_thumb.jpg"), LinkOption.NOFOLLOW_LINKS);
        return staging((page, stem, manifest) -> media.put(page, publishableMedia(manifest, occupied)), true);
    }

    void stageFrameArchive(Path stem, Path archive, Path timing) throws IOException {
        Path pair = Files.createTempDirectory(directory, ".frames-");
        try {
            Files.move(archive, pair.resolve("frames.zip"));
            Files.move(timing, pair.resolve("frames.properties"));
        } catch (IOException failure) {
            discard(pair);
            throw failure;
        }
        if (frameArchive != null) discard(frameArchive);
        frameArchive = pair;
    }

    void initialSources(Path folder, FileTransactionService transactions, Set<String> formats) {
        initialFolder = folder;
        sourceTransactions = transactions;
        initialFormats = Set.copyOf(formats);
    }

    void downloadedSource(int page, Path stem, String extension) {
        sources.put(page, stem.resolveSibling(stem.getFileName() + "." + extension));
    }

    Path pageStem(int page, String base) throws IOException {
        return Files.createDirectory(directory.resolve(Integer.toString(page))).resolve(base);
    }

    private static MediaSink staging(MediaSink save, boolean keepSource) {
        return new MediaSink() {
            @Override public boolean keepStagedSource() { return keepSource; }

            @Override public void save(int page, Path stem, ArtworkMediaManifest manifest) throws IOException {
                save.save(page, stem, manifest);
            }

            @Override public void publishAndSave(FileTransactionService transactions, Map<Path, Path> newFiles, int page,
                                                 Path stem, ArtworkMediaManifest manifest, BooleanSupplier cancelled) throws IOException {
                checkCancelled(cancelled);
                for (var file : newFiles.entrySet()) Files.move(file.getValue(), file.getKey());
                save(page, stem, manifest);
            }
        };
    }

    MediaSink partialPage(FileTransactionService transactions, DownloadPathGuard paths, long artworkId, PageRepair repair, Path folder, MediaSink save,
                          BooleanSupplier cancelled) {
        return staging((page, stem, generated) -> {
            var manifest = publishableMedia(generated, repair.preserveThumbnail());
            var replacements = new LinkedHashMap<Path, Path>();
            addOutputs(replacements, folder, stem, manifest);
            var obsolete = new HashSet<>(repair.verifiedFiles());
            obsolete.removeAll(replacements.keySet());
            var expected = new LinkedHashMap<>(repair.observedHashes());
            expected.keySet().removeIf(path -> !replacements.containsKey(path) && !obsolete.contains(path));
            var createOnly = new HashSet<>(replacements.keySet());
            createOnly.removeAll(expected.keySet());
            checkCancelled(cancelled);
            try (var reservation = reserve(paths, artworkId, replacements, obsolete)) {
                changeFiles(transactions, replacements, obsolete, createOnly, expected, () -> {
                    checkCancelled(cancelled);
                    // 指纹取本次私有产物，正式目录上的并发改写不能成为新登记的摘要。
                    try { save.save(page, stem, manifest); }
                    catch (IOException failure) { throw new UncheckedIOException(failure); }
                    checkCancelled(cancelled);
                });
            } catch (LocalizedException occupied) {
                sources.remove(page);
                throw occupied;
            }
            if (sources.remove(page) != null) forgetOriginal(page);
        }, false);
    }

    private static ArtworkMediaManifest publishableMedia(ArtworkMediaManifest generated, boolean preserveThumbnail) {
        return generated.thumbnailOwned() && preserveThumbnail
                ? new ArtworkMediaManifest(generated.originalExtension(), generated.extensions(), generated.originalRetained(), false)
                : generated;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static void addOutputs(Map<Path, Path> replacements, Path folder, Path stem, ArtworkMediaManifest manifest) {
        String base = stem.getFileName().toString();
        for (String extension : manifest.extensions()) {
            String name = base + "." + extension;
            replacements.put(folder.resolve(name), stem.resolveSibling(name));
        }
        if (manifest.thumbnailOwned()) replacements.put(folder.resolve(base + "_thumb.jpg"), stem.resolveSibling(base + "_thumb.jpg"));
        if (manifest.extensions().contains("zip")) replacements.put(folder.resolve(base + ".frames.properties"), stem.resolveSibling(base + ".frames.properties"));
    }

    void publish(FileTransactionService transactions, DownloadPathGuard paths, Set<Path> previousFiles,
                 Map<Path, String> expectedHashes, ArtworkDownloadCompletion completion, MediaSink saveMedia,
                 Runnable commitRecord) throws IOException {
        Map<Path, Path> replacements = new LinkedHashMap<>();
        for (int page = 0; page < completion.imageCount(); page++) {
            var manifest = media.get(page);
            if (manifest == null) throw new IOException("Missing staged artwork page " + page);
            addOutputs(replacements, completion.folder(), directory.resolve(completion.fileNames().get(page)), manifest);
        }
        if (frameArchive != null) {
            String base = completion.fileNames().getFirst();
            replacements.put(completion.folder().resolve(base + ".zip"), frameArchive.resolve("frames.zip"));
            replacements.put(completion.folder().resolve(base + ".frames.properties"), frameArchive.resolve("frames.properties"));
        }
        var obsolete = new HashSet<>(previousFiles);
        obsolete.removeAll(replacements.keySet());
        var createOnly = new HashSet<>(replacements.keySet());
        createOnly.removeAll(previousFiles);
        try (var reservation = reserve(paths, completion.artworkId(), replacements, obsolete)) {
            changeFiles(transactions, replacements, obsolete, createOnly, expectedHashes, () -> {
                recording = true;
                try {
                    for (var page : media.entrySet()) {
                        Path stem = completion.folder().resolve(completion.fileNames().get(page.getKey()));
                        saveMedia.save(page.getKey(), stem, page.getValue());
                    }
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
                commitRecord.run();
            });
        } catch (LocalizedException conflict) {
            if (!recording) frameArchive = null;
            throw conflict;
        }
    }

    private static Closeable reserve(DownloadPathGuard paths, long artworkId, Map<Path, Path> replacements,
                                     Set<Path> obsolete) throws IOException {
        var targets = new HashSet<>(replacements.keySet());
        targets.addAll(obsolete);
        try {
            return java.util.Objects.requireNonNull(paths.reserveOutputs(artworkId, targets));
        } catch (DirectoryTemplateException occupied) {
            throw new LocalizedException(HttpStatus.CONFLICT, "download.rebuild.output-in-use",
                    "Another work references a path this download needs to create, replace or remove, or a concurrent operation is using it. The download stopped and the existing files were kept.");
        }
    }

    void completed() {
        sources.clear();
        frameArchive = null;
        if (directory != null) {
            discard(retainedOriginals());
            if (!preserveScratch && discard(directory)) directory = null;
        }
    }

    private Path retainedOriginals() {
        return directory.resolveSibling(directory.getFileName() + "-originals");
    }

    private void retainFrames() {
        Path originals = retainedOriginals();
        Path retained = originals.resolve("animation");
        Path previous = originals.resolve("previous-animation");
        try {
            Files.createDirectories(originals);
            if (!Files.isDirectory(originals, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid artwork original directory");
            // 整目录移动；中断时旧帧包和时序始终同处于一个完整目录。
            if (Files.exists(retained, LinkOption.NOFOLLOW_LINKS)) {
                discard(previous);
                Files.move(retained, previous, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            Files.move(frameArchive, retained, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            discard(previous);
            LOG.info("Retained animation frame archive and timing at {}", retained);
        } catch (IOException failure) {
            preserveScratch = true;
            LOG.warn("Could not move frame archive and timing {}; retaining staging directory", frameArchive, failure);
        }
    }

    private synchronized void forgetOriginal(int page) {
        Path originals = retainedOriginals();
        if (!Files.isDirectory(originals, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            if (Files.deleteIfExists(originals.resolve(Integer.toString(page)))) Files.delete(originals);
        } catch (java.nio.file.DirectoryNotEmptyException otherPages) {
            // 仍有其它页待重试。
        } catch (IOException failure) {
            LOG.warn("Could not clear retained artwork original for page {}", page, failure);
        }
    }

    private synchronized void retainOriginal(int page, Path source) {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Path originals = Files.createDirectories(retainedOriginals());
            if (!Files.isDirectory(originals, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid artwork original directory");
            Path retained = originals.resolve(Integer.toString(page));
            Files.move(source, retained, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Retained original for incomplete artwork page {} at {}", page, retained);
        } catch (IOException failure) {
            preserveScratch = true;
            LOG.warn("Could not move downloaded original {}; retaining staging directory", source, failure);
        }
    }

    void finishPage(int page, Path stage) {
        Path source = sources.remove(page);
        if (source != null) retainOriginal(page, source);
        if (!preserveScratch) discard(stage);
    }

    private static void changeFiles(FileTransactionService transactions, Map<Path, Path> replacements, Set<Path> obsolete,
                                    Set<Path> createOnly, Map<Path, String> expectedHashes, Runnable commit) throws IOException {
        try {
            if (expectedHashes.isEmpty()) transactions.changeFiles(replacements, obsolete, createOnly, commit);
            else transactions.changeFiles(replacements, obsolete, createOnly, expectedHashes, commit);
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        } catch (FileAlreadyExistsException conflict) {
            var guarded = new HashSet<>(createOnly);
            guarded.addAll(expectedHashes.keySet());
            for (Path target : guarded) {
                if (target.toAbsolutePath().normalize().toString().equals(conflict.getFile())) {
                    var error = new LocalizedException(HttpStatus.CONFLICT,
                            "download.rebuild.target-exists", "Cannot replace output file {0}. Check the existing file before retrying.",
                            target.getFileName().toString());
                    error.initCause(conflict);
                    throw error;
                }
            }
            throw conflict;
        }
    }

    @Override public void close() {
        if (directory == null) return;
        if (frameArchive != null) retainFrames();
        // 未解析的整作传输不留下无主的成功页。逐页任务在入队与执行间取消时也要留下已下载的原图。
        for (var page : sources.entrySet()) {
            if (initialFolder != null && !recording && media.containsKey(page.getKey())) continue;
            Path source = page.getValue();
            String name = source.getFileName().toString();
            String extension = name.substring(name.lastIndexOf('.') + 1);
            if (initialFolder == null || initialFormats.contains("original")
                    || initialFormats.contains(extension.equals("jpeg") ? "jpg" : extension)) {
                // 下次发布也需要这个正式路径，应保持空闲，避免未登记的应急原件阻塞重试。
                retainOriginal(page.getKey(), source);
                continue;
            }
            Path target = initialFolder.resolve(source.getFileName());
            try {
                sourceTransactions.changeFiles(Map.of(target, source), Set.of(), Set.of(target), () -> {});
            } catch (IOException | RuntimeException unavailable) {
                LOG.warn("Could not return downloaded original to {}", target, unavailable);
                retainOriginal(page.getKey(), source);
            }
        }
        if (!preserveScratch) discard(directory);
    }

    static boolean discard(Path stage) {
        try {
            FileSystemUtils.deleteRecursively(stage);
            return true;
        } catch (IOException failure) {
            LOG.warn("Could not remove artwork staging directory {}", stage, failure);
            return false;
        }
    }
}
