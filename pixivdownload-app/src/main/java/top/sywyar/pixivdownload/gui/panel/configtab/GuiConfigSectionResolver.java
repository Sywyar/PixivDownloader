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

        Map<String, List<GuiConfigSectionSpec>> declaredSpecsByGroup = new LinkedHashMap<>();
        safePluginSections.stream()
                .filter(spec -> spec != null)
                .filter(spec -> visibleGroups == null || visibleGroups.contains(spec.group()))
                .filter(spec -> !exclusiveGroups.contains(spec.group()))
                .forEach(spec -> declaredSpecsByGroup
                        .computeIfAbsent(spec.group(), ignored -> new ArrayList<>())
                        .add(spec));

        declaredSpecsByGroup.forEach((group, specs) -> blocksByGroup
                .computeIfAbsent(group, ignored -> new ArrayList<>())
                .add(declaredBlock(group, specs)));

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
        if (contains(visibleGroups, pluginsGroup)) {
            specs.add(transitionSpec(PLUGIN_MARKET_SECTION, GuiConfigGroups.PLUGINS, pluginsGroup, 300));
        }
        return specs;
    }

    private static GuiConfigSectionSpec transitionSpec(String sectionId, String groupId,
                                                       String group, int groupOrder) {
        return new GuiConfigSectionSpec(TRANSITION_OWNER, sectionId, groupId, group, groupOrder,
                "", "", "", "", "", "", List.of(), GuiConfigSectionLayout.FIELD_LIST, 0,
                List.of(), List.of(), List.of(), false, true);
    }

    private static ConfigSectionBlock transitionBlock(GuiConfigSectionSpec spec,
                                                      Path configPath,
                                                      Function<String, String> webUrlProvider) {
        Function<ConfigSectionContext, ConfigSection> factory = switch (spec.sectionId()) {
            case PLUGIN_MARKET_SECTION -> sectionContext ->
                    new PluginMarketConfigSection(sectionContext, configPath, webUrlProvider);
            default -> null;
        };
        if (factory == null) {
            return null;
        }
        return new FactoryConfigSectionBlock(spec.pluginId(), spec.sectionId(), spec.group(), spec.order(), factory);
    }

    private static ConfigSectionBlock declaredBlock(String group, List<GuiConfigSectionSpec> specs) {
        List<GuiConfigSectionSpec> sorted = sortSpecs(specs);
        GuiConfigSectionSpec first = sorted.get(0);
        int order = sorted.stream()
                .mapToInt(GuiConfigSectionSpec::order)
                .min()
                .orElse(first.order());
        return new FactoryConfigSectionBlock(first.pluginId(), first.sectionId(), group, order,
                sectionContext -> new DeclaredGuiConfigSection(sectionContext, group, sorted));
    }

    private static List<ConfigSectionBlock> sortBlocks(List<ConfigSectionBlock> blocks) {
        return blocks.stream()
                .sorted(Comparator
                        .comparingInt(ConfigSectionBlock::order)
                        .thenComparing(ConfigSectionBlock::pluginId, Comparator.nullsLast(String::compareTo))
                        .thenComparing(ConfigSectionBlock::sectionId, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private static List<GuiConfigSectionSpec> sortSpecs(List<GuiConfigSectionSpec> specs) {
        return specs.stream()
                .sorted(Comparator
                        .comparingInt(GuiConfigSectionSpec::order)
                        .thenComparing(GuiConfigSectionSpec::pluginId, Comparator.nullsLast(String::compareTo))
                        .thenComparing(GuiConfigSectionSpec::sectionId, Comparator.nullsLast(String::compareTo)))
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
