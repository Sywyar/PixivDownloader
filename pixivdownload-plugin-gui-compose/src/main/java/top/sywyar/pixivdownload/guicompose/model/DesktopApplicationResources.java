package top.sywyar.pixivdownload.guicompose.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 加载并校验桌面关于页使用的内置应用资源。
 */
final class DesktopApplicationResources {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopApplicationResources.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern MAINTAINER_LOGIN = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");
    private static final Set<String> MAINTAINER_ROLES = Set.of(
            "author-core",
            "commit-contributor",
            "commit-collaborator"
    );

    private DesktopApplicationResources() {
    }

    static Snapshot load() {
        return new Snapshot(
                loadApplicationIcon(),
                loadMaintainers(),
                loadLicenseText()
        );
    }

    private static Optional<DesktopUiNode.ImageData> loadApplicationIcon() {
        try (var stream = DesktopApplicationResources.class.getResourceAsStream(
                "/static/favicon.ico")) {
            if (stream == null) return Optional.empty();
            return Optional.of(new DesktopUiNode.ImageData(
                    "image/x-icon",
                    Base64.getEncoder().encodeToString(stream.readAllBytes())
            ));
        } catch (Exception failure) {
            LOG.warn("Unable to load the desktop application icon", failure);
            return Optional.empty();
        }
    }

    private static List<Maintainer> loadMaintainers() {
        return loadMaintainers(DesktopApplicationResources.class.getResourceAsStream(
                "/pixivdownload/maintainers.json"));
    }

    static List<Maintainer> loadMaintainers(InputStream stream) {
        try (stream) {
            if (stream == null)
                throw new IllegalStateException("Bundled maintainer catalog is missing");
            MaintainerCatalog catalog = OBJECT_MAPPER.readValue(
                    stream,
                    MaintainerCatalog.class
            );
            if (catalog == null || catalog.maintainers() == null || catalog.maintainers().isEmpty()) {
                throw new IllegalStateException("Bundled maintainer catalog is empty");
            }
            Map<Long, Maintainer> unique = new LinkedHashMap<>();
            for (Maintainer maintainer : catalog.maintainers()) {
                if (maintainer == null || unique.putIfAbsent(
                        maintainer.id(),
                        maintainer
                ) != null) {
                    throw new IllegalStateException("Bundled maintainer catalog contains duplicates");
                }
            }
            return List.copyOf(unique.values());
        } catch (Exception failure) {
            LOG.warn("Unable to load the bundled maintainer catalog", failure);
            return List.of();
        }
    }

    private static String loadLicenseText() {
        try (var stream = DesktopApplicationResources.class.getResourceAsStream("/LICENSE")) {
            return stream == null ? "GNU AGPL v3.0" : new String(
                    stream.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        } catch (Exception failure) {
            LOG.warn("Unable to load the bundled license text", failure);
            return "GNU AGPL v3.0";
        }
    }

    private static void validateHttpsUri(String value, String expectedHost) {
        URI uri = URI.create(Objects.requireNonNull(value, "value"));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !expectedHost.equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null || uri.getPort() != -1) {
            throw new IllegalArgumentException("Invalid maintainer URL");
        }
    }

    record Snapshot(
            Optional<DesktopUiNode.ImageData> applicationIcon,
            List<Maintainer> maintainers,
            String licenseText
    ) {
    }

    private record MaintainerCatalog(List<Maintainer> maintainers) {
    }

    record Maintainer(
            long id,
            String login,
            String role,
            String avatarUrl,
            String profileUrl,
            String avatarMediaType,
            String avatarBase64
    ) {
        Maintainer {
            if (id <= 0L || !MAINTAINER_LOGIN.matcher(Objects.requireNonNull(
                    login,
                    "login"
            )).matches() || !MAINTAINER_ROLES.contains(Objects.requireNonNull(
                    role,
                    "role"
            ))) {
                throw new IllegalArgumentException("Invalid maintainer identity");
            }
            validateHttpsUri(avatarUrl, "avatars.githubusercontent.com");
            validateHttpsUri(profileUrl, "github.com");
            new DesktopUiNode.ImageData(avatarMediaType, avatarBase64);
        }

        DesktopUiNode.ImageData avatar() {
            return new DesktopUiNode.ImageData(avatarMediaType, avatarBase64);
        }
    }
}
