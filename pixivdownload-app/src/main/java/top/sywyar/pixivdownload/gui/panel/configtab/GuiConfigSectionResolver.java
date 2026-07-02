package top.sywyar.pixivdownload.gui.panel.configtab;

import top.sywyar.pixivdownload.gui.config.GuiConfigSectionSpec;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.gui.config.ConfigFieldRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Resolves rich GUI config section metadata into host-side {@link ConfigSection} instances.
 */
public final class GuiConfigSectionResolver {

    static final String TRANSITION_OWNER = "app-transition";
    static final String AI_TRANSITION_SECTION = "transition.ai-config";
    static final String NOTIFICATION_TRANSITION_SECTION = "transition.notification-config";
    static final String PLUGIN_MARKET_SECTION = "core.plugin-market-config";

    private GuiConfigSectionResolver() {
    }

    public static List<ConfigSection> createSections(ConfigSectionContext ctx,
                                                     List<String> visibleGroups,
                                                     List<GuiConfigSectionSpec> pluginSections,
                                                     Path configPath,
                                                     Function<String, String> webUrlProvider) {
        List<GuiConfigSectionSpec> safePluginSections = pluginSections == null ? List.of() : pluginSections;
        List<GuiConfigSectionSpec> transitionSpecs = transitionAdapterSpecs(visibleGroups);
        Map<String, List<ConfigSectionBlock>> blocksByGroup = new LinkedHashMap<>();
        Set<String> exclusiveGroups = new LinkedHashSet<>();

        for (GuiConfigSectionSpec spec : transitionSpecs) {
            ConfigSectionBlock block = transitionBlock(spec, configPath, webUrlProvider);
            if (block != null) {
                blocksByGroup.computeIfAbsent(spec.group(), ignored -> new ArrayList<>()).add(block);
                if (PLUGIN_MARKET_SECTION.equals(spec.sectionId())) {
                    exclusiveGroups.add(spec.group());
                }
            }
        }

        safePluginSections.stream()
                .filter(spec -> spec != null)
                .filter(spec -> visibleGroups == null || visibleGroups.contains(spec.group()))
                .filter(spec -> !exclusiveGroups.contains(spec.group()))
                .forEach(spec -> blocksByGroup
                        .computeIfAbsent(spec.group(), ignored -> new ArrayList<>())
                        .add(declaredBlock(spec)));

        List<ConfigSection> sections = new ArrayList<>();
        for (Map.Entry<String, List<ConfigSectionBlock>> entry : blocksByGroup.entrySet()) {
            List<ConfigSectionBlock> blocks = sortBlocks(entry.getValue());
            if (blocks.size() == 1) {
                sections.add(blocks.get(0).createSection(ctx));
            } else {
                sections.add(new CompositeConfigSection(ctx, entry.getKey(), blocks));
            }
        }
        return List.copyOf(sections);
    }

    private static List<GuiConfigSectionSpec> transitionAdapterSpecs(List<String> visibleGroups) {
        List<GuiConfigSectionSpec> specs = new ArrayList<>();
        String pluginsGroup = ConfigFieldRegistry.groupPlugins();
        String aiGroup = ConfigFieldRegistry.groupAi();
        String notificationGroup = ConfigFieldRegistry.groupNotification();
        if (contains(visibleGroups, aiGroup)) {
            specs.add(transitionSpec(AI_TRANSITION_SECTION, GuiConfigGroups.AI, aiGroup, 1200));
        }
        if (contains(visibleGroups, notificationGroup)) {
            specs.add(transitionSpec(NOTIFICATION_TRANSITION_SECTION,
                    GuiConfigGroups.NOTIFICATION, notificationGroup, 1300));
        }
        if (contains(visibleGroups, pluginsGroup)) {
            specs.add(transitionSpec(PLUGIN_MARKET_SECTION, GuiConfigGroups.PLUGINS, pluginsGroup, 300));
        }
        return specs;
    }

    private static GuiConfigSectionSpec transitionSpec(String sectionId, String groupId,
                                                       String group, int groupOrder) {
        return new GuiConfigSectionSpec(TRANSITION_OWNER, sectionId, groupId, group, groupOrder,
                "", "", GuiConfigSectionLayout.FIELD_LIST, 0, List.of(), List.of(), List.of());
    }

    private static ConfigSectionBlock transitionBlock(GuiConfigSectionSpec spec,
                                                      Path configPath,
                                                      Function<String, String> webUrlProvider) {
        Function<ConfigSectionContext, ConfigSection> factory = switch (spec.sectionId()) {
            case AI_TRANSITION_SECTION -> AiConfigSection::new;
            case NOTIFICATION_TRANSITION_SECTION -> NotificationConfigSection::new;
            case PLUGIN_MARKET_SECTION -> sectionContext ->
                    new PluginMarketConfigSection(sectionContext, configPath, webUrlProvider);
            default -> null;
        };
        if (factory == null) {
            return null;
        }
        return new FactoryConfigSectionBlock(spec.pluginId(), spec.sectionId(), spec.group(), spec.order(), factory);
    }

    private static ConfigSectionBlock declaredBlock(GuiConfigSectionSpec spec) {
        return new FactoryConfigSectionBlock(spec.pluginId(), spec.sectionId(), spec.group(), spec.order(),
                sectionContext -> new DeclaredGuiConfigSection(sectionContext, spec.group(), List.of(spec)));
    }

    private static List<ConfigSectionBlock> sortBlocks(List<ConfigSectionBlock> blocks) {
        return blocks.stream()
                .sorted(Comparator
                        .comparingInt(ConfigSectionBlock::order)
                        .thenComparing(ConfigSectionBlock::pluginId, Comparator.nullsLast(String::compareTo))
                        .thenComparing(ConfigSectionBlock::sectionId, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private static boolean contains(List<String> values, String value) {
        return values != null && values.contains(value);
    }

    private record FactoryConfigSectionBlock(
            String pluginId,
            String sectionId,
            String group,
            int order,
            Function<ConfigSectionContext, ConfigSection> factory
    ) implements ConfigSectionBlock {

        @Override
        public ConfigSection createSection(ConfigSectionContext ctx) {
            return factory.apply(ctx);
        }
    }
}
