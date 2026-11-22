package top.sywyar.pixivdownload.download.schedule.source.descriptor;

import top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceFrontendContribution;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.download.schedule.persistence.PixivSchedulePersistenceCodec;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pixiv 来源的纯数据描述符事实源。 */
public final class PixivScheduledSourceDescriptors {

    public static final String OVERUSE_GUARD_ID = "pixiv-overuse";
    public static final String FRONTEND_MODULE_URL =
            "/pixiv-batch/pixiv-schedule-sources.js";

    private static final ScheduledSourceFrontendContribution FRONTEND =
            new ScheduledSourceFrontendContribution(
                    ScheduledSourceFrontendContribution.CURRENT_CONTRACT_VERSION,
                    FRONTEND_MODULE_URL);

    private static final Set<String> ILLUST_ONLY = Set.of(
            PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST);
    private static final Set<String> ILLUST_OR_NOVEL = Set.of(
            PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST,
            PixivSchedulePersistenceCodec.WORK_TYPE_NOVEL);

    private static final Map<String, String> INPUT_SCHEMAS = loadInputSchemas();
    private static final List<ScheduledSourceDescriptor> DESCRIPTORS = List.of(
            descriptor(
                    "user-new",
                    "schedule.type.user-new",
                    Set.of(DownloadAcquisitionMode.USER_PROFILE.code(), DownloadAcquisitionMode.QUICK.code()),
                    ILLUST_OR_NOVEL),
            descriptor(
                    "user-request",
                    "schedule.type.user-request",
                    Set.of(DownloadAcquisitionMode.USER_PROFILE.code(), DownloadAcquisitionMode.QUICK.code()),
                    ILLUST_ONLY),
            descriptor(
                    "search",
                    "schedule.type.search",
                    Set.of(DownloadAcquisitionMode.SEARCH.code()),
                    ILLUST_OR_NOVEL),
            descriptor(
                    "series",
                    "schedule.type.series",
                    Set.of(DownloadAcquisitionMode.SERIES_COLLECTION.code()),
                    ILLUST_OR_NOVEL),
            descriptor(
                    "my-bookmarks",
                    "schedule.type.my-bookmarks",
                    Set.of(DownloadAcquisitionMode.QUICK.code()),
                    ILLUST_OR_NOVEL),
            descriptor(
                    "follow-latest",
                    "schedule.type.follow-latest",
                    Set.of(DownloadAcquisitionMode.QUICK.code()),
                    ILLUST_ONLY),
            descriptor(
                    "collection",
                    "schedule.type.collection",
                    Set.of(DownloadAcquisitionMode.QUICK.code()),
                    ILLUST_OR_NOVEL),
            new ScheduledSourceDescriptor("explicit-list", Set.of(),
                    PixivSchedulePersistenceCodec.DEFINITION_SCHEMA, PixivSchedulePersistenceCodec.DEFINITION_VERSION,
                    new ScheduledSourcePresentation("batch", "card.import", "card.import", "schedule", "pixiv"),
                    Set.of(DownloadAcquisitionMode.SINGLE_IMPORT.code()), ILLUST_OR_NOVEL,
                    Set.of(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID), Set.of(OVERUSE_GUARD_ID), null,
                    INPUT_SCHEMAS.get("explicit-list")));

    private PixivScheduledSourceDescriptors() {
    }

    /** 返回固定顺序、不可变的来源描述符。 */
    public static List<ScheduledSourceDescriptor> createAll() {
        return DESCRIPTORS;
    }

    private static ScheduledSourceDescriptor descriptor(
            String sourceType,
            String displayKey,
            Set<String> acquisitionModes,
            Set<String> possibleWorkTypes) {
        return new ScheduledSourceDescriptor(
                sourceType,
                legacyAliases(sourceType),
                PixivSchedulePersistenceCodec.DEFINITION_SCHEMA,
                PixivSchedulePersistenceCodec.DEFINITION_VERSION,
                new ScheduledSourcePresentation(
                        "batch", displayKey, displayKey, "schedule", "pixiv"),
                acquisitionModes,
                possibleWorkTypes,
                Set.of(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID),
                Set.of(OVERUSE_GUARD_ID),
                FRONTEND,
                INPUT_SCHEMAS.get(sourceType));
    }

    private static Map<String, String> loadInputSchemas() {
        String resource = "/pixivdownload/download-workbench/schedule/definition-v1.json";
        try (var input = PixivScheduledSourceDescriptors.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing schedule schema: " + resource);
            var catalog = new JsonMapper().readTree(input);
            var schemas = new HashMap<String, String>();
            for (var entry : catalog.get("sources").properties()) {
                ObjectNode schema = ((ObjectNode) catalog.get("common")).deepCopy();
                var source = entry.getValue();
                ObjectNode properties = (ObjectNode) schema.get("properties");
                properties.set("kind", source.get("kind"));
                var definition = (ObjectNode) source.get("source").deepCopy();
                var context = (ObjectNode) ((ObjectNode) schema.get("$defs")).remove("context");
                if ("#/$defs/context".equals(definition.path("$ref").asString(""))) {
                    definition.remove("$ref");
                    addContextFields((ObjectNode) definition.get("properties"), (ObjectNode) context.remove("properties"));
                    addContextFields(definition, context);
                }
                properties.set("source", definition);
                schema.set("required", source.get("required"));
                schema.set("examples", source.get("examples"));
                schemas.put(entry.getKey(), schema.toString());
            }
            return Map.copyOf(schemas);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read schedule schema: " + resource, failure);
        }
    }

    private static void addContextFields(ObjectNode target, ObjectNode shared) {
        for (var field : shared.properties()) {
            if (target.has(field.getKey())) throw new IllegalStateException("Conflicting source schema field: " + field.getKey());
            target.set(field.getKey(), field.getValue());
        }
    }

    private static Set<String> legacyAliases(String sourceType) {
        return PixivSchedulePersistenceCodec.legacySourceAliases().entrySet().stream()
                .filter(entry -> sourceType.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
