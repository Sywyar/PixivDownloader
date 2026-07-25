package top.sywyar.pixivdownload.douyin.client.signature;

import top.sywyar.pixivdownload.douyin.client.api.DouyinApiUriBuilder;

import java.net.URI;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * 抖音 Web API 请求的签名接缝（seam）。
 *
 * <p>本类只负责「在什么位置、以什么顺序把签名挂到请求上」，不包含任何反爬签名算法实现。
 * 默认签名器 {@link #stubSigner()} 仅附加一个非功能的占位参数，便于示例端到端跑通装配与错误分类，
 * 但它无法通过抖音服务端验签——服务端会按「需要签名」处理，{@code DefaultDouyinClient} 随后回退到
 * 不需签名的公开作品页路径。</p>
 *
 * <p>如需启用受签名保护的高吞吐端点（用户主页列表、搜索、收藏等），请通过构造器注入自行实现的
 * {@code aBogusSigner} / {@code xBogusSigner}（例如在私有构建中提供真实签名器）；此仓库不分发此类实现。</p>
 */
public class DouyinSignedUriBuilder {

    /** 占位签名值，明确表示「未配置真实签名器」，服务端会拒绝。 */
    static final String STUB_SIGNATURE = "stub-signer-not-configured";

    private final UnaryOperator<String> aBogusSigner;
    private final Function<String, URI> xBogusSigner;
    private final DouyinApiUriBuilder apiUriBuilder;

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

    public SignedRequest unsignedRequest(String path, Map<String, ?> endpointParams, String cookie) {
        String requestCookie = requestCookie(cookie);
        return new SignedRequest(apiUriBuilder.api(path, endpointParams, requestCookie), requestCookie);
    }

    private String requestCookie(String cookie) {
        String normalized = cookie == null ? "" : cookie.trim();
        var existing = DouyinMsToken.fromCookie(normalized);
        return existing.isPresent()
                ? DouyinMsToken.withToken(normalized, existing.get())
                : normalized;
    }

    private static UnaryOperator<String> defaultABogusSigner() {
        return stubSigner();
    }

    private static Function<String, URI> defaultXBogusSigner() {
        return url -> URI.create(url + (url.contains("?") ? "&" : "?") + "X-Bogus=" + STUB_SIGNATURE);
    }

    static UnaryOperator<String> stubSigner() {
        return query -> {
            String q = query == null ? "" : query;
            return q + (q.isEmpty() ? "" : "&") + "a_bogus=" + STUB_SIGNATURE;
        };
    }

    public record SignedRequest(URI uri, String cookie) {
        public SignedRequest {
            cookie = cookie == null ? "" : cookie;
        }
    }
}
