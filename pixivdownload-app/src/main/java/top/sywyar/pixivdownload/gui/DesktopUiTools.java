package top.sywyar.pixivdownload.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.asset.ExternalWorkFiles;
import top.sywyar.pixivdownload.core.hash.ImageHashMapper;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefix;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.pathprefix.StoredPathCodec;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import top.sywyar.pixivdownload.core.asset.DownloadDirectories;
import java.util.Properties;
import top.sywyar.pixivdownload.plugin.api.storage.relational.*;
import top.sywyar.pixivdownload.core.db.schema.CoreMigrationSchema;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.*;
import static top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.Operator.*;

final class DesktopUiTools {
    private static final top.sywyar.pixivdownload.plugin.api.database.migration.MigrationSchema SCHEMA =
            CoreMigrationSchema.create(DatabaseSchemaRegistry.forBuiltInPlugins(), true);
    private static final Logger log = LoggerFactory.getLogger(DesktopUiTools.class);
    private final java.util.function.Supplier<String> serverUrl;
    private static final List<String> IMAGE_EXTENSIONS =
            List.of(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp");

    private final RestTemplate restTemplate = new RestTemplate();

    DesktopUiTools(java.util.function.Supplier<String> serverUrl) {
        this.serverUrl = serverUrl;
    }

    DesktopUiHost.FolderCheckResult checkArtworkFolders(DatabaseTarget target,
                                                       String rootFolder) throws SQLException {
        try (Connection connection = target.openConnection()) {
            return checkArtworkFolders(connection, target, rootFolder);
        }
    }

    DesktopUiHost.FolderCheckResult checkArtworkFolders(Connection connection, DatabaseTarget target, String rootFolder) throws SQLException {
        var page = checkArtworkFolders(connection, target, rootFolder, 0, Integer.MAX_VALUE);
        return new DesktopUiHost.FolderCheckResult(page.total(), page.inaccessible());
    }

    record FolderPage(int total, int missing, List<DesktopUiHost.FolderArtwork> inaccessible) {}

    FolderPage checkArtworkFolders(Connection connection, DatabaseTarget target, String rootFolder, int offset, int limit) throws SQLException {
        var actual = target.readSchema(connection);
        var table = actual.get("artworks");
        if (table == null) throw new SQLException("DATABASE_EXISTING_MAIN_REQUIRED", "42P01");
        Expression filter = table.columns().stream().anyMatch(c -> c.name().equals("deleted"))
                ? call(EQUAL, column("artworks", "deleted"), value(0)) : null;
        var fields = new java.util.LinkedHashMap<String, Expression>();
        for (String field : List.of("artwork_id", "title", "folder", "move_folder")) fields.put(field, column("artworks", field));
        var query = new Query("artworks", fields, filter, List.of(),
                List.of(new Query.Order(column("artworks", "time"), true, false),
                        new Query.Order(column("artworks", "artwork_id"), true, false)), Integer.MAX_VALUE, 0);
        List<DesktopUiHost.FolderArtwork> inaccessible = new ArrayList<>();
        int[] total = {0};
        int[] missing = {0};
        try (var session = target.storageSession(connection, SCHEMA)) {
            var codec = loadPathCodec(session, actual.containsKey("path_prefixes"), rootFolder);
            session.scan(query, row -> {
                total[0] = Math.incrementExact(total[0]);
                String moveFolder = (String) row.get("move_folder");
                boolean moved = ArtworkRecord.usesMoveFolder(moveFolder);
                String stored = moved ? moveFolder : (String) row.get("folder");
                String path = codec.resolve(stored);
                if (!isDirectory(path)) {
                    if (missing[0]++ >= offset && inaccessible.size() < limit)
                        inaccessible.add(new DesktopUiHost.FolderArtwork((Long) row.get("artwork_id"),
                                (String) row.get("title"), path, moved));
                }
                return true;
            });
        }
        return new FolderPage(total[0], missing[0], List.copyOf(inaccessible));
    }

    void updateArtworkFolder(DatabaseTarget target, String rootFolder,
                             long artworkId, boolean moved, String newPath) throws SQLException {
        try (Connection connection = target.openConnection()) {
            updateArtworkFolder(connection, target, rootFolder, artworkId, moved, newPath);
        }
    }

    void updateArtworkFolder(Connection connection, DatabaseTarget target, String rootFolder,
                             long artworkId, boolean moved, String newPath) throws SQLException {
        updateArtworkFolder(connection, target, rootFolder, artworkId, moved, newPath, null);
    }

    void updateArtworkFolder(Connection connection, DatabaseTarget target, String rootFolder,
                             long artworkId, boolean moved, String newPath, DownloadDirectories directories) throws SQLException {
        var tables = target.readSchema(connection).keySet();
        synchronized (directories == null ? this : directories) {
            var artwork = call(EQUAL, column("artworks", "artwork_id"), value(artworkId));
            // SQLite 的删除事务可能已经持有写锁，先用读会话返回已知的目录冲突。
            if (directories != null && directories.hasDeletionReservations()) {
                try (var session = target.storageSession(connection, SCHEMA)) {
                    var codec = loadPathCodec(session, tables.contains("path_prefixes"), rootFolder);
                    var update = readArtworkFolderUpdate(session, codec, tables, artwork, moved, newPath);
                    directories.requireDirectoryAvailable(Path.of(update.effectivePath()));
                }
            }
            var transaction = target.beginToolTransaction(connection);
            Throwable failure = null;
            try {
                try (var session = target.storageSession(connection, SCHEMA)) {
                    var codec = loadPathCodec(session, tables.contains("path_prefixes"), rootFolder);
                    var update = readArtworkFolderUpdate(session, codec, tables, artwork, moved, newPath);
                    if (directories != null) directories.requireDirectoryAvailable(Path.of(update.effectivePath()));
                    if (session.update("artworks", Map.of(moved ? "move_folder" : "folder", value(update.storedPath())), artwork) == 0)
                        throw new SQLException("ARTWORK_NOT_FOUND", "02000");
                    if (tables.contains("artwork_image_hashes") && !update.external()
                            && !sameDirectory(codec.resolve(update.previousPath()), update.effectivePath())) {
                        ImageHashMapper.deleteByArtwork(session, artworkId);
                    }
                }
                transaction.commit();
            } catch (SQLException | RuntimeException | Error uncommitted) {
                failure = uncommitted;
                throw uncommitted;
            } finally {
                transaction.finish(failure);
            }
        }
    }

    private record ArtworkFolderUpdate(String storedPath, String previousPath, String effectivePath, boolean external) {}

    private static ArtworkFolderUpdate readArtworkFolderUpdate(DatabaseSession session, StoredPathCodec codec,
            java.util.Set<String> tables, Expression artwork, boolean moved, String newPath) throws SQLException {
        var fields = new java.util.LinkedHashMap<String, Expression>();
        fields.put("folder", column("artworks", "folder"));
        fields.put("move_folder", column("artworks", "move_folder"));
        if (tables.contains("external_work_files")) {
            fields.put("external", ExternalWorkFiles.referenceMarker(WorkType.ARTWORK,
                    column("artworks", "artwork_id"), column("artworks", "time")));
        }
        var rows = session.query(new Query("artworks", fields, artwork, List.of(), List.of(), 1, 0));
        if (rows.isEmpty()) throw new SQLException("ARTWORK_NOT_FOUND", "02000");
        var row = rows.getFirst();
        String oldMove = (String) row.get("move_folder");
        String oldLocation = ArtworkRecord.usesMoveFolder(oldMove) ? oldMove : (String) row.get("folder");
        String stored = codec.encode(newPath);
        String newMove = moved ? stored : oldMove;
        String newFolder = moved ? (String) row.get("folder") : stored;
        String newLocation = ArtworkRecord.usesMoveFolder(newMove) ? newMove : newFolder;
        return new ArtworkFolderUpdate(stored, oldLocation, codec.resolve(newLocation), row.get("external") != null);
    }

    private static boolean sameDirectory(String previous, String current) {
        if (java.util.Objects.equals(previous, current)) return true;
        if (previous == null || previous.isBlank()) return false;
        try { return Files.isSameFile(Path.of(previous), Path.of(current)); }
        catch (IOException | java.nio.file.InvalidPathException unavailable) { return false; }
    }

    DesktopUiHost.ImageClassifierSettings loadImageClassifierSettings(String rootFolder) throws IOException {
        Path path = RuntimeFiles.resolveImageClassifierPath(rootFolder);
        Properties properties = new Properties();
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            }
        }
        List<DesktopUiHost.ImageClassifierTarget> targets = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String folder = properties.getProperty("target.folder." + i);
            String remark = properties.getProperty("folder.remark." + i);
            if (folder != null && remark != null) {
                targets.add(new DesktopUiHost.ImageClassifierTarget(stripTrailingSeparators(folder), remark));
            }
        }
        return new DesktopUiHost.ImageClassifierSettings(
                properties.getProperty("default.folder", ""),
                Boolean.parseBoolean(properties.getProperty("show.skip.button", "true")),
                properties.getProperty("server.url", ""),
                targets);
    }

    void saveImageClassifierSettings(String rootFolder, DesktopUiHost.ImageClassifierSettings settings) throws IOException {
        Path path = RuntimeFiles.resolveImageClassifierPath(rootFolder);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Properties properties = new Properties();
        properties.setProperty("default.folder", stripTrailingSeparators(settings.defaultFolder()));
        properties.setProperty("show.skip.button", Boolean.toString(settings.showSkipButton()));
        properties.setProperty("server.url", settings.serverUrl());
        for (int i = 0; i < Math.min(20, settings.targets().size()); i++) {
            DesktopUiHost.ImageClassifierTarget target = settings.targets().get(i);
            properties.setProperty("target.folder." + i, stripTrailingSeparators(target.folder()));
            properties.setProperty("folder.remark." + i, target.remark());
        }
        try (OutputStream output = Files.newOutputStream(path)) {
            properties.store(output, "Image Classifier Configuration");
        }
    }

    boolean isImageClassifierDirectory(Path path) {
        return path != null && Files.isDirectory(path);
    }

    List<Path> listImageClassifierFolders(Path parent) throws IOException {
        return listClassifierPaths(parent, true, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    List<Path> listImageClassifierImages(Path folder) throws IOException {
        return listClassifierPaths(folder, false, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    List<Path> listClassifierPaths(Path folder, boolean directories, int itemBudget, long characterBudget) throws IOException {
        var result = new ArrayList<Path>();
        int count = 0;
        long characters = 0;
        try (var paths = Files.newDirectoryStream(folder)) {
            for (Path path : paths) {
                if (++count > itemBudget || (characters += path.toString().length()) > characterBudget)
                    throw new IllegalArgumentException("CLASSIFIER_DIRECTORY_LIMIT");
                if (directories ? Files.isDirectory(path) : Files.isRegularFile(path) && isSupportedImage(path))
                    result.add(path);
            }
        }
        Comparator<Path> names = Comparator.comparing(path -> path.getFileName().toString());
        result.sort(directories ? Comparator.comparing((Path path) -> parseLongOrNull(path.getFileName().toString()),
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(names) : names);
        return List.copyOf(result);
    }

    void deleteImageClassifierFolderIfEmpty(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
            if (children.iterator().hasNext()) {
                return;
            }
        }
        Files.deleteIfExists(folder);
    }

    DesktopUiHost.ImageClassifierServer checkImageClassifierServer(String configuredUrl) {
        String primary = configuredUrl == null || configuredUrl.isBlank() ? serverUrl.get() : configuredUrl.trim();
        if (serverResponds(primary)) {
            return new DesktopUiHost.ImageClassifierServer(true, primary);
        }
        String fallback = alternateScheme(primary);
        if (fallback != null && serverResponds(fallback)) {
            return new DesktopUiHost.ImageClassifierServer(true, fallback);
        }
        return new DesktopUiHost.ImageClassifierServer(false, primary);
    }

    Optional<DesktopUiHost.ImageClassifierArtwork> resolveImageClassifierArtwork(
            Path folder, DesktopUiHost.ImageClassifierServer server) {
        Long artworkId = null;
        if (server != null && server.available()) {
            try {
                ResponseEntity<Map> response = restTemplate.getForEntity(
                        server.url() + "/api/downloaded/by-move-folder?path={path}", Map.class, folder.toString());
                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    Object value = response.getBody().get("artworkId");
                    if (value instanceof Number number) {
                        artworkId = parseLongOrNull(number.toString());
                    }
                }
            } catch (Exception exception) {
                log.debug("Failed to resolve artwork by move folder: {}", exception.getMessage());
            }
        }
        artworkId = classifierArtworkId(folder, artworkId);
        if (artworkId == null) {
            return Optional.empty();
        }

        String title = null;
        Integer xRestrict = null;
        if (server != null && server.available()) {
            try {
                ResponseEntity<Map> response = restTemplate.getForEntity(
                        server.url() + "/api/downloaded/" + artworkId, Map.class);
                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    Object titleValue = response.getBody().get("title");
                    if (titleValue instanceof String value) {
                        title = value;
                    }
                    Object restrictValue = response.getBody().get("xRestrict");
                    if (restrictValue instanceof Number value) {
                        xRestrict = value.intValue();
                    }
                }
            } catch (Exception exception) {
                log.debug("Failed to load classifier artwork metadata: {}", exception.getMessage());
            }
        }
        return Optional.of(new DesktopUiHost.ImageClassifierArtwork(artworkId, title, xRestrict));
    }

    static Long classifierArtworkId(Path folder, Long movedArtworkId) {
        if (movedArtworkId != null && movedArtworkId > 0) return movedArtworkId;
        Path name = folder.getFileName();
        Long id = name == null ? null : parseLongOrNull(name.toString());
        return id != null && id > 0 ? id : null;
    }

    private static StoredPathCodec loadPathCodec(DatabaseSession session, boolean hasPrefixes, String rootFolder) throws SQLException {
        List<PathPrefix> prefixes = new ArrayList<>();
        if (hasPrefixes) session.scan(new Query("path_prefixes", Map.of("id", column("path_prefixes", "id"),
                "path", column("path_prefixes", "path")), null, List.of(), List.of(), Integer.MAX_VALUE, 0), row -> {
            prefixes.add(new PathPrefix((Long) row.get("id"), (String) row.get("path"))); return true;
        });
        return PathPrefixCodec.snapshot(rootFolder, prefixes);
    }

    private static boolean isDirectory(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            return Files.isDirectory(Path.of(path));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Long parseLongOrNull(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static boolean isSupportedImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private boolean serverResponds(String serverUrl) {
        try {
            return restTemplate.getForEntity(serverUrl + "/api/download/status", byte[].class).getStatusCode() == HttpStatus.OK;
        } catch (Exception exception) {
            log.debug("Image classifier server status check failed for {}: {}", serverUrl, exception.getMessage());
            return false;
        }
    }

    private static String alternateScheme(String url) {
        if (url.startsWith("http://")) {
            return "https://" + url.substring("http://".length());
        }
        if (url.startsWith("https://")) {
            return "http://" + url.substring("https://".length());
        }
        return null;
    }

    private static String stripTrailingSeparators(String value) {
        if (value == null) {
            return "";
        }
        int end = value.length();
        while (end > 1 && (value.charAt(end - 1) == '/' || value.charAt(end - 1) == '\\')) {
            if (end == 3 && value.charAt(1) == ':') {
                break;
            }
            end--;
        }
        return value.substring(0, end);
    }
}
