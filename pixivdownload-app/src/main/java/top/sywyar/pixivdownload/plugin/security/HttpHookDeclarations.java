package top.sywyar.pixivdownload.plugin.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;

/** 仅保存注册时物化的注解纯值，方法与 Bean 由原 invocation registry 持有和排空。 */
@Component
public final class HttpHookDeclarations {
    public record Target(String owner, HttpMethod method, String path, List<String> params,
            List<String> headers, List<String> consumes, List<String> produces) {
        public Target {
            OperationPolicies.identity(owner);
            if (method == null || path == null || !path.startsWith("/") || path.startsWith("//")
                    || path.length() > 1024 || path.contains("?") || path.contains("#")
                    || path.chars().anyMatch(Character::isISOControl)) throw invalid();
            params = conditions(params); headers = conditions(headers);
            consumes = conditions(consumes); produces = conditions(produces);
        }
        public boolean matches(OperationTargets.Endpoint endpoint) {
            return owner.equals(endpoint.owner()) && endpoint.routes().contains(new OperationTargets.Route(method, path))
                    && matches(params, endpoint.parameters()) && matches(headers, endpoint.headers())
                    && matches(consumes, endpoint.consumes()) && matches(produces, endpoint.produces());
        }
        private static boolean matches(List<String> declared, List<String> actual) {
            return declared.isEmpty() || declared.equals(actual.stream().sorted().toList());
        }
        private static List<String> conditions(List<String> values) {
            if (values == null || values.size() > 64 || values.stream().anyMatch(value -> value == null
                    || value.isBlank() || value.length() > 1024 || value.chars().anyMatch(Character::isISOControl))
                    || values.stream().mapToInt(String::length).sum() > 16384
                    || new HashSet<>(values).size() != values.size()) throw invalid();
            return values.stream().sorted().toList();
        }
    }
    public record Declaration(String hook, String callback, Target target, OperationHook.Phase phase,
            int order, OperationHook.Completion completion, String configuration, List<OperationBinding.Field> fields,
            String revision) {
        public Declaration {
            fields = List.copyOf(fields);
            OperationPolicies.identity(revision);
            if (callback.length() > 512 || fields.size() > 16) throw invalid();
            HookContracts.json(configuration);
            if ((hook + callback + target + configuration + fields + revision).getBytes(StandardCharsets.UTF_8).length > 65536) throw invalid();
        }
        public OperationBinding binding(String id, String endpoint) {
            return new OperationBinding(id, hook, "", endpoint, phase, order, completion, configuration, fields);
        }
        public String contract() { return hash(toString()); }
    }
    public record Published(ExternalCapabilityOwner owner, Declaration declaration) {
        public String id() { return "http-annotation-" + hash(owner.pluginId() + ":" + declaration.hook()); }
        public String fingerprint() { return hash(owner + ":" + declaration.contract()); }
    }
    private volatile Map<ExternalCapabilityOwner, List<Published>> publications = Map.of();

    public synchronized void publish(ExternalCapabilityOwner owner, List<Declaration> declarations) {
        if (declarations.isEmpty()) return;
        if (declarations.size() > 16 || declarations.stream().map(Declaration::hook).distinct().count() != declarations.size()) throw invalid();
        var next = new HashMap<>(publications);
        next.put(owner, declarations.stream().map(value -> new Published(owner, value)).toList());
        if (next.values().stream().mapToInt(List::size).sum() > 1024
                || next.values().stream().flatMap(List::stream).mapToInt(value -> value.declaration().toString().getBytes(StandardCharsets.UTF_8).length).sum() > 4 * 1024 * 1024)
            throw invalid();
        publications = Map.copyOf(next);
    }
    public synchronized void withdraw(ExternalCapabilityOwner owner) {
        var next = new HashMap<>(publications); next.remove(owner); publications = Map.copyOf(next);
    }
    public List<Published> snapshot() {
        return publications.values().stream().flatMap(List::stream)
                .sorted(Comparator.comparing(Published::id).thenComparingLong(value -> value.owner().publicationId())).toList();
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("OPERATION_HOOK_DECLARATION_INVALID"); }
}
