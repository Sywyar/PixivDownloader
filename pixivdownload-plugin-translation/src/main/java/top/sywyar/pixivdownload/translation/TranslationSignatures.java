package top.sywyar.pixivdownload.translation;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class TranslationSignatures {
    private TranslationSignatures() {}

    static String digest(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(utf8(message));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }

    static Map<String, String> sign(CloudProvider provider, TranslationConfig.CloudSettings settings,
                                    URI uri, String type, byte[] body, Instant now, String nonce) {
        String hash = digest("SHA-256", body);
        var signed = new TreeMap<String, String>();
        signed.put("host", uri.getHost());
        signed.put("content-type", type);
        String algorithm;
        String scope;
        String timestamp;
        byte[] key = utf8(settings.getSecret());
        switch (provider) {
            case TENCENT -> {
                algorithm = "TC3-HMAC-SHA256";
                String date = now.atZone(ZoneOffset.UTC).toLocalDate().toString();
                scope = date + "/tmt/tc3_request";
                timestamp = Long.toString(now.getEpochSecond());
                key = hmac(hmac(hmac(utf8("TC3" + settings.getSecret()), date), "tmt"), "tc3_request");
            }
            case VOLCENGINE -> {
                algorithm = "HMAC-SHA256";
                timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.ROOT)
                        .withZone(ZoneOffset.UTC).format(now);
                String date = timestamp.substring(0, 8);
                scope = date + "/cn-north-1/translate/request";
                signed.put("x-date", timestamp);
                signed.put("x-content-sha256", hash);
                key = hmac(hmac(hmac(hmac(key, date), "cn-north-1"), "translate"), "request");
            }
            case ALIYUN -> {
                algorithm = "ACS3-HMAC-SHA256";
                scope = "";
                timestamp = now.truncatedTo(ChronoUnit.SECONDS).toString();
                signed.put("x-acs-action", "TranslateGeneral");
                signed.put("x-acs-version", "2018-10-12");
                signed.put("x-acs-date", timestamp);
                signed.put("x-acs-signature-nonce", nonce);
                signed.put("x-acs-content-sha256", hash);
            }
            default -> throw new IllegalArgumentException("translation.unknown-engine");
        }
        String headers = signed.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue() + "\n")
                .collect(Collectors.joining());
        String names = String.join(";", signed.keySet());
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        String canonical = "POST\n/\n" + query + "\n" + headers + "\n" + names + "\n" + hash;
        String hashToSign = digest("SHA-256", utf8(canonical));
        boolean acs = provider == CloudProvider.ALIYUN;
        String message = algorithm + "\n" + (acs ? "" : timestamp + "\n" + scope + "\n") + hashToSign;
        String separator = acs ? "," : ", ";
        signed.put("Authorization", algorithm + " Credential=" + settings.getKeyId() + (acs ? "" : "/" + scope)
                + separator + "SignedHeaders=" + names + separator + "Signature=" + HexFormat.of().formatHex(hmac(key, message)));
        // The transport sets Host from the URI and Content-Type from the encoded request.
        signed.remove("host");
        signed.remove("content-type");
        if (provider == CloudProvider.TENCENT) {
            signed.put("X-TC-Action", "TextTranslate");
            signed.put("X-TC-Version", "2018-03-21");
            signed.put("X-TC-Timestamp", timestamp);
            signed.put("X-TC-Region", settings.getRegion());
        }
        return signed;
    }
}
