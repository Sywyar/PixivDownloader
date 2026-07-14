package top.sywyar.pixivdownload.plugin.runtime.artifact;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimeOperationException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Explicit development-mode adapter for Maven plugin modules.
 *
 * <p>Production still loads only verified artifacts from {@code plugins/}. When the opt-in JVM property is enabled,
 * the runtime ignores that directory and maps sibling modules' {@code target/classes} output to a PF4J directory
 * layout under the repository {@code target/} cache.
 */
public final class PluginDevelopmentArtifacts {

    public static final String ENABLED_PROPERTY = "pixivdownload.plugin-dev.enabled";
    public static final String ROOT_PROPERTY = "pixivdownload.plugin-dev.root";

    private static final String TARGET_DIR = "target";
    private static final String CLASSES_DIR = "classes";
    private static final String LIB_DIR = "lib";
    private static final String CACHE_DIR = "pixivdownload-plugin-dev-runtime";
    private static final String PLUGIN_PROPERTIES = "plugin.properties";
    private static final String SESSION_MARKER = ".pixiv-plugin-dev-session";
    private static final String SNAPSHOT_MARKER = ".pixiv-plugin-dev-snapshot";
    private static final int SESSION_DELETE_ATTEMPTS = 5;
    private static final long SESSION_DELETE_RETRY_MILLIS = 25L;

    private PluginDevelopmentArtifacts() {
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY));
    }

    public static DevelopmentDiscovery discover(Path pluginsRoot) {
        Objects.requireNonNull(pluginsRoot, "pluginsRoot");
        Path developmentRoot = developmentRoot(pluginsRoot);
        Path cacheRoot = cacheRoot(developmentRoot);
        if (!Files.isDirectory(developmentRoot)) {
            return new DevelopmentDiscovery(developmentRoot, cacheRoot, List.of(), List.of());
        }
        Path ignoredPluginsRoot = pluginsRoot.toAbsolutePath().normalize();
        try (Stream<Path> stream = Files.list(developmentRoot)) {
            List<Path> moduleRoots = stream
                    .filter(Files::isDirectory)
                    .filter(path -> !path.toAbsolutePath().normalize().equals(ignoredPluginsRoot))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            List<DevelopmentPluginArtifact> artifacts = moduleRoots.stream()
                    .map(PluginDevelopmentArtifacts::toCompiledArtifact)
                    .flatMap(OptionalStream::stream)
                    .toList();
            List<DevelopmentSourceModule> sourceOnlyModules = moduleRoots.stream()
                    .map(PluginDevelopmentArtifacts::toSourceOnlyModule)
                    .flatMap(OptionalStream::stream)
                    .toList();
            return new DevelopmentDiscovery(developmentRoot, cacheRoot, artifacts, sourceOnlyModules);
        } catch (IOException e) {
            throw new PluginRuntimeOperationException("failed to scan plugin development root "
                    + developmentRoot, e);
        }
    }

    public static List<MaterializedDevelopmentPlugin> dependencyOrder(
            List<MaterializedDevelopmentPlugin> plugins) {
        if (plugins == null || plugins.isEmpty()) {
            return List.of();
        }
        Map<String, MaterializedDevelopmentPlugin> byId = new LinkedHashMap<>();
        for (MaterializedDevelopmentPlugin plugin : plugins) {
            byId.putIfAbsent(plugin.descriptor().id(), plugin);
        }
        List<MaterializedDevelopmentPlugin> ordered = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (MaterializedDevelopmentPlugin plugin : plugins) {
            visit(plugin, byId, visiting, visited, ordered);
        }
        return List.copyOf(ordered);
    }

    public static DevelopmentCacheSession openSession(Path cacheRoot) {
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Path normalizedCacheRoot = cacheRoot.toAbsolutePath().normalize();
        Path sessionRoot = null;
        try {
            Files.createDirectories(normalizedCacheRoot);
            sessionRoot = Files.createTempDirectory(normalizedCacheRoot, ".session-");
            String token = UUID.randomUUID().toString();
            Files.writeString(sessionRoot.resolve(SESSION_MARKER), token, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return new DevelopmentCacheSession(normalizedCacheRoot, sessionRoot, token);
        } catch (IOException e) {
            cleanupAfterFailure(sessionRoot, e);
            throw new PluginRuntimeOperationException("failed to open plugin development cache session under "
                    + normalizedCacheRoot, e);
        }
    }

    public static MaterializedDevelopmentPlugin materialize(
            DevelopmentPluginArtifact artifact, DevelopmentCacheSession session) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(session, "session");
        synchronized (session) {
            return materializeInSession(artifact, session);
        }
    }

    private static MaterializedDevelopmentPlugin materializeInSession(
            DevelopmentPluginArtifact artifact, DevelopmentCacheSession session) {
        PluginDescriptor descriptor = PluginPackageReader.inspectDescriptor(artifact.descriptorPath());
        List<String> descriptorErrors = descriptor.externalValidationErrors();
        if (!descriptorErrors.isEmpty()) {
            throw new PluginRuntimeOperationException("invalid development plugin descriptor in "
                    + artifact.descriptorPath() + ": " + String.join("; ", descriptorErrors));
        }
        Path snapshot = null;
        try {
            Path sessionRoot = session.requireOpenRoot();
            snapshot = Files.createTempDirectory(sessionRoot,
                    snapshotDirectoryPrefix(descriptor, session.nextSequence()));
            Files.copy(artifact.descriptorPath(), snapshot.resolve(PLUGIN_PROPERTIES),
                    StandardCopyOption.REPLACE_EXISTING);
            copyDevelopmentClasses(artifact.classesDirectory(), snapshot.resolve(CLASSES_DIR));
            copyPrivateLibraries(artifact.classesDirectory(), snapshot.resolve(LIB_DIR));
            Files.writeString(snapshot.resolve(SNAPSHOT_MARKER),
                    session.snapshotMarker(descriptor), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return new MaterializedDevelopmentPlugin(artifact.moduleRoot(),
                    artifact.classesDirectory(), snapshot, descriptor);
        } catch (IOException e) {
            cleanupAfterFailure(snapshot, e);
            throw new PluginRuntimeOperationException("failed to materialize development plugin module "
                    + artifact.moduleRoot(), e);
        } catch (RuntimeException e) {
            cleanupAfterFailure(snapshot, e);
            throw e;
        }
    }

    private static Path developmentRoot(Path pluginsRoot) {
        String configured = System.getProperty(ROOT_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        Path absolutePluginsRoot = pluginsRoot.toAbsolutePath().normalize();
        Path parent = absolutePluginsRoot.getParent();
        Path defaultRoot = parent != null ? parent : Path.of("").toAbsolutePath().normalize();
        return discoverRepositoryRoot(defaultRoot).orElse(defaultRoot);
    }

    private static Optional<Path> discoverRepositoryRoot(Path start) {
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml")) && hasDirectPluginModule(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    private static boolean hasDirectPluginModule(Path candidate) {
        try (Stream<Path> stream = Files.list(candidate)) {
            return stream
                    .filter(Files::isDirectory)
                    .anyMatch(path -> !TARGET_DIR.equals(path.getFileName().toString())
                            && Files.isRegularFile(path.resolve("src/main/resources").resolve(PLUGIN_PROPERTIES)));
        } catch (IOException e) {
            return false;
        }
    }

    private static Path cacheRoot(Path developmentRoot) {
        return developmentRoot.resolve(TARGET_DIR).resolve(CACHE_DIR).toAbsolutePath().normalize();
    }

    private static void visit(MaterializedDevelopmentPlugin plugin,
                              Map<String, MaterializedDevelopmentPlugin> byId,
                              Set<String> visiting,
                              Set<String> visited,
                              List<MaterializedDevelopmentPlugin> ordered) {
        String id = plugin.descriptor().id();
        if (visited.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            ordered.add(plugin);
            visited.add(id);
            return;
        }
        for (var dependency : plugin.descriptor().dependencies()) {
            MaterializedDevelopmentPlugin target = byId.get(dependency.pluginId());
            if (target != null) {
                visit(target, byId, visiting, visited, ordered);
            }
        }
        visiting.remove(id);
        if (visited.add(id)) {
            ordered.add(plugin);
        }
    }

    private static OptionalStream<DevelopmentPluginArtifact> toCompiledArtifact(Path moduleRoot) {
        Path classesDirectory = moduleRoot.resolve(TARGET_DIR).resolve(CLASSES_DIR);
        Path descriptorPath = classesDirectory.resolve(PLUGIN_PROPERTIES);
        if (!Files.isRegularFile(descriptorPath)) {
            return OptionalStream.empty();
        }
        return OptionalStream.of(new DevelopmentPluginArtifact(moduleRoot.toAbsolutePath().normalize(),
                classesDirectory.toAbsolutePath().normalize(), descriptorPath.toAbsolutePath().normalize()));
    }

    private static OptionalStream<DevelopmentSourceModule> toSourceOnlyModule(Path moduleRoot) {
        Path sourceDescriptor = moduleRoot.resolve("src/main/resources").resolve(PLUGIN_PROPERTIES);
        if (!Files.isRegularFile(sourceDescriptor)) {
            return OptionalStream.empty();
        }
        Path compiledDescriptor = moduleRoot.resolve(TARGET_DIR).resolve(CLASSES_DIR).resolve(PLUGIN_PROPERTIES);
        if (Files.isRegularFile(compiledDescriptor)) {
            return OptionalStream.empty();
        }
        String pluginId = moduleRoot.getFileName().toString();
        try {
            PluginDescriptor descriptor = PluginPackageReader.inspectDescriptor(sourceDescriptor);
            if (descriptor.id() != null && !descriptor.id().isBlank()) {
                pluginId = descriptor.id();
            }
        } catch (RuntimeException ignored) {
            // Keep the module name fallback; the materialized path will report descriptor parsing errors when present.
        }
        return OptionalStream.of(new DevelopmentSourceModule(moduleRoot.toAbsolutePath().normalize(),
                sourceDescriptor.toAbsolutePath().normalize(), pluginId));
    }

    private static String snapshotDirectoryPrefix(PluginDescriptor descriptor, long sequence) {
        return safeSegment(descriptor.id()) + "-" + safeSegment(descriptor.version()) + "-" + sequence + "-";
    }

    private static String safeSegment(String value) {
        String text = value == null || value.isBlank() ? "unknown" : value;
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void copyDevelopmentClasses(Path sourceRoot, Path targetRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            for (Path source : stream.sorted(Comparator.comparingInt(Path::getNameCount)).toList()) {
                if (source.equals(sourceRoot)) {
                    Files.createDirectories(targetRoot);
                    continue;
                }
                Path relative = sourceRoot.relativize(source);
                if (skipClassPathEntry(relative)) {
                    continue;
                }
                copyPath(source, targetRoot.resolve(relative));
            }
        }
    }

    private static boolean skipClassPathEntry(Path relative) {
        if (relative.getNameCount() == 0) {
            return true;
        }
        if (relative.getNameCount() == 1 && PLUGIN_PROPERTIES.equals(relative.getFileName().toString())) {
            return true;
        }
        return LIB_DIR.equals(relative.getName(0).toString());
    }

    private static void copyPrivateLibraries(Path classesDirectory, Path targetRoot) throws IOException {
        Path sourceRoot = classesDirectory.resolve(LIB_DIR);
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        copyDirectory(sourceRoot, targetRoot);
    }

    private static void copyDirectory(Path sourceRoot, Path targetRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            for (Path source : stream.sorted(Comparator.comparingInt(Path::getNameCount)).toList()) {
                if (source.equals(sourceRoot)) {
                    Files.createDirectories(targetRoot);
                    continue;
                }
                copyPath(source, targetRoot.resolve(sourceRoot.relativize(source)));
            }
        }
    }

    private static void copyPath(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            Files.createDirectories(target);
            return;
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void cleanupAfterFailure(Path root, Throwable failure) {
        if (root == null) {
            return;
        }
        try {
            deleteRecursively(root);
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void deleteOwnedSession(Path sessionRoot, String token) throws IOException {
        Path marker = sessionRoot.resolve(SESSION_MARKER);
        IOException previousFailure = null;
        for (int attempt = 1; attempt <= SESSION_DELETE_ATTEMPTS; attempt++) {
            try {
                deleteSessionContents(sessionRoot, marker);
                Files.deleteIfExists(marker);
                Files.deleteIfExists(sessionRoot);
                return;
            } catch (IOException failure) {
                if (Files.notExists(sessionRoot, LinkOption.NOFOLLOW_LINKS)) {
                    return;
                }
                restoreSessionMarker(sessionRoot, marker, token, failure);
                if (previousFailure != null) {
                    failure.addSuppressed(previousFailure);
                }
                previousFailure = failure;
                if (attempt == SESSION_DELETE_ATTEMPTS) {
                    throw failure;
                }
                try {
                    Thread.sleep(SESSION_DELETE_RETRY_MILLIS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    IOException interruptedFailure = new IOException(
                            "interrupted while deleting plugin development cache session " + sessionRoot,
                            interrupted);
                    interruptedFailure.addSuppressed(failure);
                    throw interruptedFailure;
                }
            }
        }
    }

    private static void deleteSessionContents(Path sessionRoot, Path marker) throws IOException {
        try (Stream<Path> walk = Files.walk(sessionRoot)) {
            try {
                for (Path path : walk
                        .filter(candidate -> !candidate.equals(sessionRoot) && !candidate.equals(marker))
                        .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                        .toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (UncheckedIOException failure) {
                throw failure.getCause();
            }
        }
    }

    private static void restoreSessionMarker(
            Path sessionRoot, Path marker, String token, IOException deletionFailure) throws IOException {
        try {
            if (Files.isSymbolicLink(sessionRoot)
                    || !Files.isDirectory(sessionRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("unsafe plugin development cache session root after cleanup failure: "
                        + sessionRoot);
            }
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                        || !token.equals(Files.readString(marker, StandardCharsets.UTF_8))) {
                    throw new IOException("plugin development cache session marker changed during cleanup: "
                            + marker);
                }
                return;
            }
            Files.writeString(marker, token, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException restoreFailure) {
            deletionFailure.addSuppressed(restoreFailure);
            throw deletionFailure;
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record DevelopmentDiscovery(
            Path developmentRoot,
            Path cacheRoot,
            List<DevelopmentPluginArtifact> artifacts,
            List<DevelopmentSourceModule> sourceOnlyModules) {

        public DevelopmentDiscovery {
            Objects.requireNonNull(developmentRoot, "developmentRoot");
            Objects.requireNonNull(cacheRoot, "cacheRoot");
            artifacts = List.copyOf(artifacts);
            sourceOnlyModules = List.copyOf(sourceOnlyModules);
        }
    }

    public record DevelopmentPluginArtifact(
            Path moduleRoot,
            Path classesDirectory,
            Path descriptorPath) {

        public DevelopmentPluginArtifact {
            Objects.requireNonNull(moduleRoot, "moduleRoot");
            Objects.requireNonNull(classesDirectory, "classesDirectory");
            Objects.requireNonNull(descriptorPath, "descriptorPath");
        }
    }

    public record DevelopmentSourceModule(
            Path moduleRoot,
            Path descriptorPath,
            String pluginId) {

        public DevelopmentSourceModule {
            Objects.requireNonNull(moduleRoot, "moduleRoot");
            Objects.requireNonNull(descriptorPath, "descriptorPath");
            Objects.requireNonNull(pluginId, "pluginId");
        }
    }

    public record MaterializedDevelopmentPlugin(
            Path moduleRoot,
            Path classesDirectory,
            Path pf4jLoadPath,
            PluginDescriptor descriptor) {

        public MaterializedDevelopmentPlugin {
            Objects.requireNonNull(moduleRoot, "moduleRoot");
            Objects.requireNonNull(classesDirectory, "classesDirectory");
            Objects.requireNonNull(pf4jLoadPath, "pf4jLoadPath");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    public static final class DevelopmentCacheSession implements AutoCloseable {

        private final Path cacheRoot;
        private final Path sessionRoot;
        private final String token;
        private final AtomicLong snapshotSequence = new AtomicLong();
        private boolean closed;

        private DevelopmentCacheSession(Path cacheRoot, Path sessionRoot, String token) {
            this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
            this.sessionRoot = sessionRoot.toAbsolutePath().normalize();
            this.token = token;
        }

        public Path cacheRoot() {
            return cacheRoot;
        }

        public Path sessionRoot() {
            return sessionRoot;
        }

        private long nextSequence() {
            return snapshotSequence.incrementAndGet();
        }

        private synchronized Path requireOpenRoot() throws IOException {
            if (closed) {
                throw new IOException("plugin development cache session is closed: " + sessionRoot);
            }
            requireOwnedSessionRoot();
            return sessionRoot;
        }

        private String snapshotMarker(PluginDescriptor descriptor) {
            return token + "\n" + descriptor.id() + "\n" + descriptor.version() + "\n";
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            if (Files.notExists(sessionRoot, LinkOption.NOFOLLOW_LINKS)) {
                closed = true;
                return;
            }
            requireOwnedSessionRoot();
            deleteOwnedSession(sessionRoot, token);
            closed = true;
        }

        private void requireOwnedSessionRoot() throws IOException {
            if (!cacheRoot.equals(sessionRoot.getParent())
                    || Files.isSymbolicLink(sessionRoot)
                    || !Files.isDirectory(sessionRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("unsafe plugin development cache session root: " + sessionRoot);
            }
            Path marker = sessionRoot.resolve(SESSION_MARKER);
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("plugin development cache session marker is missing: " + marker);
            }
            String persistedToken = Files.readString(marker, StandardCharsets.UTF_8);
            if (!token.equals(persistedToken)) {
                throw new IOException("plugin development cache session marker does not match: " + marker);
            }
        }
    }

    private record OptionalStream<T>(T value) {

        private static <T> OptionalStream<T> of(T value) {
            return new OptionalStream<>(Objects.requireNonNull(value, "value"));
        }

        private static <T> OptionalStream<T> empty() {
            return new OptionalStream<>(null);
        }

        private Stream<T> stream() {
            return value == null ? Stream.empty() : Stream.of(value);
        }
    }
}
