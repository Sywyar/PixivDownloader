package top.sywyar.pixivdownload.core.metadata.sidecar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkSidecarMeta;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 作品 meta sidecar 的文件层读写：路径解析、原子写入、
 * 以及把落盘 JSON 解析回纯 JDK-only 的 {@link WorkSidecarMeta}（Jackson 解析只在本类发生）。
 * 生命周期排除（配额打包 / 小说导出）经 {@link top.sywyar.pixivdownload.core.metadata.sidecar.WorkSidecarFiles#isSidecarFile}
 * 统一判定文件名。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkSidecarStore {

    /** {@code source} 顶层字段的合法取值（计划任务 / 前端转发 / 历史回填）。 */
    private static final Set<String> ALLOWED_SOURCES = Set.of("forward", "schedule", "backfill");

    private final ObjectMapper objectMapper;

    /** sidecar 在作品目录下的路径。 */
    public Path sidecarPath(Path directory, long workId) {
        return directory.resolve(WorkSidecarFiles.fileName(workId));
    }

    /** 原子写出 sidecar 文档（先写临时文件再移动），覆盖既有。 */
    public void write(Path directory, long workId, ObjectNode document) throws IOException {
        Files.createDirectories(directory);
        Path target = sidecarPath(directory, workId);
        Path tmp = directory.resolve(WorkSidecarFiles.fileName(workId) + ".tmp");
        objectMapper.writeValue(tmp.toFile(), document);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 读取、校验并解析 sidecar。以下任一情况都<b>返回空并记 warn</b>（绝不上抛给插件调用方）：
     * 文件不存在（历史作品无 sidecar 属正常）/ 不可读 / JSON 解析失败 / 顶层契约非法。
     *
     * <p>顶层契约校验：{@code schemaVersion == 1}、{@code workType} 与请求一致、{@code workId} 与请求 id 一致、
     * {@code source ∈ {forward, schedule, backfill}}、{@code normalized}/{@code raw} 要么是对象要么缺失
     * （缺失按空块兼容）。任一不符即判非法、返回空——避免把损坏 / 串改 / 张冠李戴的 sidecar 当合法 meta 返回。
     */
    public Optional<WorkSidecarMeta> read(Path directory, WorkType workType, long workId) {
        if (directory == null) {
            return Optional.empty();
        }
        Path path = sidecarPath(directory, workId);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(path.toFile());
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to read sidecar {}: {}", path, e.getMessage());
            return Optional.empty();
        }
        if (!isValid(root, path, workType, workId)) {
            return Optional.empty();
        }
        try {
            return Optional.of(map(root, workType, workId));
        } catch (RuntimeException e) {
            log.warn("Failed to map sidecar {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 校验落盘 sidecar 顶层契约。任一不符记 warn 并返回 {@code false}（调用方据此返回空）。
     */
    private boolean isValid(JsonNode root, Path path, WorkType workType, long workId) {
        if (root == null || !root.isObject()) {
            return rejected(path, "not a JSON object");
        }
        if (root.path("schemaVersion").asInt(-1) != WorkMetaCurator.SCHEMA_VERSION) {
            return rejected(path, "unsupported schemaVersion=" + root.path("schemaVersion").asText("?"));
        }
        JsonNode typeNode = root.path("workType");
        if (!typeNode.isTextual() || !workType.name().equals(typeNode.asText())) {
            return rejected(path, "workType mismatch: file=" + typeNode.asText("?") + " requested=" + workType);
        }
        JsonNode idNode = root.path("workId");
        if (!idNode.isNumber() || idNode.asLong() != workId) {
            return rejected(path, "workId mismatch: file=" + idNode.asText("?") + " requested=" + workId);
        }
        JsonNode sourceNode = root.path("source");
        if (!sourceNode.isTextual() || !ALLOWED_SOURCES.contains(sourceNode.asText())) {
            return rejected(path, "illegal source=" + sourceNode.asText("?"));
        }
        if (!isObjectOrAbsent(root.path("normalized"))) {
            return rejected(path, "normalized is neither object nor absent");
        }
        if (!isObjectOrAbsent(root.path("raw"))) {
            return rejected(path, "raw is neither object nor absent");
        }
        return true;
    }

    private boolean rejected(Path path, String reason) {
        log.warn("Reject sidecar {}: {}", path, reason);
        return false;
    }

    /** 缺失（含显式 null）或对象都合法；其它类型（字符串 / 数组 / 数字…）非法。 */
    private static boolean isObjectOrAbsent(JsonNode node) {
        return node.isMissingNode() || node.isNull() || node.isObject();
    }

    private WorkSidecarMeta map(JsonNode root, WorkType workType, long workId) {
        JsonNode n = root.path("normalized");
        WorkSidecarMeta.Normalized normalized = new WorkSidecarMeta.Normalized(
                longOrNull(n, "uploadTime"),
                longOrNull(n, "createTime"),
                longOrNull(n, "reuploadTime"),
                boolOrNull(n, "isOriginal"),
                boolOrNull(n, "isUnlisted"),
                textOrNull(n, "titleTranslation"),
                textOrNull(n, "captionTranslation"),
                textOrNull(n, "illustComment"),
                mapPages(n.path("pages")));
        Map<String, Object> raw = mapRaw(root.path("raw"));
        return new WorkSidecarMeta(
                root.path("schemaVersion").asInt(0),
                workType,
                workId,
                textOrNull(root, "fetchedAt"),
                textOrNull(root, "source"),
                normalized,
                raw);
    }

    private List<WorkSidecarMeta.Page> mapPages(JsonNode pages) {
        if (!pages.isArray() || pages.isEmpty()) {
            return List.of();
        }
        List<WorkSidecarMeta.Page> out = new ArrayList<>(pages.size());
        for (JsonNode page : pages) {
            out.add(new WorkSidecarMeta.Page(
                    page.path("width").asInt(0),
                    page.path("height").asInt(0),
                    textOrNull(page, "original")));
        }
        return out;
    }

    private Map<String, Object> mapRaw(JsonNode raw) {
        if (raw == null || !raw.isObject() || raw.isEmpty()) {
            return Map.of();
        }
        return objectMapper.convertValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.asLong() : null;
    }

    private static Boolean boolOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isBoolean() ? v.asBoolean() : null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String text = v.asText("");
        return text.isEmpty() ? null : text;
    }
}
