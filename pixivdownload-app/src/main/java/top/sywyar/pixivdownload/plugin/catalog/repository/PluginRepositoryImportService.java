package top.sywyar.pixivdownload.plugin.catalog.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.config.PluginRepositoryConfigEditor;
import top.sywyar.pixivdownload.plugin.api.gui.RepositoryConfigEntry;
import top.sywyar.pixivdownload.plugin.api.gui.TrustedKeyConfigEntry;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogHttpClient;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogTrustStores;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.security.PluginCatalogStrictJson;
import top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore;
import top.sywyar.pixivdownload.plugin.signature.RepositoryUpdateVerificationRequest;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.VerificationPolicy;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** repository.json 的预览、二次拉取确认与配置快照写入。 */
@Service
public final class PluginRepositoryImportService {

    private static final long MAX_UPDATE_BYTES = 64L * 1024L;
    private static final long MAX_SIGNATURE_BYTES = 16L * 1024L;
    private final PluginRepositoryRegistry registry;
    private final PluginCatalogClientProvider clients;
    private final PluginCatalogTrustStateStore stateStore;
    private final RepositoryDescriptorParser parser = new RepositoryDescriptorParser();
    private final ObjectMapper mapper = PluginCatalogStrictJson.mapper(true);

    public PluginRepositoryImportService(PluginRepositoryRegistry registry,
                                         PluginCatalogClientProvider clients,
                                         PluginCatalogTrustStateStore stateStore) {
        this.registry = registry;
        this.clients = clients;
        this.stateStore = stateStore;
    }

    public RepositoryImportPreview preview(String descriptorUrl) {
        ParsedRepositoryDescriptor parsed = fetch(descriptorUrl);
        PluginRepository existing = registry.find(parsed.descriptor().repositoryId()).orElse(null);
        boolean conflict = existing != null && (existing.builtIn() || existing.descriptorUrl() == null
                || !existing.descriptorUrl().equals(parsed.descriptorUrl()));
        boolean keysChanged = existing != null && !fingerprints(existing.trustedKeys())
                .equals(fingerprints(parsed.trustedKeys()));
        boolean networkExpanded = existing != null && !knownHosts(existing).containsAll(parsed.networkHosts());
        String proofStatus = existing == null || !keysChanged && !networkExpanded
                && equalsIgnoreCase(existing.descriptorSha256(), parsed.descriptorSha256())
                ? "NOT_REQUIRED" : updateProofStatus(existing, parsed);
        RepositoryDescriptor descriptor = parsed.descriptor();
        return new RepositoryImportPreview(parsed.descriptorUrl(), host(parsed.descriptorUrl()),
                parsed.descriptorSha256(), descriptor.repositoryId(), descriptor.displayName(),
                descriptor.publisher().id(), descriptor.publisher().displayName(),
                descriptor.publisher().homepageUrl(), host(descriptor.publisher().homepageUrl()),
                descriptor.catalog().protocol(), descriptor.catalog().endpoint(), host(descriptor.catalog().endpoint()),
                descriptor.revocationsUrl(), host(descriptor.revocationsUrl()),
                descriptor.updateProofUrl(), host(descriptor.updateProofUrl()), parsed.networkHosts(),
                descriptor.networkProfile(), parsed.effectiveProxyPolicy(), parsed.redirectBoundary(),
                parsed.keyPreviews(), "NOT_AVAILABLE", null, List.of(), existing != null, conflict,
                keysChanged, networkExpanded, proofStatus, true,
                "import.executable-warning");
    }

    public RepositoryTrustResult trust(String descriptorUrl, String expectedDescriptorSha256,
                                       boolean trustConfirmed) {
        if (!trustConfirmed) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_TRUST_CONFIRMATION_REQUIRED,
                    "explicit repository trust confirmation is required");
        }
        if (expectedDescriptorSha256 == null || !expectedDescriptorSha256.matches("[0-9a-fA-F]{64}")) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_DESCRIPTOR_CHANGED,
                    "expectedDescriptorSha256 must be a complete SHA-256 digest");
        }
        ParsedRepositoryDescriptor parsed = fetch(descriptorUrl);
        if (!parsed.descriptorSha256().equalsIgnoreCase(expectedDescriptorSha256)) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_DESCRIPTOR_CHANGED,
                    "repository descriptor bytes changed after preview");
        }
        PluginRepository existing = registry.find(parsed.descriptor().repositoryId()).orElse(null);
        if (existing != null && (existing.builtIn() || existing.descriptorUrl() == null
                || !existing.descriptorUrl().equals(parsed.descriptorUrl()))) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_ID_CONFLICT,
                    "repositoryId conflicts with an existing or built-in repository");
        }
        boolean updateRequired = existing != null && (!equalsIgnoreCase(
                existing.descriptorSha256(), parsed.descriptorSha256())
                || !fingerprints(existing.trustedKeys()).equals(fingerprints(parsed.trustedKeys()))
                || !knownHosts(existing).containsAll(parsed.networkHosts()));
        RepositoryUpdateDocument proof = updateRequired ? verifiedUpdateProof(existing, parsed, true) : null;
        writeConfiguration(parsed);
        if (proof != null) {
            try {
                stateStore.acceptUpdateSequence(parsed.descriptor().repositoryId(), proof.sequence());
            } catch (IOException failure) {
                throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_CONFIG_WRITE_FAILED,
                        "repository saved but update sequence state could not be written: " + failure.getMessage());
            }
        }
        return new RepositoryTrustResult(parsed.descriptor().repositoryId(), parsed.descriptorSha256(),
                true, true, "SELF_TRUSTED");
    }

    private ParsedRepositoryDescriptor fetch(String descriptorUrl) {
        try {
            RepositoryDescriptorParser.publicHttps(descriptorUrl, false,
                    PluginCatalogErrorCode.REPOSITORY_DESCRIPTOR_URL_INVALID, "descriptorUrl");
            byte[] bytes = new PluginCatalogHttpClient(true, false, 10_000, 20_000)
                    .fetchBytes(descriptorUrl, RepositoryDescriptorParser.MAX_DESCRIPTOR_BYTES);
            return parser.parse(descriptorUrl, bytes);
        } catch (PluginCatalogException failure) {
            if (failure.code() == PluginCatalogErrorCode.DOWNLOAD_FAILED
                    || failure.code() == PluginCatalogErrorCode.BLOCKED_ADDRESS
                    || failure.code() == PluginCatalogErrorCode.INSECURE_URL) {
                throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_DESCRIPTOR_FETCH_REJECTED,
                        failure.getMessage());
            }
            throw failure;
        }
    }

    private String updateProofStatus(PluginRepository existing, ParsedRepositoryDescriptor parsed) {
        if (existing == null || existing.updateProofUrl() == null) return "NOT_AVAILABLE";
        try {
            verifiedUpdateProof(existing, parsed, false);
            return "VERIFIED";
        } catch (PluginCatalogException failure) {
            return "INVALID";
        }
    }

    private RepositoryUpdateDocument verifiedUpdateProof(PluginRepository existing,
                                                          ParsedRepositoryDescriptor parsed,
                                                          boolean required) {
        if (existing == null || existing.updateProofUrl() == null) {
            if (required) throw updateInvalid("existing descriptor does not declare updateProofUrl");
            return null;
        }
        try {
            byte[] documentBytes = clients.clientFor(existing)
                    .fetchBytes(existing.updateProofUrl(), MAX_UPDATE_BYTES);
            byte[] signatureBytes = clients.clientFor(existing)
                    .fetchBytes(existing.updateProofUrl() + ".sig", MAX_SIGNATURE_BYTES);
            RepositoryUpdateDocument document = mapper.readValue(
                    PluginCatalogStrictJson.strictUtf8(documentBytes), RepositoryUpdateDocument.class);
            SignatureMetadata signature = mapper.readValue(
                    PluginCatalogStrictJson.strictUtf8(signatureBytes), SignatureMetadata.class);
            validateUpdate(existing, parsed, document, signature);
            VerificationPolicy policy = existing.official()
                    ? VerificationPolicy.installedOfficial() : VerificationPolicy.installedCustom();
            VerificationResult result = PluginCatalogTrustStores.verifierForRepository(existing)
                    .verifyRepositoryUpdate(new RepositoryUpdateVerificationRequest(documentBytes,
                            existing.repositoryId(), document.sequence(), signature, policy));
            if (!result.accepted()) throw updateInvalid("update proof signature rejected: " + result.diagnosticCode());
            return document;
        } catch (PluginCatalogException failure) {
            throw failure;
        } catch (Exception failure) {
            throw updateInvalid("invalid repository update proof: " + failure.getMessage());
        }
    }

    private void validateUpdate(PluginRepository existing, ParsedRepositoryDescriptor parsed,
                                RepositoryUpdateDocument document, SignatureMetadata signature) {
        if (document != null && document.sequence() != null
                && document.sequence() <= stateStore.updateSequence(existing.repositoryId())) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_UPDATE_ROLLBACK,
                    "repository update proof sequence did not advance");
        }
        Set<String> updateFingerprints = document == null || document.newKeyFingerprints() == null
                ? Set.of() : new HashSet<>(document.newKeyFingerprints());
        if (document == null || document.schemaVersion() == null || document.schemaVersion() != 1
                || !existing.repositoryId().equals(document.repositoryId()) || document.sequence() == null
                || !equalsIgnoreCase(existing.descriptorSha256(), document.previousDescriptorSha256())
                || !parsed.descriptorUrl().equals(document.newDescriptorUrl())
                || !parsed.descriptorSha256().equalsIgnoreCase(document.newDescriptorSha256())
                || signature == null || !Objects.equals(signature.keyId(), document.signedByKeyId())
                || document.newKeyFingerprints() == null
                || updateFingerprints.size() != document.newKeyFingerprints().size()
                || !updateFingerprints.equals(fingerprints(parsed.trustedKeys()))) {
            throw updateInvalid("repository update proof fields or sequence do not match the old and new snapshots");
        }
    }

    private void writeConfiguration(ParsedRepositoryDescriptor parsed) {
        RepositoryDescriptor descriptor = parsed.descriptor();
        PluginRepository baseline = registry.find(descriptor.repositoryId())
                .orElseGet(() -> registry.repositories().get(0));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("descriptor-url", parsed.descriptorUrl());
        extra.put("descriptor-sha256", parsed.descriptorSha256());
        extra.put("display-name", descriptor.displayName());
        extra.put("publisher-id", descriptor.publisher().id());
        extra.put("publisher-display-name", descriptor.publisher().displayName());
        extra.put("catalog-protocol", descriptor.catalog().protocol());
        extra.put("catalog-endpoint", descriptor.catalog().endpoint());
        if (descriptor.revocationsUrl() != null) extra.put("revocations-url", descriptor.revocationsUrl());
        if (descriptor.updateProofUrl() != null) extra.put("update-proof-url", descriptor.updateProofUrl());
        extra.put("trust-source", "SELF_TRUSTED");
        List<TrustedKeyConfigEntry> keys = parsed.trustedKeys().stream().map(key -> TrustedKeyConfigEntry.create(
                key.keyId(), key.algorithm(), key.publicKeySpkiBase64(), key.state().name(),
                key.publisher(), key.trustLabel())).toList();
        RepositoryConfigEntry entry = new RepositoryConfigEntry(descriptor.repositoryId(), "",
                descriptor.catalog().endpoint(), true, parsed.effectiveProxyPolicy(),
                false, true, false, "github-releases".equals(parsed.effectiveProxyPolicy()),
                baseline.connectTimeoutMs(), baseline.readTimeoutMs(), baseline.maxManifestBytes(),
                baseline.maxPackageBytes(), keys, extra);
        PluginRepositoryConfigEditor editor = new PluginRepositoryConfigEditor(RuntimeFiles.resolveConfigYamlPath());
        try {
            List<RepositoryConfigEntry> entries = new ArrayList<>(editor.read());
            entries.removeIf(current -> current.id().equalsIgnoreCase(descriptor.repositoryId()));
            entries.add(entry);
            editor.write(entries);
        } catch (IOException failure) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_CONFIG_WRITE_FAILED,
                    "failed to save trusted repository snapshot: " + failure.getMessage());
        }
    }

    private static Set<String> fingerprints(List<TrustedPluginKey> keys) {
        Set<String> result = new HashSet<>();
        for (TrustedPluginKey key : keys) {
            try {
                byte[] spki = java.util.Base64.getDecoder().decode(key.publicKeySpkiBase64());
                result.add("sha256:" + RepositoryDescriptorParser.sha256Hex(spki));
            } catch (IllegalArgumentException failure) {
                result.add("invalid:" + key.keyId());
            }
        }
        return result;
    }

    private static Set<String> knownHosts(PluginRepository repository) {
        Set<String> hosts = new HashSet<>();
        addHost(hosts, repository.descriptorUrl());
        addHost(hosts, repository.catalogEndpoint());
        addHost(hosts, repository.revocationsUrl());
        addHost(hosts, repository.updateProofUrl());
        return hosts;
    }

    private static void addHost(Set<String> hosts, String url) {
        String host = host(url);
        if (host != null) hosts.add(host);
    }

    private static String host(String url) {
        if (url == null || url.isBlank()) return null;
        try { return URI.create(url).getHost().toLowerCase(Locale.ROOT); }
        catch (RuntimeException failure) { return null; }
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static PluginCatalogException updateInvalid(String detail) {
        return new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_UPDATE_PROOF_INVALID, detail);
    }
}
