package top.sywyar.pixivdownload.download;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.FileSystemUtils;
import top.sywyar.pixivdownload.core.artwork.download.ArtworkDownloadCompletion;
import top.sywyar.pixivdownload.core.artwork.download.PartialArtwork;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.asset.FileTransactionService;
import top.sywyar.pixivdownload.download.media.ImageOutputService.MediaSink;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps replacement files private until every page and its library record can be published together. */
final class ArtworkRebuild implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ArtworkRebuild.class);
    private final Map<Integer, ArtworkMediaManifest> media = new ConcurrentHashMap<>();
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

    void save(int page, Path stem, ArtworkMediaManifest manifest) {
        media.put(page, manifest);
    }

    void publish(FileTransactionService transactions, PartialArtwork previous, ArtworkDownloadCompletion completion,
                 MediaSink saveMedia, Runnable commitRecord) throws IOException {
        Map<Path, Path> replacements = new LinkedHashMap<>();
        for (int page = 0; page < completion.imageCount(); page++) {
            var manifest = media.get(page);
            if (manifest == null) throw new IOException("Missing staged artwork page " + page);
            String base = completion.fileNames().get(page);
            for (String extension : manifest.extensions()) {
                String name = base + "." + extension;
                replacements.put(completion.folder().resolve(name), directory.resolve(name));
            }
            for (String suffix : List.of("_thumb.jpg", ".frames.properties")) {
                Path staged = directory.resolve(base + suffix);
                if (Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS))
                    replacements.put(completion.folder().resolve(staged.getFileName()), staged);
            }
        }
        var obsolete = new HashSet<Path>();
        for (var names : previous.verifiedFiles().values()) {
            for (String name : names) obsolete.add(Path.of(previous.folder()).resolve(name));
        }
        for (var page : previous.media().entrySet()) {
            if (page.getValue().extensions().contains("webp")) {
                String base = previous.baseNames().get(page.getKey());
                obsolete.add(Path.of(previous.folder()).resolve(base + "_thumb.jpg"));
            }
        }
        obsolete.removeAll(replacements.keySet());
        transactions.changeFiles(replacements, obsolete, () -> {
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
    }

    @Override public void close() {
        if (directory == null) return;
        try { FileSystemUtils.deleteRecursively(directory); }
        catch (IOException failure) { LOG.warn("Could not remove artwork rebuild staging directory {}", directory, failure); }
    }
}
