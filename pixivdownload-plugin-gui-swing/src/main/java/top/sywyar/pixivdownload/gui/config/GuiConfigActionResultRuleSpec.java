package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultArgument;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigActionResultCondition;

import java.util.List;

/**
 * Resolved action-result notice rule ready for Swing rendering.
 */
public record GuiConfigActionResultRuleSpec(
        String notice,
        int order,
        List<GuiConfigActionResultCondition> conditions,
        List<GuiConfigActionResultArgument> arguments
) {

    public GuiConfigActionResultRuleSpec {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }
}
