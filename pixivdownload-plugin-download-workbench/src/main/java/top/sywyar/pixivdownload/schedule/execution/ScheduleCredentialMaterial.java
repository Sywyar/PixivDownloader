package top.sywyar.pixivdownload.schedule.execution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

/** 宿主在复合租约内读取并在整轮结束清零的凭证材料；每次插件调用仍取得独立句柄副本。 */
final class ScheduleCredentialMaterial implements AutoCloseable {

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
