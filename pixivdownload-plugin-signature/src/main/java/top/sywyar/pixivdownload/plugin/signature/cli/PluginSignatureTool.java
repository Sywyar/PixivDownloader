package top.sywyar.pixivdownload.plugin.signature.cli;

import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
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
import java.util.Map;

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

    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  artifact --artifact <jar> --plugin-id <id> --version <version> "
                + "--key-id <key> --private-key <pkcs8.pem> --out <sig.json>");
        System.out.println("  manifest --manifest <manifest.json> --repository-id <id> "
                + "--key-id <key> --private-key <pkcs8.pem> --out <manifest.json.sig>");
    }
}
