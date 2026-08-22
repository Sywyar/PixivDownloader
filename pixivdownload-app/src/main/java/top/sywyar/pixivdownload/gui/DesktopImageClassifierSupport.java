package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.core.asset.BoundedImageDecoder;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static top.sywyar.pixivdownload.gui.DesktopUiNodes.nullToEmpty;

/**
 * 图片分类工具的预览解码与设置文本转换。
 */
final class DesktopImageClassifierSupport {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopImageClassifierSupport.class);

    private final DesktopUiHost host;
    private final String rootFolder;
    private final Map<Path, DesktopUiNode.ImageData> imageCache = new ConcurrentHashMap<>();
    private volatile Map<String, Path> paths = Map.of();

    DesktopImageClassifierSupport(DesktopUiHost host, String rootFolder) {
        this.host = host;
        this.rootFolder = rootFolder;
    }

    Optional<DesktopUiNode.ImageData> materializeImage(Path image) {
        DesktopUiNode.ImageData cached = imageCache.get(image);
        if (cached != null) return Optional.of(cached);
        try {
            Path source = image;
            String name = image.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".webp")) {
                Path thumbnail = image.resolveSibling(image.getFileName().toString().substring(
                        0,
                        image.getFileName().toString().lastIndexOf('.')
                ) + "_thumb.jpg");
                if (Files.isRegularFile(thumbnail)) source = thumbnail;
            }
            BufferedImage original = BoundedImageDecoder.read(source);
            if (original == null) return Optional.empty();
            double scale = Math.min(
                    1d,
                    Math.min(1600d / original.getWidth(), 1600d / original.getHeight())
            );
            int width = Math.max(1, (int) Math.round(original.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(original.getHeight() * scale));
            BufferedImage rendered = new BufferedImage(
                    width,
                    height,
                    BufferedImage.TYPE_INT_RGB
            );
            Graphics2D graphics = rendered.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(
                        0,
                        0,
                        width,
                        height
                );
                graphics.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC
                );
                graphics.drawImage(
                        original,
                        0,
                        0,
                        width,
                        height,
                        null
                );
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(rendered, "jpg", output)) return Optional.empty();
            DesktopUiNode.ImageData data = new DesktopUiNode.ImageData(
                    "image/jpeg",
                    Base64.getEncoder().encodeToString(output.toByteArray())
            );
            imageCache.put(image, data);
            return Optional.of(data);
        } catch (Exception failure) {
            LOG.warn("Unable to materialize classifier preview {}", image, failure);
            return Optional.empty();
        }
    }

    void clearCache() {
        imageCache.clear();
    }

    void setPaths(Map<String, Path> paths) {
        this.paths = Map.copyOf(paths);
    }

    String id(Path path) {
        return paths.entrySet().stream().filter(entry -> entry.getValue().equals(path)).map(Map.Entry::getKey).findFirst().orElse(
                "folder.unknown");
    }

    Path path(String id) {
        return paths.get(id);
    }

    DesktopUiHost.ImageClassifierSettings loadSettings() {
        try {
            return host.loadImageClassifierSettings(rootFolder);
        } catch (Exception ignored) {
            return new DesktopUiHost.ImageClassifierSettings(
                    "",
                    false,
                    "http://localhost:6999",
                    List.of()
            );
        }
    }

    List<DesktopUiHost.ImageClassifierTarget> parseTargets(String encoded) {
        List<DesktopUiHost.ImageClassifierTarget> targets = new ArrayList<>();
        for (String line : nullToEmpty(encoded).lines().toList()) {
            String value = line.trim();
            if (value.isBlank()) continue;
            int separator = value.indexOf('|');
            String folder = (separator < 0 ? value : value.substring(0, separator)).trim();
            String remark = separator < 0 ? "" : value.substring(separator + 1).trim();
            if (!folder.isBlank()) {
                targets.add(new DesktopUiHost.ImageClassifierTarget(folder, remark));
            }
        }
        return List.copyOf(targets);
    }

    String encodeTargets(List<DesktopUiHost.ImageClassifierTarget> targets) {
        return targets.stream().map(target -> target.folder() + (target.remark().isBlank() ? "" : "|" + target.remark())).collect(
                java.util.stream.Collectors.joining(System.lineSeparator()));
    }
}
