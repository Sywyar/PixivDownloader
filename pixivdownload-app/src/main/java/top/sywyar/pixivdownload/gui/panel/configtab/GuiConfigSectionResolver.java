package top.sywyar.pixivdownload.gui.panel.configtab;

import top.sywyar.pixivdownload.gui.config.GuiConfigSectionSpec;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigSectionLayout;
import top.sywyar.pixivdownload.gui.config.ConfigFieldRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        Map<String, ConfigSection> sectionsByGroup = new LinkedHashMap<>();

        for (GuiConfigSectionSpec spec : transitionSpecs) {
            ConfigSection section = transitionAdapter(spec, ctx, configPath, webUrlProvider);
            if (section != null) {
                sectionsByGroup.put(spec.group(), section);
            }
        }

        safePluginSections.stream()
                .filter(spec -> spec != null)
                .filter(spec -> visibleGroups == null || visibleGroups.contains(spec.group()))
                .filter(spec -> !sectionsByGroup.containsKey(spec.group()))
                .collect(java.util.stream.Collectors.groupingBy(
                        GuiConfigSectionSpec::group,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()))
                .forEach((group, specs) -> sectionsByGroup.put(group,
                        new DeclaredGuiConfigSection(ctx, group, sortSpecs(specs))));

        return List.copyOf(sectionsByGroup.values());
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

    private static ConfigSection transitionAdapter(GuiConfigSectionSpec spec,
                                                   ConfigSectionContext ctx,
                                                   Path configPath,
                                                   Function<String, String> webUrlProvider) {
        return switch (spec.sectionId()) {
            case AI_TRANSITION_SECTION -> new AiConfigSection(ctx);
            case NOTIFICATION_TRANSITION_SECTION -> new NotificationConfigSection(ctx);
            case PLUGIN_MARKET_SECTION -> new PluginMarketConfigSection(ctx, configPath, webUrlProvider);
            default -> null;
        };
    }

    private static List<GuiConfigSectionSpec> sortSpecs(List<GuiConfigSectionSpec> specs) {
        return specs.stream()
                .sorted(Comparator
                        .comparingInt(GuiConfigSectionSpec::order)
                        .thenComparing(GuiConfigSectionSpec::pluginId)
                        .thenComparing(GuiConfigSectionSpec::sectionId))
                .toList();
    }

    private static boolean contains(List<String> values, String value) {
        return values != null && values.contains(value);
    }
}
