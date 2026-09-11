package top.sywyar.pixivdownload.sdk.development;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 从发行元数据读取固定配套关系；兼容范围不参与运行包选择。 */
record SdkRuntimeLock(String sdkVersion, String releaseId, String sourceCommitSha, Runtime runtime) {
    static final long MAX_JSON_BYTES = 1024 * 1024;
    static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    record Artifact(String file, long size, String sha256) {
        Artifact {
            safeRelative(file);
            if (size <= 0 || size > SdkRuntimeArchive.MAX_ARCHIVE_BYTES
                    || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("SDK_INVALID_ARTIFACT");
            }
        }
    }

    record Runtime(String hostVersion, String hostSourceCommitSha, List<String> platforms,
                   String downloadUrl, Artifact archive, Artifact host, Artifact pluginsManifest) {
        Runtime {
            if (hostVersion == null || !hostVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || hostSourceCommitSha == null || !hostSourceCommitSha.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("SDK_INVALID_HOST_IDENTITY");
            }
            platforms = List.copyOf(platforms);
            if (platforms.isEmpty() || platforms.size() > 8 || platforms.stream().distinct().count() != platforms.size()
                    || !List.of("windows-x64", "windows-arm64", "linux-x64", "linux-arm64", "macos-arm64")
                            .containsAll(platforms)) {
                throw new IllegalArgumentException("SDK_INVALID_PLATFORMS");
            }
            Objects.requireNonNull(archive);
            Objects.requireNonNull(host);
            Objects.requireNonNull(pluginsManifest);
            if (!archive.file().matches("[A-Za-z0-9._-]+\\.zip")
                    || !host.file().equals("PixivDownload-" + hostVersion + ".jar")
                    || !pluginsManifest.file().equals("plugins-manifest.json")) {
                throw new IllegalArgumentException("SDK_INVALID_RUNTIME_LAYOUT");
            }
        }
    }

    static SdkRuntimeLock read(Path project) throws IOException {
        JsonNode metadata = readJson(project.resolve("sdk-project.json"));
        if (!metadata.path("schemaVersion").isInt() || metadata.path("schemaVersion").intValue() != 3
                || !metadata.path("javaVersion").isInt() || metadata.path("javaVersion").intValue() != 17) {
            throw new IOException("SDK_UNSUPPORTED_PROJECT");
        }
        String version = requiredText(metadata, "sdkVersion");
        String releaseId = requiredText(metadata, "releaseId");
        String source = requiredText(metadata, "sourceCommitSha");
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-(?:alpha|beta|rc)[0-9]+)?")
                || !releaseId.equals("sdk-api-v" + version) || !source.matches("[0-9a-f]{40}")) {
            throw new IOException("SDK_INVALID_IDENTITY");
        }
        Runtime runtime = JSON.treeToValue(metadata.required("developmentRuntime"), Runtime.class);
        String expectedUrl = "https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases/download/"
                + releaseId + "/" + runtime.archive().file();
        if (!expectedUrl.equals(runtime.downloadUrl())) {
            throw new IOException("SDK_RUNTIME_URL_IDENTITY_MISMATCH");
        }
        return new SdkRuntimeLock(version, releaseId, source, runtime);
    }

    void requireCurrentPlatform() throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String platform = (os.startsWith("windows") ? "windows" : os.startsWith("linux") ? "linux"
                : os.startsWith("mac") ? "macos" : "unsupported") + "-"
                + (List.of("amd64", "x86_64").contains(arch) ? "x64"
                : List.of("aarch64", "arm64").contains(arch) ? "arm64" : "unsupported");
        if (!runtime.platforms().contains(platform)) {
            throw new IOException("SDK_UNSUPPORTED_PLATFORM: " + platform);
        }
    }

    static JsonNode readJson(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_JSON_BYTES) {
            throw new IOException("SDK_INVALID_METADATA_FILE");
        }
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes((int) MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) throw new IOException("SDK_METADATA_TOO_LARGE");
            return JSON.readTree(bytes);
        }
    }

    static String requiredText(JsonNode node, String key) throws IOException {
        JsonNode value = node.required(key);
        if (!value.isTextual() || value.textValue().isBlank()) throw new IOException("SDK_INVALID_" + key);
        return value.textValue();
    }

    static Path safeRelative(String value) {
        if (value == null || value.isEmpty() || value.length() > 1024 || value.startsWith("/")
                || value.contains("\\") || value.contains(":") || value.contains("//")) {
            throw new IllegalArgumentException("SDK_UNSAFE_PATH");
        }
        String[] parts = value.split("/", -1);
        if (parts.length > 64) throw new IllegalArgumentException("SDK_PATH_TOO_DEEP");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.matches(".*[\\x00-\\x1f<>\"|?*].*")
                    || part.matches("(?i)(?:CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:\\..*)?")) {
                throw new IllegalArgumentException("SDK_UNSAFE_PATH");
            }
        }
        return Path.of(value);
    }
}
