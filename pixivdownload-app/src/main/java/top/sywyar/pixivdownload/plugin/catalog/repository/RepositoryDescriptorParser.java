package top.sywyar.pixivdownload.plugin.catalog.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.security.PluginCatalogStrictJson;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** repository.json 的严格解析与安全字段规范化。 */
public final class RepositoryDescriptorParser {

    public static final long MAX_DESCRIPTOR_BYTES = 64L * 1024L;
    private static final int MAX_URL_CHARS = 2_048;
    private static final Set<String> PROTOCOLS = Set.of("manifest-v1", "paged-v2");
    private static final Set<String> NETWORK_PROFILES = Set.of("DIRECT_STRICT", "GITHUB_RELEASES");
    private static final Set<String> RESERVED_IDS = Set.of(
            PluginRepository.OFFICIAL_ID, PluginRepository.LEGACY_CONFIGURED_ID, PluginRepository.COMMUNITY_ID);
    private final ObjectMapper mapper = PluginCatalogStrictJson.mapper(true);

    ParsedRepositoryDescriptor parse(String descriptorUrl, byte[] bytes) {
        URI descriptorUri = publicHttps(descriptorUrl, false,
                PluginCatalogErrorCode.REPOSITORY_DESCRIPTOR_URL_INVALID, "descriptorUrl");
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_DESCRIPTOR_BYTES) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_DESCRIPTOR_TOO_LARGE,
                    "repository descriptor must contain 1..65536 bytes");
        }
        RepositoryDescriptor descriptor;
        try {
            JsonNode root = mapper.readTree(PluginCatalogStrictJson.strictUtf8(bytes));
            validateArrayBounds(root);
            descriptor = mapper.treeToValue(root, RepositoryDescriptor.class);
        } catch (Exception failure) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_DESCRIPTOR_INVALID,
                    "malformed repository descriptor: " + failure.getMessage());
        }
        validateDescriptor(descriptor);

        URI catalogUri = publicHttps(descriptor.catalog().endpoint(), false,
                PluginCatalogErrorCode.REPOSITORY_DESCRIPTOR_INVALID, "catalog.endpoint");
        if ("paged-v2".equals(descriptor.catalog().protocol())
                && (catalogUri.getRawQuery() != null || catalogUri.getRawFragment() != null)) {
            invalid("paged-v2 catalog.endpoint must not contain query or fragment");
        }
        URI revocationsUri = optionalJsonUri(descriptor.revocationsUrl(), "revocationsUrl");
        URI updateProofUri = optionalJsonUri(descriptor.updateProofUrl(), "updateProofUrl");
        URI homepageUri = descriptor.publisher().homepageUrl() == null
                || descriptor.publisher().homepageUrl().isBlank()
                ? null : publicHttps(descriptor.publisher().homepageUrl(), false,
                PluginCatalogErrorCode.REPOSITORY_DESCRIPTOR_INVALID, "publisher.homepageUrl");

        if ("GITHUB_RELEASES".equals(descriptor.networkProfile())) {
            requireGithub(catalogUri, "catalog.endpoint");
            if (revocationsUri != null) requireGithub(revocationsUri, "revocationsUrl");
            if (updateProofUri != null) requireGithub(updateProofUri, "updateProofUrl");
        }

        List<TrustedPluginKey> trustedKeys = new ArrayList<>();
        List<RepositoryKeyPreview> previews = new ArrayList<>();
        Set<String> keyIds = new LinkedHashSet<>();
        for (RepositoryDescriptor.Key key : descriptor.trustedKeys()) {
            validateKey(key, keyIds);
            TrustedPluginKey trusted = new TrustedPluginKey(
                    key.keyId(), SignatureMetadata.ED25519, key.publicKeySpkiBase64(),
                    TrustedPluginKey.State.valueOf(key.state()), key.publisher(), key.trustLabel(), false);
            trustedKeys.add(trusted);
            String digest = sha256Hex(decodeSpki(key.publicKeySpkiBase64()));
            previews.add(new RepositoryKeyPreview(key.keyId(), key.algorithm(), key.state(), key.publisher(),
                    key.trustLabel(), "sha256:" + digest, "SHA-256 " + groupedFingerprint(digest)));
        }
        try {
            PluginTrustStores.of(trustedKeys);
        } catch (IllegalArgumentException failure) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_KEY_INVALID,
                    "invalid Ed25519 SPKI trust root: " + failure.getMessage());
        }

        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        hosts.add(lowerHost(descriptorUri));
        hosts.add(lowerHost(catalogUri));
        if (revocationsUri != null) hosts.add(lowerHost(revocationsUri));
        if (updateProofUri != null) hosts.add(lowerHost(updateProofUri));
        hosts.remove(null);
        String profile = descriptor.networkProfile();
        return new ParsedRepositoryDescriptor(descriptorUri.toASCIIString(), sha256Hex(bytes), descriptor,
                trustedKeys, previews, List.copyOf(hosts),
                "DIRECT_STRICT".equals(profile) ? "direct-strict" : "github-releases",
                "DIRECT_STRICT".equals(profile)
                        ? "no redirects; public HTTPS only"
                        : "one redirect within GitHub-owned hosts; public HTTPS only");
    }

    private static void validateArrayBounds(JsonNode node) {
        if (node.isArray() && node.size() > 256) invalid("JSON arrays may contain at most 256 entries");
        node.elements().forEachRemaining(RepositoryDescriptorParser::validateArrayBounds);
    }

    private static void validateDescriptor(RepositoryDescriptor descriptor) {
        if (descriptor == null || descriptor.schemaVersion() == null || descriptor.schemaVersion() != 1) {
            invalid("schemaVersion must be integer 1");
        }
        String id = requiredText(descriptor.repositoryId(), 64, "repositoryId");
        String canonical = id.toLowerCase(Locale.ROOT);
        if (!id.equals(canonical) || !id.matches("[a-z][a-z0-9._-]{0,63}") || RESERVED_IDS.contains(id)) {
            invalid("repositoryId is not canonical or is reserved");
        }
        requiredText(descriptor.displayName(), 128, "displayName");
        if (descriptor.publisher() == null) invalid("publisher is required");
        String publisherId = requiredText(descriptor.publisher().id(), 64, "publisher.id");
        if (!publisherId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")) invalid("publisher.id is invalid");
        requiredText(descriptor.publisher().displayName(), 128, "publisher.displayName");
        optionalText(descriptor.publisher().homepageUrl(), MAX_URL_CHARS, "publisher.homepageUrl");
        if (descriptor.catalog() == null) invalid("catalog is required");
        String protocol = requiredText(descriptor.catalog().protocol(), 32, "catalog.protocol");
        if (!PROTOCOLS.contains(protocol)) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_PROTOCOL_UNSUPPORTED,
                    "unsupported repository catalog protocol: " + protocol);
        }
        requiredText(descriptor.catalog().endpoint(), MAX_URL_CHARS, "catalog.endpoint");
        String profile = requiredText(descriptor.networkProfile(), 32, "networkProfile");
        if (!NETWORK_PROFILES.contains(profile)) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_NETWORK_PROFILE_UNSUPPORTED,
                    "unsupported repository network profile: " + profile);
        }
        optionalText(descriptor.revocationsUrl(), MAX_URL_CHARS, "revocationsUrl");
        optionalText(descriptor.updateProofUrl(), MAX_URL_CHARS, "updateProofUrl");
        if (descriptor.trustedKeys() == null || descriptor.trustedKeys().isEmpty()
                || descriptor.trustedKeys().size() > 4) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_KEY_INVALID,
                    "trustedKeys must contain 1..4 entries");
        }
    }

    private static void validateKey(RepositoryDescriptor.Key key, Set<String> keyIds) {
        if (key == null) keyInvalid("trustedKeys entry is null");
        String keyId = requiredText(key.keyId(), 128, "trustedKeys.keyId");
        if (!keyId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") || !keyIds.add(keyId)) {
            keyInvalid("trusted keyId is invalid or duplicated: " + keyId);
        }
        if (!SignatureMetadata.ED25519.equals(key.algorithm())) keyInvalid("only Ed25519 keys are accepted");
        if (!"ACTIVE".equals(key.state()) && !"RETIRED".equals(key.state())) {
            keyInvalid("trusted key state must be ACTIVE or RETIRED");
        }
        requiredText(key.publicKeySpkiBase64(), 1_024, "trustedKeys.publicKeySpkiBase64");
        optionalText(key.publisher(), 128, "trustedKeys.publisher");
        optionalText(key.trustLabel(), 128, "trustedKeys.trustLabel");
    }

    private static URI optionalJsonUri(String value, String field) {
        if (value == null || value.isBlank()) return null;
        URI uri = publicHttps(value, true, PluginCatalogErrorCode.REPOSITORY_DESCRIPTOR_INVALID, field);
        if (uri.getPath() == null || !uri.getPath().endsWith(".json")) {
            invalid(field + " path must end with .json");
        }
        return uri;
    }

    static URI publicHttps(String value, boolean noQueryOrFragment, PluginCatalogErrorCode code, String field) {
        String text = requiredText(value, MAX_URL_CHARS, field);
        try {
            URI uri = new URI(text).normalize();
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
                    || noQueryOrFragment && (uri.getRawQuery() != null || uri.getRawFragment() != null)) {
                throw new URISyntaxException(text, "absolute public HTTPS URL required");
            }
            return uri;
        } catch (URISyntaxException failure) {
            throw new PluginCatalogException(code, field + " is invalid: " + failure.getMessage());
        }
    }

    private static void requireGithub(URI uri, String field) {
        String host = lowerHost(uri);
        if (!("github.com".equals(host) || "api.github.com".equals(host)
                || "raw.githubusercontent.com".equals(host)
                || host != null && host.endsWith(".githubusercontent.com"))) {
            throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_NETWORK_PROFILE_UNSUPPORTED,
                    field + " is outside the fixed GitHub Releases host boundary");
        }
    }

    static String lowerHost(URI uri) {
        return uri == null || uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static byte[] decodeSpki(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException failure) {
            keyInvalid("publicKeySpkiBase64 is not valid Base64");
            return new byte[0];
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String groupedFingerprint(String digest) {
        StringBuilder grouped = new StringBuilder(digest.length() + 31);
        for (int i = 0; i < digest.length(); i += 2) {
            if (i > 0) grouped.append(':');
            grouped.append(digest, i, i + 2);
        }
        return grouped.toString();
    }

    private static String requiredText(String value, int maximum, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) invalid(field + " is required");
        validatePlainText(value, maximum, field);
        return value;
    }

    private static void optionalText(String value, int maximum, String field) {
        if (value != null && !value.isBlank()) validatePlainText(value, maximum, field);
    }

    private static void validatePlainText(String value, int maximum, String field) {
        if (value.length() > maximum || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || value.codePoints().anyMatch(Character::isISOControl)) {
            invalid(field + " contains controls/newlines or is too long");
        }
    }

    private static void invalid(String detail) {
        throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_DESCRIPTOR_INVALID, detail);
    }

    private static void keyInvalid(String detail) {
        throw new PluginCatalogException(PluginCatalogErrorCode.REPOSITORY_KEY_INVALID, detail);
    }
}
