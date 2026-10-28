package top.sywyar.pixivdownload.schedule.dto;

import java.util.List;

/** 当前进程已激活计划来源的纯数据清单。 */
public record ScheduleSourceManifestView(
        String epoch,
        long revision,
        List<Source> sources
) {
    public ScheduleSourceManifestView {
        sources = List.copyOf(sources);
    }

    public record Source(
            String sourceType,
            List<String> legacyAliases,
            String ownerPluginId,
            String packageId,
            long pluginGeneration,
            long publicationId,
            String activationToken,
            String definitionSchema,
            int definitionVersion,
            Presentation presentation,
            List<String> acquisitionModes,
            List<String> possibleWorkTypes,
            Frontend frontend
    ) {
        public Source {
            legacyAliases = List.copyOf(legacyAliases);
            acquisitionModes = List.copyOf(acquisitionModes);
            possibleWorkTypes = List.copyOf(possibleWorkTypes);
        }
    }

    public record Presentation(
            String displayNamespace,
            String displayNameKey,
            String descriptionKey,
            String iconKey,
            String colorToken
    ) {
    }

    public record Frontend(
            int contractVersion,
            String moduleUrl
    ) {
    }
}
