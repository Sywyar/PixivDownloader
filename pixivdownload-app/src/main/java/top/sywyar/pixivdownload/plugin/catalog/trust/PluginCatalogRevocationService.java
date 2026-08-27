package top.sywyar.pixivdownload.plugin.catalog.trust;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogTrustStores;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginCatalogClientProvider;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.security.PluginCatalogStrictJson;
import top.sywyar.pixivdownload.plugin.signature.PluginRevocationsVerificationRequest;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationPolicy;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 拉取、验签并应用仓库吊销文档；失败时只保留既有最后有效快照。 */
@Service
public final class PluginCatalogRevocationService {

    private static final long MAX_DOCUMENT_BYTES = 512L * 1024L;
    private static final long MAX_SIGNATURE_BYTES = 16L * 1024L;
    private static final Duration GRACE = Duration.ofHours(24);
    private static final Set<String> SCOPES = Set.of(
            "PACKAGE_SHA256", "PLUGIN_VERSION", "SIGNING_KEY", "PUBLISHER");
    private static final Set<String> ACTIONS = Set.of("YANKED", "REVOKED");
    private final PluginCatalogClientProvider clients;
    private final PluginCatalogTrustStateStore stateStore;
    private final ObjectMapper mapper = PluginCatalogStrictJson.mapper(true);

    public PluginCatalogRevocationService(PluginCatalogClientProvider clients,
                                          PluginCatalogTrustStateStore stateStore) {
        this.clients = clients;
        this.stateStore = stateStore;
    }

    /** 安装前刷新必选吊销源；首拉取失败或快照过期超过宽限期时 fail-closed。 */
    public PluginCatalogTrustStateStore.RevocationSnapshot requireCurrent(PluginRepository repository) {
        if (!repository.revocationsRequired()) return null;
        try {
            return refresh(repository);
        } catch (RuntimeException failure) {
            return stateStore.revocations(repository.repositoryId())
                    .filter(this::withinGrace)
                    .orElseThrow(() -> new PluginCatalogException(PluginCatalogErrorCode.REVOCATION_UNAVAILABLE,
                            "no current verified revocation snapshot for " + repository.repositoryId()
                                    + ": " + failure.getMessage()));
        }
    }

    public PluginCatalogTrustStateStore.RevocationSnapshot refresh(PluginRepository repository) {
        byte[] bytes = clients.clientFor(repository).fetchBytes(repository.revocationsUrl(), MAX_DOCUMENT_BYTES);
        byte[] signatureBytes = clients.clientFor(repository)
                .fetchBytes(repository.revocationsUrl() + ".sig", MAX_SIGNATURE_BYTES);
        PluginRevocationDocument document = parse(bytes, PluginRevocationDocument.class, "revocation document");
        validate(repository, document);
        SignatureMetadata signature = parse(signatureBytes, SignatureMetadata.class, "revocation signature");
        VerificationPolicy policy = repository.official()
                ? VerificationPolicy.installedOfficial() : VerificationPolicy.installedCustom();
        VerificationResult verification = PluginCatalogTrustStores.verifierForRepository(repository)
                .verifyPluginRevocations(new PluginRevocationsVerificationRequest(
                        bytes, repository.repositoryId(), document.sequence(), signature, policy));
        if (!verification.accepted()) {
            throw rejected("revocation signature rejected: " + verification.diagnosticCode());
        }

        String sha256 = sha256(bytes);
        PluginCatalogTrustStateStore.RevocationSnapshot previous = stateStore
                .revocations(repository.repositoryId()).orElse(null);
        if (previous != null && (document.sequence() < previous.sequence()
                || document.sequence() == previous.sequence()
                && !sha256.equalsIgnoreCase(previous.documentSha256()))) {
            throw rejected("revocation rollback or sequence equivocation rejected");
        }
        PluginCatalogTrustStateStore.RevocationSnapshot snapshot = new PluginCatalogTrustStateStore.RevocationSnapshot(
                document.sequence(), sha256, document.generatedTime(), document.nextUpdate(), Instant.now().toString(),
                document.entries().stream().map(PluginCatalogRevocationService::snapshotEntry).toList());
        try {
            stateStore.acceptRevocations(repository.repositoryId(), snapshot);
        } catch (IOException failure) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_CONFIG_WRITE_FAILED,
                    "failed to persist verified revocations: " + failure.getMessage());
        }
        return snapshot;
    }

    /** YANKED 与 REVOKED 均阻断新安装/更新。 */
    public void requireInstallAllowed(PluginRepository repository, String pluginId, PluginCatalogPackage pkg) {
        PluginCatalogTrustStateStore.RevocationSnapshot snapshot = requireCurrent(repository);
        if (snapshot == null) return;
        String keyId = pkg.signature() != null ? pkg.signature().keyId() : null;
        for (PluginCatalogTrustStateStore.RevocationEntry entry : snapshot.entries()) {
            if (matches(entry, repository, pluginId, pkg.version(), pkg.sha256(), keyId)) {
                throw new PluginCatalogException(PluginCatalogErrorCode.REVOCATION_REJECTED, pluginId, pkg.version(),
                        "plugin package is " + entry.action().toLowerCase(Locale.ROOT)
                                + " by verified revocation document: " + entry.reasonCode());
            }
        }
    }

    /** 已验证最后有效快照中的 YANKED 项不进入默认市场推荐页；详情仍可诊断，安装仍由上面的强制门裁定。 */
    public boolean isYanked(PluginRepository repository, String pluginId, PluginCatalogPackage pkg) {
        if (repository == null || pkg == null) return false;
        String keyId = pkg.signature() != null ? pkg.signature().keyId() : null;
        return stateStore.revocations(repository.repositoryId()).stream()
                .flatMap(snapshot -> snapshot.entries().stream())
                .anyMatch(entry -> "YANKED".equals(entry.action())
                        && matches(entry, repository, pluginId, pkg.version(), pkg.sha256(), keyId));
    }

    public static boolean matches(PluginCatalogTrustStateStore.RevocationEntry entry, PluginRepository repository,
                                  String pluginId, String version, String sha256, String keyId) {
        if (entry == null || !"REVOKED".equals(entry.action()) && !"YANKED".equals(entry.action())
                || !isEffective(entry.effectiveTime())) return false;
        return switch (entry.scope()) {
            case "PACKAGE_SHA256" -> equalsIgnoreCase(entry.packageSha256(), sha256);
            case "PLUGIN_VERSION" -> equals(entry.pluginId(), pluginId) && equals(entry.version(), version);
            case "SIGNING_KEY" -> equals(entry.keyId(), keyId);
            case "PUBLISHER" -> equals(entry.publisherId(), repository.publisherId());
            default -> false;
        };
    }

    private boolean withinGrace(PluginCatalogTrustStateStore.RevocationSnapshot snapshot) {
        try {
            return Instant.now().isBefore(Instant.parse(snapshot.nextUpdate()).plus(GRACE));
        } catch (DateTimeParseException failure) {
            return false;
        }
    }

    private <T> T parse(byte[] bytes, Class<T> type, String label) {
        try {
            return mapper.readValue(PluginCatalogStrictJson.strictUtf8(bytes), type);
        } catch (Exception failure) {
            throw rejected("invalid " + label + ": " + failure.getMessage());
        }
    }

    private static void validate(PluginRepository repository, PluginRevocationDocument document) {
        if (document == null || document.schemaVersion() == null || document.schemaVersion() != 1
                || !repository.repositoryId().equals(document.repositoryId())
                || document.sequence() == null || document.sequence() <= 0
                || document.entries() == null || document.entries().size() > 10_000) {
            throw rejected("revocation document identity, sequence or entry count is invalid");
        }
        Instant generated = instant(document.generatedTime(), "generatedTime");
        Instant next = instant(document.nextUpdate(), "nextUpdate");
        if (!next.isAfter(generated)) throw rejected("nextUpdate must follow generatedTime");
        Set<String> identities = new HashSet<>();
        for (PluginRevocationDocument.Entry entry : document.entries()) {
            String action = normalizedAction(entry);
            if (entry == null || !SCOPES.contains(entry.scope()) || !ACTIONS.contains(action)
                    || entry.reasonCode() == null || entry.reasonCode().isBlank()) {
                throw rejected("revocation entry scope, action or reasonCode is invalid");
            }
            instant(entry.effectiveTime(), "effectiveTime");
            String identity = entry.scope() + '|' + entry.pluginId() + '|' + entry.version() + '|'
                    + entry.packageSha256() + '|' + entry.keyId() + '|' + entry.publisherId();
            if (!identities.add(identity)) throw rejected("duplicate revocation entry");
            boolean complete = switch (entry.scope()) {
                case "PACKAGE_SHA256" -> isSha256(entry.packageSha256());
                case "PLUGIN_VERSION" -> hasText(entry.pluginId()) && hasText(entry.version());
                case "SIGNING_KEY" -> hasText(entry.keyId());
                case "PUBLISHER" -> hasText(entry.publisherId());
                default -> false;
            };
            if (!complete) throw rejected("revocation entry selector is incomplete");
        }
    }

    private static PluginCatalogTrustStateStore.RevocationEntry snapshotEntry(PluginRevocationDocument.Entry entry) {
        return new PluginCatalogTrustStateStore.RevocationEntry(entry.scope(), entry.pluginId(), entry.version(),
                entry.packageSha256(), entry.keyId(), entry.publisherId(), normalizedAction(entry), entry.reasonCode(),
                entry.effectiveTime());
    }

    private static String normalizedAction(PluginRevocationDocument.Entry entry) {
        return entry == null || !hasText(entry.action()) ? "REVOKED" : entry.action();
    }

    private static boolean isEffective(String value) {
        try {
            return !Instant.parse(value).isAfter(Instant.now());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static Instant instant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (Exception failure) {
            throw rejected(field + " must be an ISO-8601 instant");
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static boolean equals(String left, String right) { return left != null && left.equals(right); }
    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static PluginCatalogException rejected(String detail) {
        return new PluginCatalogException(PluginCatalogErrorCode.REVOCATION_REJECTED, detail);
    }
}
