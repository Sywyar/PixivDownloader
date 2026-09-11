package top.sywyar.pixivdownload.sdk.community.format;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.http.HttpsLocation;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

/** 不携带认证结论的身份与原始字节引用；身份核实由受保护调用方提供。 */
public final class CommunityValues {
    private CommunityValues() { }

    public record Account(String id, String type) { }
    public record Owner(String accountId, String accountType, String publisherId) {
        public Account account() { return new Account(accountId, accountType); }
    }
    public record Reference(String path, long size, String sha256) {
        public void validate() { CommunityPaths.relative(path, false); }
        public void verify(byte[] bytes) { verifyBytes(bytes, size, sha256, path); }
        public void verify(java.nio.file.Path root) throws java.io.IOException {
            var file = CommunityPaths.resolve(root, path, false, true);
            if (!java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || java.nio.file.Files.size(file) != size) throw new ContractException("SIZE_MISMATCH", path);
            try (var input = java.nio.file.Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                while ((count = input.read(buffer, 0, (int) Math.min(buffer.length, size - total + 1))) != -1) {
                    total += count;
                    if (total > size) throw new ContractException("SIZE_MISMATCH", path);
                    digest.update(buffer, 0, count);
                }
                if (total != size) throw new ContractException("SIZE_MISMATCH", path);
                if (!java.util.HexFormat.of().formatHex(digest.digest()).equals(sha256)) throw new ContractException("HASH_MISMATCH", path);
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        public static Reference of(String path, byte[] bytes) {
            CommunityPaths.relative(path, false);
            return new Reference(path, bytes.length, CommunityJson.sha256(bytes));
        }
    }
    public record RemoteReference(String url, long size, String sha256) {
        public void validate() { https(url, false, "/url"); }
        public void verify(byte[] bytes) { verifyBytes(bytes, size, sha256, url); }
    }

    public static void verifyBytes(byte[] bytes, long size, String sha256, String field) {
        if (bytes.length != size) throw new ContractException("SIZE_MISMATCH", field);
        if (!CommunityJson.sha256(bytes).equals(sha256)) throw new ContractException("HASH_MISMATCH", field);
    }

    public static URI https(String value, boolean noQueryOrFragment, String field) {
        if (value != null && value.length() > HttpsLocation.MAX_URL_CHARS) {
            throw CommunityJson.limit(field, HttpsLocation.MAX_URL_CHARS, "UTF-16");
        }
        try { return HttpsLocation.parse(value, noQueryOrFragment); }
        catch (URISyntaxException e) { throw ContractException.invalid("URL_UNSAFE", field); }
    }

    public static void version(String value, String field) {
        if (!PluginDescriptor.isExternalVersion(value)) throw new ContractException("SCHEMA_INVALID", field);
    }

    public static <T> void unique(List<T> values, Function<T, String> key, String field) {
        var keys = new HashSet<String>();
        for (T value : values) if (!keys.add(key.apply(value))) throw ContractException.invalid("DUPLICATE_KEY", field);
    }
}
