package top.sywyar.pixivdownload.plugin.runtime.install.recovery;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionRecoveryReport.FailureKind;
import top.sywyar.pixivdownload.plugin.runtime.install.transaction.PluginTransactionState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** 恢复清单的字段、身份、路径与备份绑定校验器。 */
public final class PluginRecoveryManifestValidator {

    public static final String FORMAT_VERSION = "1";
    public static final String BACKUP_SUBDIRECTORY = "removed";
    public static final int MAX_BACKUPS = 256;

    private final Path pluginsRoot;
    private final PluginPackageLimits limits;
    private final PluginProvenanceStore provenanceStore;

    public PluginRecoveryManifestValidator(
            Path pluginsRoot,
            PluginPackageLimits limits,
            PluginProvenanceStore provenanceStore
    ) {
        this.pluginsRoot = Objects.requireNonNull(pluginsRoot, "pluginsRoot")
                .toAbsolutePath().normalize();
        this.limits = Objects.requireNonNull(limits, "limits");
        this.provenanceStore = Objects.requireNonNull(provenanceStore, "provenanceStore");
    }

    public RecoveryManifest validate(Path transaction, Properties properties)
            throws PluginRecoveryValidationException {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(properties, "properties");
        Path normalizedTransaction = transaction.toAbsolutePath().normalize();
        String formatVersion = requiredProperty(properties, "format.version");
        if (!FORMAT_VERSION.equals(formatVersion)) {
            throw invalidManifest("unsupported transaction manifest format.version: " + formatVersion);
        }
        String transactionId = requiredProperty(properties, "transaction.id");
        if (!transactionId.equals(normalizedTransaction.getFileName().toString())) {
            throw invalidManifest("transaction.id does not match its directory name");
        }
        if (!transactionId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw invalidManifest("transaction.id is not a safe token");
        }

        RecoveryOperation operation;
        try {
            operation = RecoveryOperation.valueOf(requiredProperty(properties, "operation"));
        } catch (IllegalArgumentException e) {
            throw invalidManifest("unknown transaction operation");
        }

        PluginTransactionState state;
        try {
            state = PluginTransactionState.valueOf(requiredProperty(properties, "state"));
        } catch (IllegalArgumentException e) {
            throw invalidManifest("unknown transaction state");
        }
        String packageId = requiredProperty(properties, "package.id");
        if (!PluginDescriptor.ID_PATTERN.matcher(packageId).matches()) {
            throw invalidManifest("invalid manifest package.id");
        }
        String version = properties.getProperty("version");
        if (version == null) {
            throw invalidManifest("missing manifest property: version");
        }

        String targetValue = properties.getProperty("target");
        if (targetValue == null) {
            throw invalidManifest("missing manifest property: target");
        }
        String stagedValue = properties.getProperty("staged");
        if (stagedValue == null) {
            throw invalidManifest("missing manifest property: staged");
        }
        boolean removal = operation == RecoveryOperation.REMOVE;
        if (removal != targetValue.isBlank() || removal != stagedValue.isBlank()) {
            throw invalidManifest(removal
                    ? "removal transaction must not declare a target"
                    : "install transaction must declare target and staged paths");
        }
        if (removal && state != PluginTransactionState.PREPARED
                && state != PluginTransactionState.ROLLING_BACK
                && state != PluginTransactionState.ROLLED_BACK
                && state != PluginTransactionState.COMMITTED) {
            throw invalidManifest("removal transaction has an unsupported state: " + state);
        }
        if (removal) {
            if (!version.isBlank()) {
                throw invalidManifest("removal transaction must not declare a version");
            }
        } else {
            validateVersion(version, "install transaction version");
        }

        Path target = targetValue.isBlank() ? null
                : validateArtifactPath(targetValue, pluginsRoot, "target");
        Path stagedRoot = normalizedTransaction.resolve("new");
        Path staged = stagedValue.isBlank() ? null
                : validateArtifactPath(stagedValue, stagedRoot, "staged artifact");
        ExpectedArtifact newArtifact = removal ? null : parseExpectedArtifact(properties, "artifact");
        if (!removal) {
            if (!packageId.equals(newArtifact.pluginId()) || !version.equals(newArtifact.version())) {
                throw invalidManifest("package identity does not match the staged artifact identity");
            }
            requireCanonicalArtifactName(target, packageId, version, "target");
            if (!target.getFileName().equals(staged.getFileName())) {
                throw invalidManifest("target and staged artifact names differ");
            }
            requireCanonicalArtifactName(staged, packageId, version, "staged artifact");
            if (!newArtifact.hasSidecar()) {
                throw invalidManifest("install transaction must bind the staged provenance sidecar");
            }
        } else if (!blankProperty(properties, "artifact.id")
                || !blankProperty(properties, "artifact.version")
                || !blankProperty(properties, "artifact.size")
                || !blankProperty(properties, "artifact.sha256")
                || !blankProperty(properties, "artifact.sidecar.sha256")) {
            throw invalidManifest("removal transaction must not declare a new artifact digest");
        }

        int replacesCount = parseBoundedCount(properties, "replaces.count", MAX_BACKUPS);
        Set<String> replaces = new LinkedHashSet<>();
        for (int i = 0; i < replacesCount; i++) {
            String replacedId = requiredProperty(properties, "replaces." + i);
            if (!PluginDescriptor.ID_PATTERN.matcher(replacedId).matches()
                    || replacedId.equals(packageId) || !replaces.add(replacedId)) {
                throw invalidManifest("invalid or duplicate replaced plugin id");
            }
        }
        if (removal && !replaces.isEmpty()) {
            throw invalidManifest("removal transaction must not declare replacement ids");
        }

        int backupCount = parseBoundedCount(properties, "backup.count", MAX_BACKUPS);
        if (removal && backupCount == 0) {
            throw invalidManifest("removal transaction must declare at least one backup");
        }

        Path removedRoot = normalizedTransaction.resolve(BACKUP_SUBDIRECTORY);
        if (backupCount > 0) {
            requirePathWithin(removedRoot, normalizedTransaction, "backup root");
            assertExistingPathComponentsSafe(removedRoot, "backup root");
            try {
                BasicFileAttributes attributes = readAttributesIfPresent(removedRoot).orElse(null);
                if (attributes != null && (attributes.isSymbolicLink() || attributes.isOther()
                        || !attributes.isDirectory())) {
                    throw unsafePath("backup root is not a plain directory: " + removedRoot);
                }
            } catch (IOException e) {
                throw unsafePath("backup root could not be inspected: " + describeFailure(e));
            }
        }

        List<RecoveryBackup> backups = new ArrayList<>(backupCount);
        Set<Path> claimedPaths = new LinkedHashSet<>();
        if (target != null) {
            claimedPaths.add(target);
            claimedPaths.add(staged);
        }
        for (int i = 0; i < backupCount; i++) {
            ExpectedArtifact expected = parseExpectedArtifact(properties, "backup." + i);
            if (removal && !packageId.equals(expected.pluginId())) {
                throw invalidManifest("removal backup identity does not match package.id");
            }
            if (!removal && !packageId.equals(expected.pluginId()) && !replaces.contains(expected.pluginId())) {
                throw invalidManifest("install backup identity is not owned or explicitly replaced");
            }
            Path origin = validateArtifactPath(requiredProperty(properties, "backup." + i + ".origin"),
                    pluginsRoot, "backup origin");
            Path backup = validateArtifactPath(requiredProperty(properties, "backup." + i + ".path"),
                    removedRoot, "backup path");
            String expectedBackupName = i + "-" + origin.getFileName();
            if (!backup.getFileName().toString().equals(expectedBackupName)) {
                throw invalidManifest("backup path is not bound to its index and origin name");
            }
            if (!claimedPaths.add(origin) || !claimedPaths.add(backup)) {
                throw invalidManifest("transaction declares a duplicate artifact path");
            }
            backups.add(new RecoveryBackup(expected, origin, backup));
        }
        validatePropertyKeys(properties, replacesCount, backupCount);
        return new RecoveryManifest(operation, state, packageId, version, target, staged,
                newArtifact, List.copyOf(replaces), List.copyOf(backups));
    }

    private static void validatePropertyKeys(Properties properties, int replacesCount, int backupCount)
            throws PluginRecoveryValidationException {
        Set<String> allowed = new LinkedHashSet<>(List.of(
                "format.version", "transaction.id", "operation", "state", "package.id", "version",
                "target", "staged", "artifact.id", "artifact.version", "artifact.size",
                "artifact.sha256", "artifact.sidecar.sha256", "replaces.count", "backup.count"));
        for (int i = 0; i < replacesCount; i++) {
            allowed.add("replaces." + i);
        }
        for (int i = 0; i < backupCount; i++) {
            String prefix = "backup." + i;
            allowed.add(prefix + ".id");
            allowed.add(prefix + ".version");
            allowed.add(prefix + ".size");
            allowed.add(prefix + ".sha256");
            allowed.add(prefix + ".sidecar.sha256");
            allowed.add(prefix + ".origin");
            allowed.add(prefix + ".path");
        }
        for (String key : properties.stringPropertyNames()) {
            if (!allowed.contains(key)) {
                throw invalidManifest("unknown transaction manifest property: " + key);
            }
        }
    }

    private Path validateArtifactPath(String value, Path expectedParent, String role)
            throws PluginRecoveryValidationException {
        Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException e) {
            throw unsafePath(role + " is not a valid path");
        }
        if (!path.isAbsolute()) {
            throw unsafePath(role + " must be absolute: " + value);
        }
        Path normalized = path.normalize();
        if (!path.equals(normalized)) {
            throw unsafePath(role + " must already be normalized: " + value);
        }
        if (normalized.getParent() == null || !normalized.getParent().equals(expectedParent)) {
            throw unsafePath(role + " escapes its expected root: " + value);
        }
        String name = normalized.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith(".") || !(name.endsWith(".jar") || name.endsWith(".zip"))) {
            throw unsafePath(role + " is not a plugin artifact path: " + value);
        }
        requirePathWithin(normalized, pluginsRoot, role);
        assertExistingPathComponentsSafe(normalized, role);
        try {
            BasicFileAttributes attributes = readAttributesIfPresent(normalized).orElse(null);
            if (attributes != null && (attributes.isSymbolicLink() || attributes.isOther()
                    || !attributes.isRegularFile())) {
                throw unsafePath(role + " is not a plain regular file: " + value);
            }
        } catch (IOException e) {
            throw unsafePath(role + " could not be inspected: " + describeFailure(e));
        }
        validateProvenancePath(normalized, role);
        return normalized;
    }

    private void validateProvenancePath(Path artifact, String role)
            throws PluginRecoveryValidationException {
        Path provenanceRoot = provenanceStore.provenanceDir().toAbsolutePath().normalize();
        for (Path configuredSidecar : provenanceStore.managedSidecarPaths(artifact)) {
            Path sidecar = configuredSidecar.toAbsolutePath().normalize();
            Path sidecarParent = sidecar.getParent();
            if (sidecarParent == null
                    || !sidecarParent.equals(artifact.getParent()) && !sidecarParent.equals(provenanceRoot)) {
                throw unsafePath(role + " provenance path escapes its expected root: " + sidecar);
            }
            requirePathWithin(sidecar, pluginsRoot, role + " provenance");
            assertExistingPathComponentsSafe(sidecar, role + " provenance");
            try {
                BasicFileAttributes parentAttributes = readAttributesIfPresent(sidecarParent).orElse(null);
                if (parentAttributes != null && (parentAttributes.isSymbolicLink() || parentAttributes.isOther()
                        || !parentAttributes.isDirectory())) {
                    throw unsafePath(role + " provenance root is not a plain directory: " + sidecarParent);
                }
                BasicFileAttributes attributes = readAttributesIfPresent(sidecar).orElse(null);
                if (attributes != null && (attributes.isSymbolicLink() || attributes.isOther()
                        || !attributes.isRegularFile())) {
                    throw unsafePath(role + " provenance is not a plain regular file: " + sidecar);
                }
            } catch (IOException e) {
                throw unsafePath(role + " provenance could not be inspected: " + describeFailure(e));
            }
        }
    }

    private void assertExistingPathComponentsSafe(Path path, String role)
            throws PluginRecoveryValidationException {
        Path normalizedPath = path.toAbsolutePath().normalize();
        requirePathWithin(normalizedPath, pluginsRoot, role);
        Path current = pluginsRoot.getRoot();
        for (Path component : pluginsRoot) {
            current = current == null ? component : current.resolve(component);
            assertPlainExistingComponent(current, role);
        }
        current = pluginsRoot;
        for (Path component : pluginsRoot.relativize(normalizedPath)) {
            current = current.resolve(component);
            if (!assertPlainExistingComponent(current, role)) {
                break;
            }
        }
    }

    private static boolean assertPlainExistingComponent(Path path, String role)
            throws PluginRecoveryValidationException {
        try {
            BasicFileAttributes attributes = readAttributesIfPresent(path).orElse(null);
            if (attributes == null) {
                return false;
            }
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw unsafePath(role + " traverses a symbolic link or reparse/special entry: " + path);
            }
            return true;
        } catch (IOException e) {
            throw unsafePath(role + " path component could not be inspected: " + describeFailure(e));
        }
    }

    private ExpectedArtifact parseExpectedArtifact(Properties properties, String prefix)
            throws PluginRecoveryValidationException {
        String pluginId = requiredProperty(properties, prefix + ".id");
        if (!PluginDescriptor.ID_PATTERN.matcher(pluginId).matches()) {
            throw invalidManifest("invalid " + prefix + " plugin id");
        }
        String version = requiredProperty(properties, prefix + ".version");
        validateVersion(version, prefix + " version");
        long size;
        try {
            size = Long.parseLong(requiredProperty(properties, prefix + ".size"));
        } catch (NumberFormatException e) {
            throw invalidManifest(prefix + ".size is not an integer");
        }
        if (size <= 0L) {
            throw invalidManifest(prefix + ".size must be positive");
        }
        if (size > limits.maxArchiveBytes()) {
            throw invalidManifest(prefix + ".size exceeds the configured archive limit");
        }
        String sha256 = requiredSha256(properties, prefix + ".sha256");
        String sidecarKey = prefix + ".sidecar.sha256";
        String sidecarValue = properties.getProperty(sidecarKey);
        if (sidecarValue == null) {
            throw invalidManifest("missing manifest property: " + sidecarKey);
        }
        String sidecarSha256 = sidecarValue.isBlank()
                ? "" : normalizeSha256(sidecarValue, sidecarKey);
        return new ExpectedArtifact(pluginId, version, size, sha256, sidecarSha256);
    }

    private static String requiredSha256(Properties properties, String key)
            throws PluginRecoveryValidationException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw invalidManifest("missing manifest property: " + key);
        }
        return normalizeSha256(value, key);
    }

    private static String normalizeSha256(String value, String key)
            throws PluginRecoveryValidationException {
        if (!value.matches("[0-9A-Fa-f]{64}")) {
            throw invalidManifest(key + " is not a SHA-256 digest");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean blankProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value != null && value.isBlank();
    }

    private static void validateVersion(String version, String role)
            throws PluginRecoveryValidationException {
        if (!version.equals(version.trim())
                || !version.matches("\\d+\\.\\d+\\.\\d+"
                + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")) {
            throw invalidManifest(role + " is not a valid semantic version");
        }
    }

    private static void requireCanonicalArtifactName(
            Path artifact,
            String pluginId,
            String version,
            String role
    ) throws PluginRecoveryValidationException {
        String actual = artifact.getFileName().toString();
        if (!actual.equals(pluginId + "-" + version + ".jar")
                && !actual.equals(pluginId + "-" + version + ".zip")) {
            throw invalidManifest(role + " is not canonically named for its declared identity");
        }
    }

    private static int parseBoundedCount(Properties properties, String key, int maximum)
            throws PluginRecoveryValidationException {
        String value = requiredProperty(properties, key);
        int count;
        try {
            count = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw invalidManifest(key + " is not an integer");
        }
        if (count < 0 || count > maximum) {
            throw invalidManifest(key + " is outside the supported range");
        }
        return count;
    }

    private static String requiredProperty(Properties properties, String key)
            throws PluginRecoveryValidationException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw invalidManifest("missing manifest property: " + key);
        }
        return value;
    }

    private static void requirePathWithin(Path path, Path expectedRoot, String role)
            throws PluginRecoveryValidationException {
        Path normalizedRoot = expectedRoot.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw unsafePath(role + " escapes its expected root: " + path);
        }
    }

    private static Optional<BasicFileAttributes> readAttributesIfPresent(Path path) throws IOException {
        try {
            return Optional.of(Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    private static PluginRecoveryValidationException invalidManifest(String message) {
        return new PluginRecoveryValidationException(FailureKind.INVALID_MANIFEST, message);
    }

    private static PluginRecoveryValidationException unsafePath(String message) {
        return new PluginRecoveryValidationException(FailureKind.UNSAFE_PATH, message);
    }

    private static String describeFailure(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getName() : error.getMessage();
    }

    public enum RecoveryOperation {
        INSTALL,
        REMOVE
    }

    public record ExpectedArtifact(
            String pluginId,
            String version,
            long size,
            String sha256,
            String sidecarSha256
    ) {

        public boolean hasSidecar() {
            return sidecarSha256 != null && !sidecarSha256.isBlank();
        }
    }

    public record RecoveryBackup(ExpectedArtifact expected, Path origin, Path backup) {
    }

    public record RecoveryManifest(
            RecoveryOperation operation,
            PluginTransactionState state,
            String packageId,
            String version,
            Path target,
            Path staged,
            ExpectedArtifact newArtifact,
            List<String> replaces,
            List<RecoveryBackup> backups
    ) {

        public Set<Path> claimedArtifactPaths() {
            Set<Path> claims = new LinkedHashSet<>();
            if (target != null) {
                claims.add(target);
            }
            if (staged != null) {
                claims.add(staged);
            }
            for (RecoveryBackup backup : backups) {
                claims.add(backup.origin());
                claims.add(backup.backup());
            }
            return Set.copyOf(claims);
        }
    }
}
