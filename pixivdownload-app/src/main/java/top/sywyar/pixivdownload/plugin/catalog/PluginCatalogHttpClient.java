package top.sywyar.pixivdownload.plugin.catalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * 受信 catalog 专用的 <b>SSRF 安全</b> HTTP 客户端：只用于拉取受信清单字节与下载受信插件包。它的 public 方法接收的 URL
 * 由调用方（受信清单 / 选中的 catalog 包）给出，<b>不</b>对外暴露「任意请求参数 URL」入口；自身做 scheme + 主机 + 解析 IP
 * 校验、设连接 / 读取超时、按最大字节数上限<b>流式</b>读取。
 *
 * <h2>安全边界</h2>
 * <ul>
 *   <li><b>仅 https</b>（生产；构造参数可放开供测试）。明确拒绝 file / http / jar / ftp 等其它 scheme。</li>
 *   <li><b>SSRF 防护</b>：解析主机到全部 IP，任一为 loopback / link-local / 私网（含 CGNAT / IPv6 ULA）/ 组播 /
 *       未指定地址（生产）即拒绝。</li>
 *   <li><b>默认禁重定向</b>（{@link HttpClient.Redirect#NEVER}）：3xx 一律按失败处理、<b>绝不</b>跟随到二次地址，避免
 *       重定向绕过 IP 校验。<b>受信仓库</b>（proxy-trusted）可经完整构造声明一份<b>重定向主机白名单</b>，仅对白名单内主机
 *       <b>跟随至多一跳</b>（GitHub release 资产 CDN 的 302→签名 URL 即走此路），并对该跳重跑全部校验。</li>
 *   <li><b>默认不走代理</b>（{@link HttpClient.Builder#NO_PROXY}）：经代理会使本地 IP 校验失效（代理替我们解析 DNS）。
 *       受信仓库可经完整构造改走出站代理；此时 DNS 由代理完成，本地 IP 校验按上述原因<b>跳过</b>，安全边界改由
 *       https + 重定向主机白名单 + 调用方的 sha256/size 完整性兜底承担。</li>
 *   <li>最大字节数上限：超过即中止（调用方清理临时文件）。</li>
 * </ul>
 *
 * <p><b>已记录的残留风险（超出当前最小闭环范围）</b>：主机解析（{@link InetAddress#getAllByName}）与 JDK
 * {@code HttpClient} 实际连接时各做一次 DNS，二者之间存在 DNS rebinding 的 TOCTOU 窗口。受信 catalog + 默认关闭使风险
 * 很低；更强的「连已校验 IP + 自管 SNI」方案不在本框架范围。
 */
public class PluginCatalogHttpClient {

    private static final Set<String> SCHEMES_HTTPS_ONLY = Set.of("https");
    private static final Set<String> SCHEMES_HTTPS_AND_HTTP = Set.of("https", "http");
    private static final int BUFFER_SIZE = 8192;

    private final boolean httpsOnly;
    private final boolean allowNonPublicAddresses;
    private final boolean proxied;
    private final boolean allowRedirects;
    private final boolean validateAddressesWhenProxied;
    private final int readTimeoutMs;
    private final Set<String> redirectAllowlistDomains;
    private final HttpClient httpClient;

    /**
     * 直连严格档（不走代理、不跟随任何重定向）。等价于
     * {@link #PluginCatalogHttpClient(boolean, boolean, int, int, ProxySelector, Set)} 传 {@code proxySelector=null} +
     * 空白名单。
     *
     * @param httpsOnly               是否只允许 https（生产 {@code true}；测试可设 {@code false} 对接本地 http stub）
     * @param allowNonPublicAddresses 是否放开非公网地址校验（生产 {@code false}=严格 SSRF；测试可设 {@code true} 对接
     *                                loopback stub）
     * @param connectTimeoutMs        连接超时（毫秒，&le;0 取默认 15000）
     * @param readTimeoutMs           读取超时（毫秒，&le;0 取默认 60000）
     */
    public PluginCatalogHttpClient(boolean httpsOnly, boolean allowNonPublicAddresses,
                                   int connectTimeoutMs, int readTimeoutMs) {
        this(httpsOnly, allowNonPublicAddresses, connectTimeoutMs, readTimeoutMs, null, Set.of());
    }

    /**
     * 完整构造：在严格档基础上，可选地<b>经出站代理</b>拉取，并<b>按主机白名单跟随至多一跳重定向</b>——仅供受信仓库
     * （proxy-trusted）使用（直连严格档恒用上面的四参构造）。
     *
     * <ul>
     *   <li>{@code proxySelector != null}：经该代理连接，并视为「已代理」：DNS 由代理完成，故 {@link #verifyUrlAllowed}
     *       跳过本地解析与 IP 段校验（本地解析在受限网络下可能失败 / 被污染，且经代理后本地 IP 校验本就失效）。受信路径的
     *       安全边界改由 https + 重定向主机白名单 + 调用方的 sha256/size 完整性兜底承担。</li>
     *   <li>{@code redirectAllowlistDomains} 非空：遇 3xx 时，仅当 {@code Location} 主机命中白名单（相等或为其子域）才
     *       <b>跟随一跳</b>（并对该跳目标重跑 {@link #verifyUrlAllowed}）；再遇 3xx 即失败。空白名单 = 不跟随（严格档行为不变）。</li>
     * </ul>
     */
    public PluginCatalogHttpClient(boolean httpsOnly, boolean allowNonPublicAddresses,
                                   int connectTimeoutMs, int readTimeoutMs,
                                   ProxySelector proxySelector, Set<String> redirectAllowlistDomains) {
        this(httpsOnly, allowNonPublicAddresses, connectTimeoutMs, readTimeoutMs, proxySelector,
                redirectAllowlistDomains != null && !redirectAllowlistDomains.isEmpty(),
                redirectAllowlistDomains, false);
    }

    /**
     * 自定义网络档构造。允许重定向时最多跟随一跳；白名单为空表示允许任意目标主机，但该目标仍会重新执行 scheme / 主机 /
     * 地址校验。{@code validateAddressesWhenProxied=true} 时，即便使用代理也先按本机 DNS 结果执行非公网地址阻断；这无法约束
     * 代理端可能不同的 DNS 视图，但可阻断 IP 字面量与本机明确解析到非公网的目标。
     */
    public PluginCatalogHttpClient(boolean httpsOnly, boolean allowNonPublicAddresses,
                                   int connectTimeoutMs, int readTimeoutMs,
                                   ProxySelector proxySelector, boolean allowRedirects,
                                   Set<String> redirectAllowlistDomains, boolean validateAddressesWhenProxied) {
        this.httpsOnly = httpsOnly;
        this.allowNonPublicAddresses = allowNonPublicAddresses;
        this.proxied = proxySelector != null;
        this.allowRedirects = allowRedirects;
        this.validateAddressesWhenProxied = validateAddressesWhenProxied;
        this.readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : 60_000;
        this.redirectAllowlistDomains = redirectAllowlistDomains == null
                ? Set.of() : Set.copyOf(redirectAllowlistDomains);
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(proxySelector != null ? proxySelector : HttpClient.Builder.NO_PROXY)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs > 0 ? connectTimeoutMs : 15_000))
                .build();
    }

    /**
     * 拉取一个受信 URL 的全部字节（内存内，受 {@code maxBytes} 上限约束）。校验失败 / 网络失败 / 超限 / 非 200 →
     * {@link PluginCatalogException}。
     */
    public byte[] fetchBytes(String url, long maxBytes) {
        URI uri = verifyUrlAllowed(url);
        HttpResponse<InputStream> response = sendFollowingAllowedRedirect(uri);
        try (InputStream in = response.body()) {
            requireOk(response, uri);
            return readBounded(in, maxBytes, uri);
        } catch (IOException e) {
            throw new PluginCatalogException(PluginCatalogErrorCode.DOWNLOAD_FAILED,
                    "failed to read " + uri + ": " + e.getMessage());
        }
    }

    /**
     * 把一个受信 URL 的内容<b>流式</b>写入 {@code target}（受 {@code maxBytes} 上限约束）。返回写入字节数。校验失败 /
     * 网络失败 / 超限 / 非 200 → {@link PluginCatalogException}（超限 / 失败时 {@code target} 可能为半成品，由调用方清理）。
     */
    public long streamToFile(String url, long maxBytes, Path target) {
        URI uri = verifyUrlAllowed(url);
        HttpResponse<InputStream> response = sendFollowingAllowedRedirect(uri);
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(target)) {
            requireOk(response, uri);
            return copyBounded(in, out, maxBytes, uri);
        } catch (IOException e) {
            throw new PluginCatalogException(PluginCatalogErrorCode.DOWNLOAD_FAILED,
                    "failed to download " + uri + ": " + e.getMessage());
        }
    }

    /**
     * 用<b>已校验并规范化</b>的 {@link URI}（出自 {@link #verifyUrlAllowed}，已 trim、scheme/host/IP 全部通过）发起请求——
     * 全链路只解析一次 URI，不再从原始字符串二次 {@code URI.create}，杜绝「校验 trim 后的串、却用原始串发请求」的不一致。
     * {@link HttpRequest#newBuilder(URI)} 理论上不会因已校验的 URI 抛出 {@link IllegalArgumentException}；仍做防御性兜底，
     * 绝不让它逃逸为未受控的 500（manifest-url 配置错误最终由调用方归 {@code CATALOG_UNAVAILABLE}，包 URL 错误保持稳定码）。
     */
    private HttpResponse<InputStream> send(URI uri) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("Accept", "*/*")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new PluginCatalogException(PluginCatalogErrorCode.INSECURE_URL,
                    "cannot build request for url '" + uri + "': " + e.getMessage());
        }
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new PluginCatalogException(PluginCatalogErrorCode.DOWNLOAD_FAILED,
                    "failed to connect to " + uri + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PluginCatalogException(PluginCatalogErrorCode.DOWNLOAD_FAILED, "download interrupted: " + uri);
        }
    }

    /**
     * 发送请求，并在配置了重定向白名单时<b>跟随至多一跳</b>白名单内的重定向（详见完整构造说明）。白名单为空时不跟随，
     * 由后续 {@link #requireOk} 把 3xx 当失败拒绝（严格档行为）。
     */
    private HttpResponse<InputStream> sendFollowingAllowedRedirect(URI uri) {
        HttpResponse<InputStream> response = send(uri);
        if (isRedirect(response.statusCode()) && allowRedirects) {
            URI target = resolveAllowedRedirect(response, uri);
            closeQuietly(response);
            HttpResponse<InputStream> hop = send(target);
            if (isRedirect(hop.statusCode())) {
                closeQuietly(hop);
                throw new PluginCatalogException(PluginCatalogErrorCode.DOWNLOAD_FAILED,
                        "refusing to follow more than one redirect for " + uri);
            }
            return hop;
        }
        return response;
    }

    private static boolean isRedirect(int code) {
        return code >= 300 && code < 400;
    }

    /**
     * 解析并校验一个 3xx 的 {@code Location}：必须存在、对该目标重跑 {@link #verifyUrlAllowed}（scheme / 主机；未代理时含
     * SSRF/IP 段），且主机必须命中重定向白名单（相等或为白名单域的子域）。任一不满足 → {@code DOWNLOAD_FAILED}。
     */
    private URI resolveAllowedRedirect(HttpResponse<?> response, URI from) {
        String location = response.headers().firstValue("Location").orElse(null);
        if (location == null || location.isBlank()) {
            throw new PluginCatalogException(PluginCatalogErrorCode.DOWNLOAD_FAILED,
                    "redirect (HTTP " + response.statusCode() + ") without Location header for " + from);
        }
        URI rawTarget;
        try {
            rawTarget = new URI(location.trim());
        } catch (URISyntaxException e) {
            throw new PluginCatalogException(PluginCatalogErrorCode.DOWNLOAD_FAILED,
                    "malformed redirect Location for " + from + ": " + e.getMessage());
        }
        URI target = verifyUrlAllowed(from.resolve(rawTarget).toString());
        String host = target.getHost();
        if (host == null || (!redirectAllowlistDomains.isEmpty() && !isAllowedRedirectHost(host))) {
            throw new PluginCatalogException(PluginCatalogErrorCode.DOWNLOAD_FAILED,
                    "refusing to follow redirect to non-allowlisted host '" + host + "' for " + from);
        }
        return target;
    }

    /**
     * 主机是否命中重定向白名单：与某白名单域相等，或为其子域（{@code endsWith("." + domain)}，带点边界、非子串匹配）。
     * 包内可见，供单测直接验证 GitHub release 资产 CDN 子域（{@code release-assets.} / {@code objects.githubusercontent.com}）
     * 被允许、而 {@code evil-githubusercontent.com} / {@code …githubusercontent.com.attacker.tld} 被拒。
     */
    boolean isAllowedRedirectHost(String host) {
        String h = stripBrackets(host).toLowerCase(Locale.ROOT);
        for (String domain : redirectAllowlistDomains) {
            String d = domain.toLowerCase(Locale.ROOT);
            if (h.equals(d) || h.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    /** 尽力关闭一个响应体以释放连接（跟随重定向前丢弃首个响应 / 拒绝多跳时丢弃次响应）。 */
    private static void closeQuietly(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // best-effort：连接释放失败不影响后续逻辑
        }
    }

    /** 仅接受 200；3xx 一律拒绝（禁用重定向、不跟随到二次地址），其它非 200 视为下载失败。 */
    private static void requireOk(HttpResponse<?> response, URI uri) {
        int code = response.statusCode();
        if (code >= 300 && code < 400) {
            throw new PluginCatalogException(PluginCatalogErrorCode.DOWNLOAD_FAILED,
                    "refusing to follow redirect (HTTP " + code + ") for " + uri);
        }
        if (code != 200) {
            throw new PluginCatalogException(PluginCatalogErrorCode.DOWNLOAD_FAILED,
                    "unexpected HTTP status " + code + " for " + uri);
        }
    }

    private static byte[] readBounded(InputStream in, long maxBytes, URI uri) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        copyBounded(in, buffer, maxBytes, uri);
        return buffer.toByteArray();
    }

    private static long copyBounded(InputStream in, OutputStream out, long maxBytes, URI uri) throws IOException {
        byte[] chunk = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                // PluginCatalogException 是 RuntimeException、非 IOException，直接向上传播（不被外层 catch(IOException) 吞）。
                throw new PluginCatalogException(PluginCatalogErrorCode.DOWNLOAD_TOO_LARGE,
                        "response exceeds " + maxBytes + " bytes: " + uri);
            }
            out.write(chunk, 0, read);
        }
        return total;
    }

    /**
     * 校验一个 URL 可被安全访问并<b>返回规范化后的 {@link URI}</b>（已 trim）：scheme 在允许集合内、主机存在、解析到的全部 IP
     * 都不在被禁地址段。失败抛 {@link PluginCatalogException}（{@link PluginCatalogErrorCode#INSECURE_URL} 或
     * {@link PluginCatalogErrorCode#BLOCKED_ADDRESS}）。<b>返回值即后续 {@link #send} 实际使用的同一个 URI</b>——调用方
     * （{@link #fetchBytes} / {@link #streamToFile}）必须用它发请求，确保「校验的值」与「发请求的值」严格一致（例如配置里
     * 首尾带空白的 manifest-url / packageUrl 在此 trim 后全链路通用，不会在发请求时再触发 {@code URI.create} 异常）。包内
     * 可见，供单测直接验证。
     */
    URI verifyUrlAllowed(String url) {
        if (url == null || url.isBlank()) {
            throw new PluginCatalogException(PluginCatalogErrorCode.INSECURE_URL, "empty url");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new PluginCatalogException(PluginCatalogErrorCode.INSECURE_URL, "malformed url: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new PluginCatalogException(PluginCatalogErrorCode.INSECURE_URL, "missing url scheme: " + url);
        }
        Set<String> allowed = httpsOnly ? SCHEMES_HTTPS_ONLY : SCHEMES_HTTPS_AND_HTTP;
        if (!allowed.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new PluginCatalogException(PluginCatalogErrorCode.INSECURE_URL,
                    "unsupported url scheme '" + scheme + "' (only https allowed): " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new PluginCatalogException(PluginCatalogErrorCode.INSECURE_URL, "missing url host: " + url);
        }
        // 预设受信代理档跳过本地解析与 IP 段校验（DNS 由代理完成）；自定义档可要求代理请求也先按本机 DNS 结果执行阻断。
        // 后者能挡 IP 字面量与本机明确解析到非公网的目标，但无法约束代理端可能不同的 DNS 视图。
        if (!proxied || validateAddressesWhenProxied) {
            InetAddress[] addresses;
            try {
                addresses = InetAddress.getAllByName(stripBrackets(host));
            } catch (UnknownHostException e) {
                throw new PluginCatalogException(PluginCatalogErrorCode.BLOCKED_ADDRESS, "cannot resolve host: " + host);
            }
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address, allowNonPublicAddresses)) {
                    throw new PluginCatalogException(PluginCatalogErrorCode.BLOCKED_ADDRESS,
                            "refusing to connect to non-public address " + address.getHostAddress() + " for host " + host);
                }
            }
        }
        return uri;
    }

    /** IPv6 字面量主机 {@code [::1]} → {@code ::1}，供 {@link InetAddress#getAllByName} 解析。 */
    private static String stripBrackets(String host) {
        if (host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /**
     * 一个地址是否应被禁止访问（SSRF 防护）。{@code allowNonPublic=true}（仅测试）放开全部校验；否则拒绝 loopback /
     * 未指定 / link-local / 组播 / 站点本地（10、172.16、192.168）/ IPv4 CGNAT（100.64/10）/ IPv6 ULA（fc00::/7）。
     * 包内可见 + static，供单测对各类地址逐一验证（用 IP 字面量、不触发 DNS）。
     */
    static boolean isBlockedAddress(InetAddress address, boolean allowNonPublic) {
        if (allowNonPublic) {
            return false;
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            // IPv4 CGNAT 100.64.0.0/10（isSiteLocalAddress 不覆盖）。
            return b0 == 100 && b1 >= 64 && b1 <= 127;
        }
        if (bytes.length == 16) {
            // IPv6 唯一本地地址 ULA fc00::/7。
            return (bytes[0] & 0xFE) == 0xFC;
        }
        return false;
    }
}
