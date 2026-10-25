import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;

/** 仅处理待打包运行时的信任库，不修改构建 JDK 或用户的信任库。 */
public final class RemoveDistrustedCa {
    private static final String DISTRUSTED_SHA256 =
            "86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b";
    private static final char[] PASSWORD = "changeit".toCharArray();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected the staged runtime cacerts path");
        }
        remove(Path.of(args[0]));
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
