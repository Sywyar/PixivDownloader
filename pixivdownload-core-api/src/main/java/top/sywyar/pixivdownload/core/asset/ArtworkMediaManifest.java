package top.sywyar.pixivdownload.core.asset;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** 随作品移动的逐页媒体事实，用于区分原始格式与转码产物。 */
public record ArtworkMediaManifest(String originalExtension, List<String> extensions, boolean originalRetained) {
    public static final String SUFFIX = ".media.properties";
    private static final int MAX_BYTES = 4096;
    private static final Set<String> FORMATS = Set.of("png", "jpg", "jpeg", "webp", "gif", "apng", "mp4", "zip");

    public ArtworkMediaManifest {
        if (originalExtension == null || !FORMATS.contains(originalExtension) || extensions == null || extensions.isEmpty()
                || extensions.size() > FORMATS.size() || extensions.stream().anyMatch(value -> value == null || !FORMATS.contains(value))
                || Set.copyOf(extensions).size() != extensions.size()) {
            throw new IllegalArgumentException("Invalid media manifest");
        }
        extensions = List.copyOf(extensions);
        if (originalRetained && !extensions.contains(originalExtension)) throw new IllegalArgumentException("Original file is not an output");
    }

    public ArtworkMediaManifest(String originalExtension, List<String> extensions) {
        this(originalExtension, extensions, extensions != null && extensions.contains(originalExtension));
    }

    public static Path path(Path stem) {
        return stem.resolveSibling(stem.getFileName() + SUFFIX);
    }

    public void write(Path stem) throws IOException {
        Path destination = path(stem);
        Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
        String text = "version=1\noriginal=" + originalExtension + "\noutputs=" + String.join(",", extensions)
                + "\noriginal-retained=" + originalRetained + "\n";
        boolean created = false;
        try {
            try (var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (created) Files.deleteIfExists(temporary);
        }
    }

    public static Optional<ArtworkMediaManifest> read(Path stem) throws IOException {
        Path file = path(stem);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Media manifest byte limit exceeded");
            Properties properties = new Properties();
            properties.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
            if (!"1".equals(properties.getProperty("version"))) throw new IOException("Unknown media manifest version");
            String retained = properties.getProperty("original-retained");
            if (!"true".equals(retained) && !"false".equals(retained)) throw new IOException("Missing original retention fact");
            try {
                return Optional.of(new ArtworkMediaManifest(properties.getProperty("original", ""),
                        List.of(properties.getProperty("outputs", "").split(",", -1)), Boolean.parseBoolean(retained)));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid media manifest", invalid);
            }
        }
    }
}
