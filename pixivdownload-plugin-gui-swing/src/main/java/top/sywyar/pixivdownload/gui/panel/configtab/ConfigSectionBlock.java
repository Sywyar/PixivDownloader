package top.sywyar.pixivdownload.gui.panel.configtab;

import java.util.Set;

/**
 * A renderable block inside a GUI configuration group.
 */
public interface ConfigSectionBlock {

    String group();

    String pluginId();

    String sectionId();

    int order();

    ConfigSection createSection(ConfigSectionContext ctx);

    /**
     * Field keys consumed by this block in addition to fields registered while building it.
     * Existing transition adapters still register their legacy fields through the context;
     * blocks that claim fields without rendering them can use this hook.
     */
    default Set<String> consumedFieldKeys(ConfigSectionContext ctx) {
        return Set.of();
    }
}
