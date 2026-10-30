package top.sywyar.pixivdownload.setup;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.security.LoginVerification;
import top.sywyar.pixivdownload.plugin.api.security.LoginVerificationRequest;
import top.sywyar.pixivdownload.plugin.api.security.LoginVerificationResult;
import top.sywyar.pixivdownload.plugin.api.service.ServiceAccessException;
import top.sywyar.pixivdownload.plugin.api.service.ServiceKey;
import top.sywyar.pixivdownload.plugin.api.service.ServicePublication;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.LoginVerificationCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.setup.request.LoginRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 登录所需策略的同步组合；挑战只保留纯值和精确 publication，不持有插件对象。 */
@Component
public final class LoginVerificationGate {
    private final ExternalCapabilityInvocationRegistry registry;
    private final List<String> policies;
    private final List<ServiceKey<LoginVerification>> keys;
    private final AppMessages messages;
    private final Clock clock;
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    @Autowired
    public LoginVerificationGate(ExternalCapabilityInvocationRegistry registry, AppMessages messages,
            @Value("${setup.login-verification-policies:}") String configuredPolicies) {
        this(registry, messages, configuredPolicies, Clock.systemUTC());
    }

    LoginVerificationGate(ExternalCapabilityInvocationRegistry registry, AppMessages messages,
                          String configuredPolicies, Clock clock) {
        this.registry = registry;
        this.messages = messages;
        this.clock = clock;
        policies = configuredPolicies.isBlank() ? List.of()
                : Arrays.stream(configuredPolicies.split(",", -1)).map(String::strip).toList();
        if (policies.size() > 16 || policies.stream().distinct().count() != policies.size()) {
            throw new IllegalArgumentException("LOGIN_POLICY_CONFIGURATION_INVALID");
        }
        keys = policies.stream().map(LoginVerificationCapabilityAdapter::key).toList();
    }

    private record Pending(String username, String address, List<ServicePublication> publications, long expires) { }
    public record ChallengeResponse(String code, String error, String verificationToken,
                                    Map<String, String> challenges) implements ApiErrorResponse { }

    @FunctionalInterface
    public interface LoginAction { ResponseEntity<?> run() throws IOException; }

    public ResponseEntity<?> verify(LoginRequest request, String address, LoginAction login) throws IOException {
        Map<String, String> proofs = validateProofs(request.getVerificationProofs());
        String token = request.getVerificationToken();
        boolean response = token != null && !token.isBlank();
        if (token != null && token.length() > 128 || !response && !proofs.isEmpty()) {
            throw failure(HttpStatus.CONFLICT, "stale");
        }
        Pending previous = response ? consume(token) : null;
        if (response && (previous == null || !previous.username().equals(request.getUsername())
                || !previous.address().equals(address))) throw failure(HttpStatus.CONFLICT, "stale");
        if (keys.isEmpty()) {
            if (response) throw failure(HttpStatus.CONFLICT, "stale");
            return login.run();
        }
        String attempt = response ? token : UUID.randomUUID().toString();
        try {
            return registry.withHostServices(keys, providers -> {
                var publications = providers.stream().map(provider -> provider.publication()).toList();
                if (previous != null && !previous.publications().equals(publications)) {
                    throw failure(HttpStatus.CONFLICT, "stale");
                }
                Map<String, String> challenges = new LinkedHashMap<>();
                int challengeBytes = 0;
                for (int i = 0; i < providers.size(); i++) {
                    String policy = policies.get(i);
                    var result = providers.get(i).service().verify(new LoginVerificationRequest(
                            request.getUsername(), address, attempt, response, proofs.getOrDefault(policy, "")));
                    if (result == null || result.status() == null) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "unavailable");
                    if (result.status() == LoginVerificationResult.Status.REJECTED) throw failure(HttpStatus.FORBIDDEN, "rejected");
                    if (result.status() == LoginVerificationResult.Status.CHALLENGE) {
                        String challenge = result.challenge();
                        if (challenge == null || challenge.length() > 16 * 1024) {
                            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "unavailable");
                        }
                        int bytes = challenge.getBytes(StandardCharsets.UTF_8).length;
                        challengeBytes += bytes;
                        if (bytes > 16 * 1024 || challengeBytes > 64 * 1024) {
                            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "unavailable");
                        }
                        challenges.put(policy, challenge);
                    }
                }
                if (challenges.isEmpty()) return login.run();
                // 一个挑战只接纳一次响应；再次挑战需要重新校验密码并开始新的尝试。
                if (response) throw failure(HttpStatus.FORBIDDEN, "rejected");
                retain(attempt, new Pending(request.getUsername(), address, publications, clock.millis() + 300_000));
                String code = "setup.login.verification.required";
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                        new ChallengeResponse(code, messages.get(code), attempt, Map.copyOf(challenges)));
            });
        } catch (ServiceAccessException unavailable) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "unavailable");
        }
    }

    private Map<String, String> validateProofs(Map<String, String> input) {
        if (input == null || input.size() > 16) throw failure(HttpStatus.BAD_REQUEST, "invalid");
        int bytes = 0;
        Map<String, String> safe = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            String value = entry.getValue();
            if (!policies.contains(entry.getKey()) || value == null || value.length() > 16 * 1024) {
                throw failure(HttpStatus.BAD_REQUEST, "invalid");
            }
            int size = value.getBytes(StandardCharsets.UTF_8).length;
            bytes += size;
            if (size > 16 * 1024 || bytes > 64 * 1024) throw failure(HttpStatus.BAD_REQUEST, "invalid");
            safe.put(entry.getKey(), value);
        }
        return Map.copyOf(safe);
    }

    private synchronized Pending consume(String token) {
        expire();
        return pending.remove(token);
    }

    private synchronized void retain(String token, Pending value) {
        expire();
        if (pending.size() >= 256) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "unavailable");
        pending.put(token, value);
    }

    private void expire() {
        long now = clock.millis();
        pending.values().removeIf(item -> item.expires() <= now);
    }

    @jakarta.annotation.PreDestroy
    void close() { synchronized (this) { pending.clear(); } }

    private static LocalizedException failure(HttpStatus status, String code) {
        return new LocalizedException(status, "setup.login.verification." + code, null);
    }
}
