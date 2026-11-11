package top.sywyar.pixivdownload.plugin.security;

import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.common.InstanceCookies;
import top.sywyar.pixivdownload.plugin.api.operation.OperationTargets;

/** 证明暂存只保存有界纯值；准备不授予业务权限，真实请求仍核对发布并消费一次。 */
@Component
public final class HookChallenges {
    static final String TICKET_HEADER = "X-Pixiv-Hook-Ticket";
    private static final String IDENTITY_COOKIE = "pixiv_hook_client", TICKET_COOKIE = "pixiv_hook_ticket_";
    private final byte[] key = new byte[32];
    private final java.time.Clock clock;
    private final Map<String, Pending> pending = new HashMap<>();
    record Pending(String digest, List<OperationHookCalls.Identity> providers, long expires, Map<String, String> proofs, String nativeSubject) { }
    record Target(String subject, String scope, String digest) { }
    record Ticket(String token, Pending proof) { }
    record Response(String code, String error, boolean invoked, String token, String revision, Map<String, String> challenges)
            implements top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse { }

    @org.springframework.beans.factory.annotation.Autowired
    public HookChallenges() { this(java.time.Clock.systemUTC()); }
    public HookChallenges(java.time.Clock clock) { this.clock = clock; new SecureRandom().nextBytes(key); }

    String subject(HttpServletRequest request, HttpServletResponse response) {
        String identityCookie = InstanceCookies.name(request, IDENTITY_COOKIE);
        String client = cookie(request, identityCookie);
        if (!client.matches("[a-f0-9-]{36}\\.[a-f0-9]{64}") || !MessageDigest.isEqual(
                client.substring(37).getBytes(StandardCharsets.US_ASCII), sign(client.substring(0, 36)).getBytes(StandardCharsets.US_ASCII))) {
            String id = UUID.randomUUID().toString(); client = id + "." + sign(id);
            setCookie(response, request, identityCookie, client, -1);
        }
        return sign(client + "\n" + request.getRemoteAddr());
    }

    Target target(HttpServletRequest request, OperationTargets.Endpoint endpoint, String revision, String subject, String input) {
        var headers = new TreeMap<String, String>();
        for (String condition : endpoint.headers()) {
            String name = condition.split("[!=]", 2)[0];
            if (name.isEmpty()) name = condition.substring(1).split("[!=]", 2)[0];
            headers.put(name, Objects.toString(request.getHeader(name), ""));
        }
        var parameters = new TreeMap<String, List<String>>();
        for (String condition : endpoint.parameters()) {
            String name = condition.replaceFirst("^!", "").split("[!=]", 2)[0];
            String[] values = request.getParameterValues(name);
            if (values != null) {
                if (values.length > 128 || Arrays.stream(values).anyMatch(value -> value.length() > 4096)) throw new IllegalArgumentException("HOOK_INPUT_LIMIT");
                parameters.put(name, Arrays.asList(values));
            }
        }
        String scope = sign(endpoint.id() + "\n" + subject + "\n" + request.getMethod() + "\n"
                + request.getRequestURI() + "\n" + Objects.toString(request.getQueryString(), "") + "\n" + headers + "\n"
                + HookRequestFilter.JSON.valueToTree(parameters) + "\n" + input);
        return new Target(subject, scope, sign(endpoint + "\n" + revision + "\n" + scope));
    }

    synchronized boolean retain(String token, String digest, List<OperationHookCalls.Identity> providers) {
        expire();
        if (pending.size() >= 256) return false;
        pending.put(token, new Pending(digest, List.copyOf(providers), clock.millis() + 300_000, null, ""));
        return true;
    }

    synchronized boolean stage(String token, String digest, List<OperationHookCalls.Identity> providers, Map<String, String> proofs) {
        expire();
        var value = pending.get(token);
        if (value == null || value.proofs() != null || !value.digest().equals(digest) || !value.providers().equals(providers)) return false;
        if (proofs == null || proofs.size() > 16) return false;
        int bytes = 0;
        for (var proof : proofs.entrySet()) {
            if (proof.getKey() == null || !proof.getKey().matches("[a-z0-9][a-z0-9-]{0,95}") || proof.getValue() == null
                    || proof.getValue().length() > 16384) return false;
            bytes += proof.getValue().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 65536) return false;
        }
        pending.put(token, new Pending(value.digest(), value.providers(), value.expires(), Map.copyOf(proofs), ""));
        return true;
    }

    synchronized Ticket consume(HttpServletRequest request, HttpServletResponse response, Target target, List<OperationHookCalls.Identity> providers) {
        expire();
        String ticketPrefix = InstanceCookies.name(request, TICKET_COOKIE);
        String value = request.getHeader(TICKET_HEADER);
        if (value != null && value.isEmpty()) value = null;
        Cookie selected = null;
        if (request.getCookies() != null) for (var cookie : request.getCookies()) {
            if (!cookie.getName().startsWith(ticketPrefix) || !cookie.getValue().equals(target.scope())) continue;
            String token = cookie.getName().substring(ticketPrefix.length());
            if (value != null) { if (token.equals(value)) selected = cookie; }
            else if (selected == null || matches(pending.get(token), target, providers)) {
                selected = cookie;
            }
        }
        if (value == null) value = selected == null ? "" : selected.getName().substring(ticketPrefix.length());
        else if (value.length() > 128) value = "invalid";
        if (selected != null) setCookie(response, request, selected.getName(), "", 0);
        var proof = pending.get(value);
        if (!matches(proof, target, providers)) return new Ticket(value, null);
        pending.remove(value);
        return new Ticket(value, proof);
    }

    private static boolean matches(Pending proof, Target target, List<OperationHookCalls.Identity> providers) {
        return proof != null && proof.proofs() != null && proof.digest().equals(target.digest()) && proof.providers().equals(providers);
    }

    synchronized void nativeTicket(HttpServletRequest request, HttpServletResponse response, String token, Target target) {
        expire();
        String ticketPrefix = InstanceCookies.name(request, TICKET_COOKIE);
        if (request.getCookies() != null) for (var cookie : request.getCookies()) if (cookie.getName().startsWith(ticketPrefix)) {
            if (!pending.containsKey(cookie.getName().substring(ticketPrefix.length()))) setCookie(response, request, cookie.getName(), "", 0);
        }
        var proof = pending.get(token);
        if (proof == null || pending.values().stream().filter(value -> value.nativeSubject().equals(target.subject())).count() >= 16)
            throw new IllegalStateException("HOOK_UNAVAILABLE");
        pending.put(token, new Pending(proof.digest(), proof.providers(), proof.expires(), proof.proofs(), target.subject()));
        // 每张票据独立命名；同目标并发提交也不会互相覆盖 Cookie。
        setCookie(response, request, ticketPrefix + token, target.scope(), (int) ((proof.expires() - clock.millis() + 999) / 1000));
    }

    private void expire() { pending.values().removeIf(item -> item.expires() <= clock.millis()); }
    private String sign(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("HOOK_IDENTITY_UNAVAILABLE", failure); }
    }
    private static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() != null) for (var cookie : request.getCookies()) if (name.equals(cookie.getName())) return cookie.getValue();
        return "";
    }
    private static void setCookie(HttpServletResponse response, HttpServletRequest request, String name, String value, int age) {
        var cookie = new Cookie(name, value); cookie.setHttpOnly(true); cookie.setSecure(request.isSecure());
        cookie.setPath("/"); cookie.setMaxAge(age); cookie.setAttribute("SameSite", "Strict"); response.addCookie(cookie);
    }
}
