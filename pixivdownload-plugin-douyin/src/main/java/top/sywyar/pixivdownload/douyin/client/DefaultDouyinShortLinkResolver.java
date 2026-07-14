package top.sywyar.pixivdownload.douyin.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedInput;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public class DefaultDouyinShortLinkResolver implements DouyinShortLinkResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultDouyinShortLinkResolver.class);
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_INPUT_LENGTH = 2_048;

    private final DouyinUrlParser parser;
    private final DouyinRedirectClient redirectClient;

    public DefaultDouyinShortLinkResolver(DouyinUrlParser parser, DouyinRedirectClient redirectClient) {
        this.parser = parser;
        this.redirectClient = redirectClient;
    }

    @Override
    public DouyinParsedInput resolve(String input, String cookie) throws DouyinClientException {
        URI current = normalize(input);
        if (!DouyinUrlParser.isShortHost(current.getHost())) {
            throw new DouyinClientException(DouyinClientErrorCode.INVALID_SHORT_URL, "Invalid Douyin short URL");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            String key = current.normalize().toString();
            if (!seen.add(key)) {
                throw new DouyinClientException(DouyinClientErrorCode.REDIRECT_LOOP, "Douyin short URL redirect loop");
            }
            ensureAllowedHop(current);
            String hopCookie = DouyinRequestHeaders.isCredentialOrigin(current) ? cookie : null;
            DouyinRedirectResponse response = get(current, hopCookie);
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                URI next = resolveLocation(current, response.location());
                if (next == null) {
                    throw new DouyinClientException(DouyinClientErrorCode.SHORT_LINK_UNRESOLVED,
                            "Douyin short URL redirect has no Location");
                }
                if (!isAllowedFinalHost(next.getHost())) {
                    log.info("Douyin short URL rejected non-Douyin redirect target: host={}", safeHost(next));
                    throw new DouyinClientException(DouyinClientErrorCode.NON_DOUYIN_TARGET,
                            "Douyin short URL redirected to a non-Douyin target: host=" + safeHost(next));
                }
                current = next;
                continue;
            }
            if (status >= 400) {
                DouyinClientErrorCode code = status == 429
                        ? DouyinClientErrorCode.HTTP_RATE_LIMITED
                        : DouyinErrorClassifier.classifyHttpStatus(status, response.body());
                throw new DouyinClientException(code == null ? DouyinClientErrorCode.NETWORK_ERROR : code,
                        "Douyin short URL returned HTTP " + status);
            }
            if (DouyinErrorClassifier.looksLikeLoginOrRiskPage(response.body())) {
                throw new DouyinClientException(DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE,
                        "Douyin short URL reached a login or verification page");
            }
            if (!isAllowedFinalHost(current.getHost())) {
                throw new DouyinClientException(DouyinClientErrorCode.NON_DOUYIN_TARGET,
                        "Douyin short URL final target is outside Douyin: host=" + safeHost(current));
            }
            Optional<DouyinParsedInput> parsed = parser.parse(current.toString());
            if (parsed.isPresent() && parsed.get().kind() != top.sywyar.pixivdownload.douyin.model.DouyinParsedKind.SHORT_LINK) {
                return parsed.get();
            }
            throw new DouyinClientException(DouyinClientErrorCode.UNSUPPORTED_FINAL_URL,
                    "Douyin short URL final target is not a supported Douyin URL");
        }
        throw new DouyinClientException(DouyinClientErrorCode.REDIRECT_LOOP, "Too many Douyin short URL redirects");
    }

    private DouyinRedirectResponse get(URI uri, String cookie) throws DouyinClientException {
        try {
            return redirectClient.get(uri, cookie);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                throw new DouyinClientException(DouyinClientErrorCode.NETWORK_TIMEOUT,
                        "Douyin short URL request timed out", e);
            }
            throw new DouyinClientException(DouyinClientErrorCode.NETWORK_ERROR,
                    "Douyin short URL network request failed", e);
        } catch (SocketTimeoutException e) {
            throw new DouyinClientException(DouyinClientErrorCode.NETWORK_TIMEOUT,
                    "Douyin short URL request timed out", e);
        } catch (IOException e) {
            throw new DouyinClientException(DouyinClientErrorCode.NETWORK_ERROR,
                    "Douyin short URL network request failed", e);
        }
    }

    private static URI normalize(String input) throws DouyinClientException {
        String value = input == null ? "" : input.trim();
        if (value.isBlank() || value.length() > MAX_INPUT_LENGTH) {
            throw new DouyinClientException(DouyinClientErrorCode.INVALID_SHORT_URL, "Invalid Douyin short URL");
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme)) {
                throw new DouyinClientException(DouyinClientErrorCode.INVALID_SHORT_URL, "Invalid Douyin short URL scheme");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new DouyinClientException(DouyinClientErrorCode.INVALID_SHORT_URL, "Invalid Douyin short URL", e);
        }
    }

    private static URI resolveLocation(URI current, URI location) {
        if (location == null) {
            return null;
        }
        URI next = current.resolve(location);
        String scheme = next.getScheme();
        if (!"https".equalsIgnoreCase(scheme)) {
            return null;
        }
        return next;
    }

    private static void ensureAllowedHop(URI uri) throws DouyinClientException {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || !isAllowedFinalHost(uri.getHost())) {
            throw new DouyinClientException(DouyinClientErrorCode.NON_DOUYIN_TARGET,
                    "Douyin short URL hop is not an allowed HTTPS target: host=" + safeHost(uri));
        }
    }

    private static boolean isAllowedFinalHost(String host) {
        String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return normalized.equals("douyin.com")
                || normalized.endsWith(".douyin.com")
                || normalized.equals("iesdouyin.com")
                || normalized.endsWith(".iesdouyin.com");
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current.getClass().getName().toLowerCase(Locale.ROOT).contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String safeHost(URI uri) {
        return uri == null || uri.getHost() == null ? "<none>" : uri.getHost().toLowerCase(Locale.ROOT);
    }
}
