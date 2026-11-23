package top.sywyar.pixivdownload.openapi;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import top.sywyar.pixivdownload.config.InstanceCookies;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;

/** Only development cookies vary with the validated request port. Keep those slots out of the cache key. */
final class ApiReferenceDocument {
    private static final String COOKIE_SLOT = UUID.randomUUID().toString();
    private final byte[][] parts;

    static String cookieName(String name) {
        return PluginDevelopmentArtifacts.enabled() ? COOKIE_SLOT + name : name;
    }

    ApiReferenceDocument(byte[] json) {
        parts = PluginDevelopmentArtifacts.enabled()
                ? Arrays.stream(new String(json, StandardCharsets.UTF_8).split(COOKIE_SLOT, -1))
                        .map(part -> part.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new)
                : new byte[][] { json };
    }

    byte[] forRequest(HttpServletRequest request) {
        if (parts.length == 1) return parts[0];
        byte[] prefix = InstanceCookies.name(request, "").getBytes(StandardCharsets.US_ASCII);
        int size = prefix.length * (parts.length - 1);
        for (byte[] part : parts) size += part.length;
        var result = ByteBuffer.allocate(size).put(parts[0]);
        for (int i = 1; i < parts.length; i++) result.put(prefix).put(parts[i]);
        return result.array();
    }
}
