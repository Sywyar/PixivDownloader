package top.sywyar.pixivdownload.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkSidecarFiles;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

final class DesktopUiTools {

    private static final Logger log = LoggerFactory.getLogger(DesktopUiTools.class);
    private static final String DEFAULT_SERVER_URL = "http://localhost:6999";
    private static final List<String> IMAGE_EXTENSIONS =
            List.of(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp");

    private final RestTemplate restTemplate = new RestTemplate();

    DesktopUiHost.FolderCheckResult checkArtworkFolders(Path databasePath) throws SQLException {
        List<DesktopUiHost.FolderArtwork> all;
        try (Connection connection = openDatabase(databasePath)) {
            all = loadArtworks(connection,
                    "SELECT artwork_id, title, folder, moved, move_folder FROM artworks"
                            + " WHERE deleted = 0 ORDER BY time DESC");
        } catch (SQLException exception) {
            if (!String.valueOf(exception.getMessage()).contains("no such column")) {
                throw exception;
            }
            try (Connection connection = openDatabase(databasePath)) {
                all = loadArtworks(connection,
                        "SELECT artwork_id, title, folder, moved, move_folder FROM artworks ORDER BY time DESC");
            }
        }

        List<DesktopUiHost.FolderArtwork> inaccessible = all.stream()
                .filter(artwork -> !isDirectory(artwork.path()))
                .toList();
        return new DesktopUiHost.FolderCheckResult(all.size(), inaccessible);
    }

    void updateArtworkFolder(Path databasePath, long artworkId, boolean moved, String newPath) throws SQLException {
        String column = moved ? "move_folder" : "folder";
        try (Connection connection = openDatabase(databasePath);
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE artworks SET " + column + " = ? WHERE artwork_id = ?")) {
            statement.setString(1, newPath);
            statement.setLong(2, artworkId);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Artwork " + artworkId + " was not found");
            }
        }
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
                properties.getProperty("server.url", DEFAULT_SERVER_URL),
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
        try (var paths = Files.list(parent)) {
            return paths.filter(Files::isDirectory).sorted(DesktopUiTools::compareFolderNames).toList();
        }
    }

    List<Path> listImageClassifierImages(Path folder) throws IOException {
        try (var paths = Files.list(folder)) {
            return paths.filter(Files::isRegularFile)
                    .filter(DesktopUiTools::isSupportedImage)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
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
        String primary = configuredUrl == null || configuredUrl.isBlank() ? DEFAULT_SERVER_URL : configuredUrl.trim();
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
                        artworkId = number.longValue();
                    }
                }
            } catch (Exception exception) {
                log.debug("Failed to resolve artwork by move folder: {}", exception.getMessage());
            }
        }
        if (artworkId == null) {
            try {
                long parsed = Long.parseLong(folder.getFileName().toString());
                if (parsed > 0) {
                    artworkId = parsed;
                }
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
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

    Path classifyImageFolder(Path sourceFolder, List<Path> images, long artworkId, Path targetFolder,
                             DesktopUiHost.ImageClassifierServer server,
                             DesktopUiHost.ImageClassifierDeleteFailureHandler deleteFailureHandler) throws IOException {
        if (images == null || images.isEmpty()) {
            throw new IOException("No images were supplied for classification");
        }
        Files.createDirectories(targetFolder);
        Path numberedFolder = images.size() == 1 ? null : nextNumberedFolder(targetFolder);
        Path destination = numberedFolder == null ? targetFolder : numberedFolder;
        if (numberedFolder != null) {
            Files.createDirectory(numberedFolder);
        }

        List<Path> copied = new ArrayList<>();
        Path sidecar = sourceFolder.resolve(WorkSidecarFiles.fileName(artworkId));
        try {
            for (Path image : images) {
                Path target = destination.resolve(image.getFileName());
                Files.copy(image, target);
                copied.add(target);
            }
            if (Files.isRegularFile(sidecar)) {
                Path target = destination.resolve(sidecar.getFileName());
                Files.copy(sidecar, target);
                copied.add(target);
            }
        } catch (IOException exception) {
            rollbackCopies(numberedFolder, copied);
            throw exception;
        }

        while (Files.exists(sourceFolder)) {
            try {
                for (Path image : images) {
                    Files.deleteIfExists(image);
                }
                Files.deleteIfExists(sidecar);
                int remaining;
                try (var paths = Files.list(sourceFolder)) {
                    remaining = Math.toIntExact(paths.count());
                }
                if (remaining > 0) {
                    throw new IOException("Source folder contains " + remaining + " other file(s)");
                }
                Files.deleteIfExists(sourceFolder);
            } catch (Exception exception) {
                if (!Files.exists(sourceFolder)) {
                    break;
                }
                if (!deleteFailureHandler.retry(String.valueOf(exception.getMessage()), sourceFolder)) {
                    break;
                }
            }
        }

        if (server != null && server.available()) {
            recordClassifierMove(server.url(), artworkId, destination, targetFolder);
        }
        return destination;
    }

    private static Connection openDatabase(Path databasePath) throws SQLException {
        SQLiteConfig configuration = new SQLiteConfig();
        configuration.setBusyTimeout(5000);
        configuration.setJournalMode(SQLiteConfig.JournalMode.WAL);
        configuration.setReadOnly(false);
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath, configuration.toProperties());
    }

    private static List<DesktopUiHost.FolderArtwork> loadArtworks(Connection connection, String sql) throws SQLException {
        List<DesktopUiHost.FolderArtwork> artworks = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                boolean moved = result.getInt("moved") == 1;
                String movedFolder = result.getString("move_folder");
                String path = moved && movedFolder != null ? movedFolder : result.getString("folder");
                artworks.add(new DesktopUiHost.FolderArtwork(
                        result.getLong("artwork_id"), result.getString("title"), path, moved));
            }
        }
        return artworks;
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

    private static int compareFolderNames(Path left, Path right) {
        String leftName = left.getFileName().toString();
        String rightName = right.getFileName().toString();
        try {
            return Integer.compare(Integer.parseInt(leftName), Integer.parseInt(rightName));
        } catch (NumberFormatException ignored) {
            return leftName.compareTo(rightName);
        }
    }

    private static boolean isSupportedImage(Path path) {
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

    private static Path nextNumberedFolder(Path parent) throws IOException {
        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            Path candidate = parent.resolve(Integer.toString(i));
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("No numbered classifier folder is available under " + parent);
    }

    private static void rollbackCopies(Path numberedFolder, List<Path> copied) {
        if (numberedFolder != null) {
            for (int i = copied.size() - 1; i >= 0; i--) {
                try {
                    Files.deleteIfExists(copied.get(i));
                } catch (IOException exception) {
                    log.warn("Failed to roll back copied classifier file {}", copied.get(i), exception);
                }
            }
            try {
                Files.deleteIfExists(numberedFolder);
            } catch (IOException exception) {
                log.warn("Failed to remove classifier rollback folder {}", numberedFolder, exception);
            }
            return;
        }
        for (int i = copied.size() - 1; i >= 0; i--) {
            try {
                Files.deleteIfExists(copied.get(i));
            } catch (IOException exception) {
                log.warn("Failed to roll back copied classifier file {}", copied.get(i), exception);
            }
        }
    }

    private void recordClassifierMove(String serverUrl, long artworkId, Path movePath, Path classifierTargetFolder) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("movePath", movePath.toString());
        body.put("moveTime", System.currentTimeMillis());
        body.put("classifierTargetFolder", stripTrailingSeparators(classifierTargetFolder.toString()));
        try {
            restTemplate.exchange(serverUrl + "/api/downloaded/move/" + artworkId,
                    HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
        } catch (Exception exception) {
            log.error("Failed to record classifier move for artwork {}", artworkId, exception);
        }
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
