package top.sywyar.pixivdownload.download.state;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import top.sywyar.pixivdownload.plugin.api.storage.EnvironmentFileContribution;

import java.io.*;
import java.util.List;

/** 只保留工作台的保存偏好与手动队列，恢复后由用户主动开始。 */
public final class BatchStateTransfer implements EnvironmentFileContribution {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final List<String> PREFERENCES = List.of("pixiv_mode", "pixiv_search_filters",
            "pixiv_search_blur_r18", "pixiv_search_submode", "pixiv_user_input");
    private static final List<String> EXECUTION = List.of("taskId", "taskPhase", "taskMissing", "taskObserved",
            "recoveryState", "liveStatus", "cancelWorkKey", "startTime", "endTime", "statusMessageKey",
            "lastMessage", "lastMessageParts", "ugoiraProgress", "imageProgress");

    @Override public Definition definition() {
        return new Definition("workbench", Root.STATE, "", "batch_state\\.json", 1, Kind.STATE, 1, MAX_BYTES, false);
    }

    @Override public void copy(String name, InputStream source, InputStream existing, OutputStream output,
                               boolean includeCredentials) throws IOException {
        byte[] bytes = source.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IOException("transfer.file-limit");
        try {
            ObjectNode saved = object(JSON.readTree(bytes));
            ObjectNode result = JSON.createObjectNode();
            if (existing != null) {
                byte[] current = existing.readNBytes(MAX_BYTES + 1);
                try {
                    if (current.length > MAX_BYTES) throw new IOException("transfer.file-limit");
                    result = object(JSON.readTree(current));
                } finally { java.util.Arrays.fill(current, (byte) 0); }
            }
            for (String key : PREFERENCES) if (saved.has(key)) result.set(key, saved.get(key));
            if (includeCredentials) for (String key : List.of("pixiv_cookie", "pixiv_cookie_fmt"))
                if (saved.has(key)) result.set(key, saved.get(key));
            ObjectNode settings = saved.has("pixiv_batch_settings") ? storedObject(saved.get("pixiv_batch_settings")) : JSON.createObjectNode();
            settings.put("autoStartOnEnqueue", false);
            settings.remove("collectionId");
            result.put("pixiv_batch_settings", JSON.writeValueAsString(settings));
            if (saved.has("pixiv_batch_queue")) {
                ObjectNode queue = storedObject(saved.get("pixiv_batch_queue"));
                if (!queue.path("queue").isArray()) throw new IOException("transfer.file-invalid");
                var items = JSON.createArrayNode();
                for (JsonNode node : queue.path("queue")) {
                    ObjectNode item = object(node);
                    if (item.path("taskObserved").asBoolean(false)) continue;
                    item.remove(EXECUTION);
                    if (!List.of("completed", "skipped", "failed").contains(item.path("status").asText()))
                        item.put("status", "idle");
                    items.add(item);
                }
                ObjectNode paused = JSON.createObjectNode();
                paused.set("queue", items);
                paused.put("isPaused", true);
                result.put("pixiv_batch_queue", JSON.writeValueAsString(paused));
            }
            output.write(JSON.writeValueAsBytes(result));
        } finally { java.util.Arrays.fill(bytes, (byte) 0); }
    }

    private static ObjectNode storedObject(JsonNode node) throws IOException {
        return object(node.isTextual() ? JSON.readTree(node.textValue()) : node);
    }
    private static ObjectNode object(JsonNode node) throws IOException {
        if (!(node instanceof ObjectNode object)) throw new IOException("transfer.file-invalid");
        return object;
    }
}
