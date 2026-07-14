package top.sywyar.pixivdownload.douyin.client.signature;

import top.sywyar.pixivdownload.douyin.client.DouyinRequestHeaders;
import top.sywyar.pixivdownload.douyin.client.api.DouyinApiUriBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

public class DouyinSignedUriBuilder {

    private static final int MAX_GENERATED_TOKEN_CONTEXTS = 32;

    private final UnaryOperator<String> aBogusSigner;
    private final Function<String, URI> xBogusSigner;
    private final DouyinApiUriBuilder apiUriBuilder;
    private final Map<String, String> generatedTokens = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_GENERATED_TOKEN_CONTEXTS;
        }
    };

    public DouyinSignedUriBuilder() {
        this(defaultABogusSigner(), defaultXBogusSigner(), new DouyinApiUriBuilder());
    }

    DouyinSignedUriBuilder(UnaryOperator<String> aBogusSigner,
                           Function<String, URI> xBogusSigner) {
        this(aBogusSigner, xBogusSigner, new DouyinApiUriBuilder());
    }

    DouyinSignedUriBuilder(UnaryOperator<String> aBogusSigner,
                           Function<String, URI> xBogusSigner,
                           DouyinApiUriBuilder apiUriBuilder) {
        this.aBogusSigner = aBogusSigner;
        this.xBogusSigner = xBogusSigner;
        this.apiUriBuilder = apiUriBuilder;
    }

    public URI api(String path, Map<String, ?> endpointParams, String cookie) {
        return request(path, endpointParams, cookie).uri();
    }

    public SignedRequest request(String path, Map<String, ?> endpointParams, String cookie) {
        String requestCookie = requestCookie(cookie);
        URI unsigned = apiUriBuilder.api(path, endpointParams, requestCookie);
        String query = unsigned.getRawQuery();
        String basePath = unsigned.getScheme() + "://" + unsigned.getRawAuthority() + unsigned.getRawPath();
        URI signed;
        try {
            signed = URI.create(basePath + "?" + aBogusSigner.apply(query));
        } catch (RuntimeException error) {
            signed = xBogusSigner.apply(unsigned.toASCIIString());
        }
        return new SignedRequest(signed, requestCookie);
    }

    private String requestCookie(String cookie) {
        String normalized = cookie == null ? "" : cookie.trim();
        var existing = DouyinMsToken.fromCookie(normalized);
        if (existing.isPresent()) {
            return DouyinMsToken.withToken(normalized, existing.get());
        }
        String key = credentialKey(normalized);
        String token;
        synchronized (generatedTokens) {
            token = generatedTokens.computeIfAbsent(key, ignored -> DouyinMsToken.fallback());
        }
        return DouyinMsToken.withToken(normalized, token);
    }

    private static String credentialKey(String cookie) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(cookie.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static UnaryOperator<String> defaultABogusSigner() {
        DouyinABogusSigner signer = new DouyinABogusSigner(DouyinRequestHeaders.USER_AGENT);
        return signer::signQuery;
    }

    private static Function<String, URI> defaultXBogusSigner() {
        DouyinXBogusSigner signer = new DouyinXBogusSigner(DouyinRequestHeaders.USER_AGENT);
        return url -> URI.create(signer.sign(url).url());
    }

    public record SignedRequest(URI uri, String cookie) {
        public SignedRequest {
            cookie = cookie == null ? "" : cookie;
        }
    }
}
