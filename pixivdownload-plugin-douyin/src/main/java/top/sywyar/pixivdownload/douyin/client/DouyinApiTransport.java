package top.sywyar.pixivdownload.douyin.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.douyin.client.request.DouyinRequestHeaders;
import top.sywyar.pixivdownload.douyin.client.signature.DouyinSignedUriBuilder;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpResponse;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpTransportException;

import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

final class DouyinApiTransport {

    private static final Logger log = LoggerFactory.getLogger(DouyinApiTransport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_API_ATTEMPTS = 3;
    private static final long[] API_RETRY_DELAYS_MS = {1_000L, 2_000L};

    private final OutboundHttpClient httpClient;
    private final DouyinSignedUriBuilder signedUriBuilder;
    private final RetrySleeper retrySleeper;

    DouyinApiTransport(OutboundHttpClient httpClient,
                       DouyinSignedUriBuilder signedUriBuilder,
                       RetrySleeper retrySleeper) {
        this.httpClient = httpClient;
        this.signedUriBuilder = signedUriBuilder;
        this.retrySleeper = retrySleeper;
    }

    JsonNode fetchApiJson(String path, Map<String, ?> endpointParams, String cookie)
            throws DouyinClientException {
        RetryableApiRequestException lastFailure = null;
        DouyinEndpointRequestPolicy policy = DouyinEndpointRequestPolicy.forPath(path);
        for (int attempt = 0; attempt < MAX_API_ATTEMPTS; attempt++) {
            DouyinSignedUriBuilder.SignedRequest request = policy.requiresSignature()
                    ? signedUriBuilder.request(path, endpointParams, cookie)
                    : signedUriBuilder.unsignedRequest(path, endpointParams, cookie);
            try {
                return fetchJson(request.uri(), request.cookie(), policy.method());
            } catch (RetryableApiRequestException error) {
                lastFailure = error;
                if (attempt + 1 >= MAX_API_ATTEMPTS) {
                    throw error;
                }
                log.debug("Retrying Douyin API request after retryable upstream response: path={}, attempt={}",
                        path, attempt + 1);
                pauseBeforeRetry(API_RETRY_DELAYS_MS[attempt]);
            }
        }
        throw lastFailure == null
                ? new DouyinClientException(DouyinClientErrorCode.NETWORK_ERROR,
                "Douyin API request did not execute")
                : lastFailure;
    }

    byte[] fetchBytes(URI uri, String cookie) throws DouyinClientException {
        return fetchBytes(uri, cookie, "GET");
    }

    private JsonNode fetchJson(URI uri, String cookie, String method) throws DouyinClientException {
        byte[] bytes = fetchBytes(uri, cookie, method);
        if (bytes.length == 0) {
            throw new RetryableApiRequestException(DouyinClientErrorCode.SIGNATURE_REQUIRED,
                    "Douyin endpoint returned an empty response");
        }
        String body = new String(bytes, StandardCharsets.UTF_8);
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || root.isMissingNode() || root.isNull()) {
                throw new DouyinClientException(DouyinClientErrorCode.RESPONSE_STRUCTURE_UNRECOGNIZED,
                        "Douyin endpoint returned an empty JSON response");
            }
            return root;
        } catch (JsonProcessingException e) {
            if (DouyinErrorClassifier.looksLikeLoginOrRiskText(body)) {
                throw new DouyinClientException(DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE,
                        "Douyin response requires login or verification", e);
            }
            if (DouyinErrorClassifier.looksLikeSignatureText(body)) {
                throw new DouyinClientException(DouyinClientErrorCode.SIGNATURE_REQUIRED,
                        "Douyin endpoint rejected the signed request", e);
            }
            throw new DouyinClientException(DouyinClientErrorCode.RESPONSE_STRUCTURE_UNRECOGNIZED,
                    "Douyin endpoint did not return valid JSON", e);
        }
    }

    private byte[] fetchBytes(URI uri, String cookie, String method) throws DouyinClientException {
        try {
            OutboundHttpResponse response = httpClient.exchange(new OutboundHttpRequest(
                    uri, method, DouyinRequestHeaders.credentials(uri, cookie), new byte[0]));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            byte[] body = response.body();
            DouyinClientErrorCode code = DouyinErrorClassifier.classifyHttpStatus(
                    response.statusCode(), body);
            DouyinClientErrorCode resolved = code == null ? DouyinClientErrorCode.NETWORK_ERROR : code;
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                throw new RetryableApiRequestException(resolved,
                        "Douyin request returned HTTP " + response.statusCode());
            }
            throw new DouyinClientException(resolved,
                    "Douyin request returned HTTP " + response.statusCode());
        } catch (OutboundHttpTransportException e) {
            if (isTimeout(e)) {
                throw new RetryableApiRequestException(DouyinClientErrorCode.NETWORK_TIMEOUT,
                        "Douyin request timed out", e);
            }
            throw new RetryableApiRequestException(DouyinClientErrorCode.NETWORK_ERROR,
                    "Douyin network request failed", e);
        }
    }

    private void pauseBeforeRetry(long delayMs) throws DouyinClientException {
        try {
            retrySleeper.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DouyinClientException(DouyinClientErrorCode.CANCELLED,
                    "Douyin API retry interrupted", e);
        }
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

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long delayMs) throws InterruptedException;
    }

    private static final class RetryableApiRequestException extends DouyinClientException {
        private RetryableApiRequestException(DouyinClientErrorCode code, String message) {
            super(code, message);
        }

        private RetryableApiRequestException(DouyinClientErrorCode code, String message, Throwable cause) {
            super(code, message, cause);
        }
    }
}
