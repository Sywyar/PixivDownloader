package top.sywyar.pixivdownload.plugin.security;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import top.sywyar.pixivdownload.plugin.api.operation.*;
import top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore.HookRequirement;

/** 经单独批准的字段投影；正文只在存在对应授权时读取，流式响应不缓存。 */
public final class HookFields {
    private static final int BODY_LIMIT = 65536;

    public static void validate(OperationBinding binding) {
        if (binding.fields().size() > 16) throw invalid();
        var names = new HashSet<String>();
        for (var field : binding.fields()) {
            if (field == null || field.source() == null || field.name() == null || !field.name().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || !names.add((field.source() == OperationBinding.Source.RESULT ? "result:" : "input:") + field.name())
                    || field.path() == null || field.path().isEmpty() || field.path().length() > 256) throw invalid();
            String path = field.path();
            if (field.source() != OperationBinding.Source.QUERY) {
                if (!path.startsWith("/") || path.split("/", -1).length > 9) throw invalid();
                try { com.fasterxml.jackson.core.JsonPointer.compile(path); } catch (IllegalArgumentException failure) { throw invalid(); }
            }
            for (String segment : path.split("/")) {
                String name = segment.replace("~1", "/").replace("~0", "~").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (name.matches(".*(password|passwd|secret|cookie|session|token|proof|attempt|credential|authorization|invitecode|apikey|privatekey).*")
                        || field.source() != OperationBinding.Source.RESULT && name.equals("code")) throw invalid();
            }
            if (field.source() == OperationBinding.Source.RESULT && binding.phase() != OperationHook.Phase.AFTER_COMPLETION) throw invalid();
        }
    }

    public static void contract(OperationBinding binding, OperationHook.Definition definition) throws IOException {
        for (boolean result : List.of(false, true)) {
            if (result && binding.phase() != OperationHook.Phase.AFTER_COMPLETION) continue;
            String schema = result ? definition.resultSchema() : definition.inputSchema();
            var names = binding.fields().stream().filter(field -> (field.source() == OperationBinding.Source.RESULT) == result)
                    .map(OperationBinding.Field::name).toList();
            // 不读取输入的 provider 仍可使用经批准字段的宿主摘要，字段不会传给 provider。
            if (schema.isEmpty()) { if (result && !names.isEmpty()) throw invalid(); continue; }
            JsonNode required = HookRequestFilter.JSON.readTree(schema).path("required");
            for (JsonNode name : required) if (!names.contains(name.asText())) throw invalid();
        }
    }

    static Map<String, String> inputs(HttpServletRequest request, List<HookRequirement> bindings) throws IOException {
        var preparation = (HookRequestFilter.Preparation) request.getAttribute(HookRequestFilter.PREPARATION);
        boolean needsJson = bindings.stream().flatMap(binding -> binding.binding().fields().stream())
                .anyMatch(field -> field.source() == OperationBinding.Source.JSON);
        JsonNode json = null;
        if (needsJson && preparation == null) {
            try {
                if (request.getContentType() == null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(request.getContentType()))) throw invalid();
                var replay = org.springframework.web.util.WebUtils.getNativeRequest(request, top.sywyar.pixivdownload.common.web.BoundedBodyRequest.class);
                if (replay == null) throw invalid();
                json = HookRequestFilter.JSON.readTree(replay.readBody(BODY_LIMIT));
            } catch (IOException | RuntimeException unavailable) { /* 下方按绑定阶段拒绝前置或标记缺席的后置输入。 */ }
        }
        Map<String, List<String>> query = new HashMap<>();
        if (bindings.stream().flatMap(binding -> binding.binding().fields().stream()).anyMatch(field -> field.source() == OperationBinding.Source.QUERY)) {
            try {
                String raw = Objects.toString(request.getQueryString(), "");
                if (raw.length() > 8192) throw invalid();
                for (String part : raw.split("&", -1)) {
                    if (query.values().stream().mapToInt(List::size).sum() >= 128) throw invalid();
                    var pair = part.split("=", 2);
                    query.computeIfAbsent(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), ignored -> new ArrayList<>())
                            .add(URLDecoder.decode(pair.length == 1 ? "" : pair[1], StandardCharsets.UTF_8));
                }
            } catch (RuntimeException unavailable) { query = null; }
        }
        var inputs = new TreeMap<String, String>();
        for (var binding : bindings) {
            try {
                var value = HookRequestFilter.JSON.createObjectNode();
                for (var field : binding.binding().fields()) {
                    JsonNode selected;
                    if (field.source() == OperationBinding.Source.QUERY) {
                        if (query == null) throw invalid();
                        var values = query.get(field.path());
                        selected = values == null ? null : HookRequestFilter.JSON.valueToTree(values.size() == 1 ? values.get(0) : values);
                    } else if (field.source() == OperationBinding.Source.JSON) {
                        if (preparation == null && json == null) throw invalid();
                        selected = preparation == null ? json.at(field.path()) : preparation.json() == null ? null : preparation.json().get(field.path());
                    } else continue;
                    put(value, field.name(), selected);
                }
                String projected = value.toString(); HookContracts.json(projected);
                inputs.put(binding.binding().id(), projected);
            } catch (RuntimeException unavailable) {
                if (binding.binding().phase() == OperationHook.Phase.BEFORE_INVOKE) throw unavailable;
                // 缺席输入在完成回调处诊断，不影响其它绑定或原业务。
            }
        }
        return Map.copyOf(inputs);
    }

    private static void put(com.fasterxml.jackson.databind.node.ObjectNode target, String name, JsonNode value) {
        if (value == null || value.isMissingNode()) return;
        if (value.isContainerNode() && (!value.isArray() || value.size() > 64)) throw invalid();
        if (value.isArray()) for (JsonNode item : value) if (item.isContainerNode()) throw invalid();
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > 4096) throw invalid();
        target.set(name, value);
    }

    static Map<String, String> results(List<HookRequirement> bindings, byte[] bytes, String type) {
        JsonNode json;
        try {
            if (bytes == null || type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(type))) return Map.of();
            json = HookRequestFilter.JSON.readTree(bytes);
            if (json == null) return Map.of();
        } catch (IOException | RuntimeException unavailable) { return Map.of(); }
        var results = new HashMap<String, String>();
        for (var binding : bindings) {
            try {
                var value = HookRequestFilter.JSON.createObjectNode();
                for (var field : binding.binding().fields()) if (field.source() == OperationBinding.Source.RESULT) put(value, field.name(), json.at(field.path()));
                String projected = value.toString(); HookContracts.json(projected); results.put(binding.binding().id(), projected);
            } catch (RuntimeException unavailable) {
                // 单绑定缺席由完成回调诊断，其它有效投影和原响应保持不变。
            }
        }
        return Map.copyOf(results);
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("HOOK_FIELDS_INVALID"); }
}
