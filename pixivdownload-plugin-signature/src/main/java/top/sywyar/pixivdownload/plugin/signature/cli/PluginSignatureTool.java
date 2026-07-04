package top.sywyar.pixivdownload.plugin.signature.cli;

import top.sywyar.pixivdownload.plugin.signature.ArtifactVerificationRequest;
import top.sywyar.pixivdownload.plugin.signature.ManifestVerificationRequest;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.VerificationPolicy;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.internal.ed25519.Ed25519Signer;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.Hashing;
import top.sywyar.pixivdownload.plugin.signature.internal.trust.KeyParsing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发布链路使用的 detached 签名 CLI。调用方只传身份字段与文件路径，签名消息由模块内部 codec 生成。
 */
public final class PluginSignatureTool {

    private PluginSignatureTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            usage();
            return;
        }
        String command = args[0];
        Map<String, String> options = parseOptions(args);
        if ("artifact".equals(command)) {
            signArtifact(options);
            return;
        }
        if ("manifest".equals(command)) {
            signManifest(options);
            return;
        }
        if ("verify-manifest".equals(command)) {
            verifyManifest(options);
            return;
        }
        if ("verify-artifact".equals(command)) {
            verifyArtifact(options);
            return;
        }
        throw new IllegalArgumentException("unknown command: " + command);
    }

    private static void signArtifact(Map<String, String> options) throws IOException {
        Path artifact = requiredPath(options, "artifact");
        String pluginId = required(options, "plugin-id");
        String version = required(options, "version");
        String keyId = required(options, "key-id");
        PrivateKey privateKey = privateKey(options);
        byte[] sha256 = Hashing.sha256(artifact);
        byte[] message = EnvelopeV1Codec.artifactMessage(
                SignatureMetadata.ED25519, keyId, pluginId, version, Files.size(artifact), sha256);
        writeMetadata(requiredPath(options, "out"), keyId, Ed25519Signer.sign(privateKey, message));
    }

    private static void verifyManifest(Map<String, String> options) throws IOException {
        Path manifest = requiredPath(options, "manifest");
        SignatureMetadata signature = readMetadata(requiredPath(options, "signature"));
        String repositoryId = required(options, "repository-id");
        VerificationResult result = verifier(options).verifyManifest(new ManifestVerificationRequest(
                Files.readAllBytes(manifest), repositoryId, signature, policy(options)));
        report(result);
    }

    private static void verifyArtifact(Map<String, String> options) throws IOException {
        Path artifact = requiredPath(options, "artifact");
        SignatureMetadata signature = readMetadata(requiredPath(options, "signature"));
        VerificationResult result = verifier(options).verifyArtifact(new ArtifactVerificationRequest(
                artifact,
                required(options, "plugin-id"),
                required(options, "version"),
                Long.parseLong(required(options, "expected-size")),
                required(options, "sha256"),
                signature,
                policy(options)));
        report(result);
    }

    private static void signManifest(Map<String, String> options) throws IOException {
        Path manifest = requiredPath(options, "manifest");
        String repositoryId = required(options, "repository-id");
        String keyId = required(options, "key-id");
        PrivateKey privateKey = privateKey(options);
        byte[] bytes = Files.readAllBytes(manifest);
        byte[] message = EnvelopeV1Codec.manifestMessage(repositoryId, bytes.length, Hashing.sha256(bytes));
        writeMetadata(requiredPath(options, "out"), keyId, Ed25519Signer.sign(privateKey, message));
    }

    private static PrivateKey privateKey(Map<String, String> options) throws IOException {
        Path path = requiredPath(options, "private-key");
        return KeyParsing.ed25519PrivateKey(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static void writeMetadata(Path out, String keyId, byte[] signature) throws IOException {
        SignatureMetadata metadata = new SignatureMetadata(SignatureMetadata.FORMAT_VERSION,
                SignatureMetadata.ED25519, keyId, Base64.getEncoder().encodeToString(signature));
        String json = "{"
                + "\"formatVersion\":" + metadata.formatVersion() + ","
                + "\"algorithm\":\"" + metadata.algorithm() + "\","
                + "\"keyId\":\"" + jsonString(metadata.keyId()) + "\","
                + "\"value\":\"" + metadata.value() + "\""
                + "}";
        Path parent = out.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(out, json + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static SignatureMetadata readMetadata(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        return new SignatureMetadata(
                Integer.parseInt(jsonValue(json, "formatVersion")),
                jsonValue(json, "algorithm"),
                jsonValue(json, "keyId"),
                jsonValue(json, "value"));
    }

    private static VerificationPolicy policy(Map<String, String> options) {
        String value = options.getOrDefault("policy", "official").trim();
        return switch (value) {
            case "official" -> VerificationPolicy.officialRepository();
            case "custom" -> VerificationPolicy.customRepository();
            case "installed-official" -> VerificationPolicy.installedOfficial();
            case "installed-custom" -> VerificationPolicy.installedCustom();
            case "local-unsigned" -> VerificationPolicy.localUnsignedAllowed();
            default -> throw new IllegalArgumentException("unsupported --policy: " + value);
        };
    }

    private static PluginSupplyChainVerifier verifier(Map<String, String> options) {
        if (!options.containsKey("trusted-key-id") && !options.containsKey("trusted-public-key")) {
            return new PluginSupplyChainVerifier();
        }
        TrustedPluginKey key = new TrustedPluginKey(
                required(options, "trusted-key-id"),
                options.getOrDefault("trusted-algorithm", SignatureMetadata.ED25519),
                required(options, "trusted-public-key"),
                TrustedPluginKey.State.valueOf(options.getOrDefault("trusted-state", "ACTIVE")),
                options.getOrDefault("trusted-publisher", "CLI Trusted Publisher"),
                options.getOrDefault("trusted-label", "CLI Trusted Root"),
                Boolean.parseBoolean(options.getOrDefault("trusted-official", "false")));
        return new PluginSupplyChainVerifier(PluginTrustStores.withBuiltInOfficial(List.of(key)));
    }

    private static void report(VerificationResult result) {
        System.out.println(result.status() + " " + result.diagnosticCode());
        if (!result.accepted()) {
            throw new IllegalStateException("verification failed: " + result.status()
                    + " (" + result.diagnosticCode() + ")");
        }
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + token);
            }
            String key = token.substring(2);
            if (key.isBlank()) {
                throw new IllegalArgumentException("empty option name");
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("missing value for --" + key);
            }
            options.put(key, args[++i]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing --" + key);
        }
        return value;
    }

    private static Path requiredPath(Map<String, String> options, String key) {
        return Path.of(required(options, key));
    }

    private static String jsonString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsonValue(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key)
                + "\"\\s*:\\s*(\"((?:\\\\.|[^\"\\\\])*)\"|([0-9]+))");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("missing signature metadata key: " + key);
        }
        String stringValue = matcher.group(2);
        return stringValue != null ? unescapeJsonString(stringValue) : matcher.group(3);
    }

    private static String unescapeJsonString(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\\' || i + 1 >= value.length()) {
                out.append(ch);
                continue;
            }
            char escaped = value.charAt(++i);
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 >= value.length()) {
                        throw new IllegalArgumentException("bad unicode escape in signature metadata");
                    }
                    out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("bad escape in signature metadata: \\" + escaped);
            }
        }
        return out.toString();
    }

    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  artifact --artifact <jar> --plugin-id <id> --version <version> "
                + "--key-id <key> --private-key <pkcs8.pem> --out <sig.json>");
        System.out.println("  manifest --manifest <manifest.json> --repository-id <id> "
                + "--key-id <key> --private-key <pkcs8.pem> --out <manifest.json.sig>");
        System.out.println("  verify-manifest --manifest <manifest.json> --signature <manifest.json.sig> "
                + "--repository-id <id> [--policy official|custom]");
        System.out.println("  verify-artifact --artifact <jar> --signature <sig.json> --plugin-id <id> "
                + "--version <version> --expected-size <bytes> --sha256 <hex> [--policy official|custom]");
    }
}
