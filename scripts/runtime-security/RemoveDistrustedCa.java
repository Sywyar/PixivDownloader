import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;

/** 仅处理待打包运行时的信任库，不修改构建 JDK 或用户的信任库。 */
public final class RemoveDistrustedCa {
    private static final String DISTRUSTED_SHA256 =
            "86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b";
    private static final char[] PASSWORD = "changeit".toCharArray();
    // 公开事件清单中的 8 个中间 CA 及其交叉签名证书，按 DER 指纹禁用。
    private static final Set<String> BLOCKED_INTERMEDIATES = Set.of(
            "42C4ACCD12C4F59597925E1A08A7A90102608543809308D50EC427E118BA1493",
            "45A883F349AEDFD5F3A649B5498FFB121712BA8B448F3F2A91DBC5A766FCDDBE",
            "98B896625C0C230ADEEC3EB2CF6AAD5001ECF9156A68644E82374CE5833BC25B",
            "5F1DED4DD590AF74631290CF073B122A2AAC6A1FA479778A0BD43579BD832699",
            "E7568FE6E12331F4FD7DE61DA4C7559D0F8EB192A218D4741A3BA75184333838",
            "579480AEA166290BD3C643589E795FC0D0E54E86505D180F1DFF99EFD3199512",
            "0164D7CEAE5BB909C272AF151FF44B1177AE9B47D9424B94F1D9558C30934D19",
            "62B7F9868263F72F6911FC78CEAF72C1979158B7368BF648683BB2AA39B02E72",
            "CB6E15165AC802E9B3F993F16C25D41F4E81C21EBDE71AE1B012AC00BF9C7C3F",
            "7485B51C0ED192878FA1D2F948FB529AE1842CC5086C4B67F7BE5B2039DE9489",
            "432EC25F0D28DE9006D97BF752F17BBB2CD41F669933FCB072D9A1148D0261F3",
            "FD9D5C2DBBD4995AB69B8D50C439D9527B00529199C2FD01B2174EB58F07021F",
            "9E39DE2881273ACC225A149C72BA3E0F51424963669AB633EE1DC7DF5DB067B4",
            "A496E23AC0CAB8155FC9B117B97EAE368EFCBB9041AEC8804D5A308D2D01C2D8",
            "4B2460A466F95FD40113BCDCCA2CEB29B3B093921DE45474DA7DF48874C630B1",
            "84112972B8E59D14560AE7E7119654474AAD119990CF3722BFC1E3B91615E1FD");

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected the staged runtime cacerts path");
        }
        Path cacerts = Path.of(args[0]);
        blockIntermediates(cacerts.resolveSibling("blocked.certs"));
        remove(cacerts);
    }

    static void blockIntermediates(Path path) throws Exception {
        byte[] original = Files.notExists(path) ? "Algorithm=SHA-256\n".getBytes(StandardCharsets.US_ASCII)
                : Files.readAllBytes(path);
        Properties blocked = readBlockedCertificates(original);
        var missing = BLOCKED_INTERMEDIATES.stream().filter(hash -> !blocked.containsKey(hash)).sorted().toList();
        if (!missing.isEmpty()) {
            Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "blocked-", ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(temporary)) {
                    output.write(original);
                    // 双换行兼容原文件末尾没有换行或保留续行符的情况。
                    output.write(("\n\n" + String.join("\n", missing) + "\n").getBytes(StandardCharsets.US_ASCII));
                }
                Properties merged = readBlockedCertificates(Files.readAllBytes(temporary));
                if (!merged.entrySet().containsAll(blocked.entrySet())
                        || !merged.keySet().containsAll(BLOCKED_INTERMEDIATES)) {
                    throw new IOException("Incomplete runtime certificate blocklist");
                }
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        Properties verified = readBlockedCertificates(Files.readAllBytes(path));
        if (!verified.keySet().containsAll(BLOCKED_INTERMEDIATES)) {
            throw new IOException("Compromised intermediates missing from runtime certificate blocklist");
        }
        System.out.println("Runtime certificate blocklist verified; added compromised intermediate entries: " + missing.size());
    }

    private static Properties readBlockedCertificates(byte[] bytes) throws IOException {
        Properties blocked = new Properties();
        blocked.load(new ByteArrayInputStream(bytes));
        if (!"SHA-256".equals(blocked.getProperty("Algorithm"))) {
            throw new IOException("Unsupported or missing runtime certificate blocklist algorithm");
        }
        for (String key : blocked.stringPropertyNames()) {
            if (!key.equals("Algorithm") && (!key.matches("[0-9A-F]{64}") || !blocked.getProperty(key).isEmpty())) {
                throw new IOException("Invalid runtime certificate blocklist entry");
            }
        }
        return blocked;
    }

    static void remove(Path path) throws Exception {
        KeyStore store = KeyStore.getInstance(path.toFile(), PASSWORD);
        int removed = 0;
        for (String alias : Collections.list(store.aliases())) {
            if (distrusted(store, alias)) {
                store.deleteEntry(alias);
                removed++;
            }
        }
        if (removed != 0) {
            Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "cacerts-", ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(temporary)) {
                    store.store(output, PASSWORD);
                }
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        KeyStore verified = KeyStore.getInstance(path.toFile(), PASSWORD);
        for (String alias : Collections.list(verified.aliases())) {
            if (distrusted(verified, alias)) {
                throw new IllegalStateException("Distrusted emSign ECC Root CA - G3 remains in " + path);
            }
        }
        System.out.println("Runtime trust store verified; removed emSign ECC Root CA - G3 entries: " + removed);
    }

    private static boolean distrusted(KeyStore store, String alias) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(store.getCertificate(alias).getEncoded());
        return DISTRUSTED_SHA256.equals(HexFormat.of().formatHex(digest));
    }
}
