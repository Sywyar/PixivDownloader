package top.sywyar.pixivdownload.plugin.signature.internal.trust;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;

/** 签名 CLI 的密钥文件管理；私钥只写入新建且已收紧权限的目录。 */
public final class SigningKeyFiles {
    public static final int MAX_KEY_BYTES = 16 * 1024;

    private SigningKeyFiles() { }

    public static void generate(Path requested) throws IOException, GeneralSecurityException {
        Path directory = requested.toAbsolutePath().normalize();
        Path parent = directory.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !parent.equals(parent.toRealPath())) {
            throw new IOException("KEY_DIRECTORY_PARENT_REQUIRED");
        }
        var posix = Files.getFileAttributeView(parent, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } else {
            if (Files.getFileAttributeView(parent, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) == null) {
                throw new IOException("KEY_FILE_PERMISSIONS_UNSUPPORTED");
            }
            Files.createDirectory(directory);
            var acl = Files.getFileAttributeView(directory, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            var user = directory.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build();
            acl.setAcl(List.of(entry));
            if (!acl.getAcl().equals(List.of(entry))) throw new IOException("KEY_FILE_PERMISSIONS_INVALID");
        }
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path privateFile = directory.resolve("private-key.pem");
        if (posix != null) {
            Files.createFile(privateFile, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
            Files.writeString(privateFile, pem("PRIVATE KEY", pair.getPrivate().getEncoded()),
                    StandardCharsets.UTF_8, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } else {
            Files.writeString(privateFile, pem("PRIVATE KEY", pair.getPrivate().getEncoded()),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        Files.writeString(directory.resolve("public-key.pem"), pem("PUBLIC KEY", pair.getPublic().getEncoded()),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** 导出配套公钥的规范 SPKI；不自行实现从私钥种子推导公钥。 */
    public static String publicKey(Path path) throws IOException {
        String text = new String(read(path, MAX_KEY_BYTES), StandardCharsets.UTF_8).strip();
        if (text.startsWith("-----BEGIN PUBLIC KEY-----") && text.endsWith("-----END PUBLIC KEY-----")) {
            text = text.substring("-----BEGIN PUBLIC KEY-----".length(),
                    text.length() - "-----END PUBLIC KEY-----".length()).replaceAll("\\s+", "");
        }
        KeyParsing.canonicalEd25519PublicKey(text);
        return text;
    }

    public static byte[] read(Path path, int maximum) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("REGULAR_FILE_REQUIRED");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("INPUT_LIMIT_EXCEEDED");
            return bytes;
        }
    }

    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n";
    }
}
