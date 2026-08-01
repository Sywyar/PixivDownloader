package top.sywyar.pixivdownload.schedule.execution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;

/** 宿主在复合租约内读取并在整轮结束清零的凭证材料；每次插件调用仍取得独立句柄副本。 */
final class ScheduleCredentialMaterial implements AutoCloseable {

    private static final int MAX_PERCENT_DECODE_ROUNDS = 16;

    private char[] secret;
    private final ScheduleCredentialEchoGuard echoGuard;
    private final String reference;
    private String accountKey;

    ScheduleCredentialMaterial(String secret, String reference, String accountKey) {
        this.secret = secret == null ? new char[0] : secret.toCharArray();
        this.echoGuard = new ScheduleCredentialEchoGuard(this.secret);
        this.reference = reference;
        this.accountKey = accountKey;
    }

    synchronized boolean isPresent() {
        return secret.length > 0;
    }

    synchronized void setAccountKey(String accountKey) {
        this.accountKey = accountKey;
    }

    boolean containsEcho(String candidate) {
        return echoGuard.matches(candidate);
    }

    /**
     * 检测 URL 等可逆百分号编码文本中的活动凭证回显。每轮只解释 {@code %HH}，不把加号改为空格；
     * 最多重复解码固定轮数，超过上限仍可继续解码的输入按可疑材料 fail-closed。
     */
    boolean containsEchoInPercentEncodedText(String candidate) {
        if (echoGuard.matchesSubstring(candidate)) {
            return true;
        }
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        String current = candidate;
        for (int round = 0; round < MAX_PERCENT_DECODE_ROUNDS; round++) {
            String decoded;
            try {
                decoded = decodePercentEncodedOnce(current);
            } catch (IllegalArgumentException failure) {
                return true;
            }
            if (decoded.equals(current)) {
                return false;
            }
            if (echoGuard.matchesSubstring(decoded)) {
                return true;
            }
            current = decoded;
        }
        return hasPercentEscape(current);
    }

    boolean containsEchoInJson(ObjectMapper objectMapper, String candidate) {
        if (containsEcho(candidate)) {
            return true;
        }
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        ArrayDeque<String> pendingJson = new ArrayDeque<>();
        pendingJson.add(candidate);
        while (!pendingJson.isEmpty()) {
            String json = pendingJson.removeFirst();
            boolean candidateMatches = false;
            ArrayDeque<String> embeddedCandidates = new ArrayDeque<>();
            try (JsonParser parser = objectMapper.createParser(json)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (!isScalarToken(token)) {
                        continue;
                    }
                    String text = parser.getText();
                    if (containsEcho(text)) {
                        candidateMatches = true;
                    }
                    if (token == JsonToken.VALUE_STRING) {
                        String embeddedCandidate = text.trim();
                        if (embeddedCandidate.startsWith("{")
                                || embeddedCandidate.startsWith("[")) {
                            embeddedCandidates.addLast(embeddedCandidate);
                        }
                    }
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // 非法 JSON 由调用方既有 schema 校验拒绝；这里只做凭证原文比对。
                continue;
            }
            if (candidateMatches) {
                return true;
            }
            pendingJson.addAll(embeddedCandidates);
        }
        return false;
    }

    private static boolean isScalarToken(JsonToken token) {
        return token == JsonToken.FIELD_NAME
                || token == JsonToken.VALUE_STRING
                || token == JsonToken.VALUE_NUMBER_INT
                || token == JsonToken.VALUE_NUMBER_FLOAT
                || token == JsonToken.VALUE_TRUE
                || token == JsonToken.VALUE_FALSE
                || token == JsonToken.VALUE_NULL;
    }

    private static String decodePercentEncodedOnce(String value) {
        if (!hasPercentEscape(value)) {
            return value;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length();) {
            char current = value.charAt(index);
            if (current == '%' && index + 2 < value.length()) {
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high >= 0 && low >= 0) {
                    bytes.write((high << 4) | low);
                    index += 3;
                    continue;
                }
            }
            int codePoint = value.codePointAt(index);
            bytes.writeBytes(new String(Character.toChars(codePoint))
                    .getBytes(StandardCharsets.UTF_8));
            index += Character.charCount(codePoint);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("invalid percent-encoded UTF-8", failure);
        }
    }

    private static boolean hasPercentEscape(String value) {
        for (int index = 0; index + 2 < value.length(); index++) {
            if (value.charAt(index) == '%'
                    && Character.digit(value.charAt(index + 1), 16) >= 0
                    && Character.digit(value.charAt(index + 2), 16) >= 0) {
                return true;
            }
        }
        return false;
    }

    synchronized ScheduledCredentialHandle openHandle() {
        return new Handle(Arrays.copyOf(secret, secret.length), reference, accountKey);
    }

    synchronized void revoke() {
        Arrays.fill(secret, '\0');
        secret = new char[0];
        accountKey = null;
    }

    @Override
    public synchronized void close() {
        revoke();
        echoGuard.close();
    }

    private static final class Handle implements ScheduledCredentialHandle {

        private char[] secret;
        private final String reference;
        private final String accountKey;

        private Handle(char[] secret, String reference, String accountKey) {
            this.secret = secret;
            this.reference = reference;
            this.accountKey = accountKey;
        }

        @Override
        public synchronized boolean isPresent() {
            return secret.length > 0;
        }

        @Override
        public String reference() {
            return reference;
        }

        @Override
        public String accountKey() {
            return accountKey;
        }

        @Override
        public synchronized char[] copySecret() {
            return Arrays.copyOf(secret, secret.length);
        }

        @Override
        public synchronized void close() {
            Arrays.fill(secret, '\0');
            secret = new char[0];
        }
    }
}
