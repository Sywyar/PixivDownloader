package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.List;

/**
 * Declarative notice rule for a GUI config action response.
 *
 * @param noticeKey i18n key of the notice shown when all conditions match
 * @param i18nNamespace optional i18n namespace; blank means the plugin display namespace
 * @param order rule order; first matching rule wins
 * @param conditions conditions that must all match
 * @param arguments notice arguments read from the response
 */
public record GuiConfigActionResultRule(
        String noticeKey,
        String i18nNamespace,
        int order,
        List<GuiConfigActionResultCondition> conditions,
        List<GuiConfigActionResultArgument> arguments
) {

    public GuiConfigActionResultRule {
        i18nNamespace = blankToNull(i18nNamespace);
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    public GuiConfigActionResultRule(String noticeKey, int order,
                                     List<GuiConfigActionResultCondition> conditions,
                                     List<GuiConfigActionResultArgument> arguments) {
        this(noticeKey, null, order, conditions, arguments);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
