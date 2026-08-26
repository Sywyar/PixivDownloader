package top.sywyar.pixivdownload.gui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.WindowStateSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 应用拥有的桌面窗口状态持久化。 */
final class DesktopWindowStateStore {
    private static final String FILE_NAME = "window-state.json";
    private static final int MAX_FILE_BYTES = 4 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(DesktopWindowStateStore.class);

    private final Path file;

    DesktopWindowStateStore(Path guiStateDirectory) {
        file = guiStateDirectory.resolve(FILE_NAME);
    }

    synchronized Optional<WindowStateSnapshot> load() {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("invalid window state file");
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("invalid window state file");
            JsonNode root = MAPPER.readTree(bytes);
            if (root == null || !root.isObject() || root.size() != 3
                    || !root.path("width").isIntegralNumber()
                    || !root.path("width").canConvertToInt()
                    || !root.path("height").isIntegralNumber()
                    || !root.path("height").canConvertToInt()
                    || !root.path("maximized").isBoolean()) {
                throw new IllegalArgumentException("invalid window state");
            }
            return Optional.of(new WindowStateSnapshot(
                    root.path("width").intValue(),
                    root.path("height").intValue(),
                    root.path("maximized").booleanValue()
            ));
        } catch (Exception failure) {
            LOG.debug("Failed to read desktop window state: {}", failure.toString());
            return Optional.empty();
        }
    }

    synchronized boolean save(WindowStateSnapshot state) {
        if (state == null) return false;
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("width", state.width());
            value.put("height", state.height());
            value.put("maximized", state.maximized());
            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                    .getBytes(StandardCharsets.UTF_8);
            temporary = Files.createTempFile(file.getParent(), "window-state-", ".tmp");
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception failure) {
            LOG.debug("Failed to persist desktop window state: {}", failure.toString());
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception cleanupFailure) {
                    LOG.debug("Failed to remove desktop window state temporary file: {}",
                            cleanupFailure.toString());
                }
            }
        }
    }
}
