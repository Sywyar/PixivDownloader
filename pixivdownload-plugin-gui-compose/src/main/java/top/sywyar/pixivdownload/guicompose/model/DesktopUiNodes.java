package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopControlCenterAvailability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiIcon;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiTone;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.Alignment;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.ButtonStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.ContainerLayout;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.InputKind;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.NumberStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.ToggleStyle;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static top.sywyar.pixivdownload.guicompose.model.GuiActionResponseSafety.sanitizeActionText;

/**
 * 桌面文档节点、文本令牌与受控展示值的共享构造函数。
 */
final class DesktopUiNodes {
    private DesktopUiNodes() {
    }

    static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,120}");
    }

    static DesktopUiNode.Container column(String id, DesktopUiNode... children) {
        return column(id, List.of(children));
    }

    static String formatTimestamp(Instant value) {
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).withZone(
                ZoneId.systemDefault()).format(value);
    }

    static String formatTimestamp(String value) {
        try {
            return value == null || value.isBlank() ? "—" : formatTimestamp(Instant.parse(value));
        } catch (RuntimeException ignored) {
            return "—";
        }
    }

    static DesktopUiNode.Container column(
            String id,
            List<? extends DesktopUiNode> children
    ) {
        return new DesktopUiNode.Container(
                id,
                ContainerLayout.COLUMN,
                1,
                10,
                Alignment.STRETCH,
                List.copyOf(children)
        );
    }

    static DesktopUiNode.Container row(String id, DesktopUiNode... children) {
        return row(id, List.of(children));
    }

    static DesktopUiNode.Container row(
            String id,
            List<? extends DesktopUiNode> children
    ) {
        return new DesktopUiNode.Container(
                id,
                ContainerLayout.FLOW,
                1,
                8,
                Alignment.START,
                List.copyOf(children)
        );
    }

    static DesktopUiNode.Container endRow(String id, DesktopUiNode... children) {
        return new DesktopUiNode.Container(
                id,
                ContainerLayout.FLOW,
                1,
                8,
                Alignment.END,
                List.of(children)
        );
    }

    static DesktopUiNode.Scroll scroll(String id, DesktopUiNode child) {
        return new DesktopUiNode.Scroll(id, child);
    }

    static DesktopUiNode.Group group(
            String id,
            String titleKey,
            DesktopUiNode child
    ) {
        return new DesktopUiNode.Group(id, key(titleKey), child);
    }

    static DesktopUiNode.Text text(String id, String messageKey, TextStyle style) {
        return new DesktopUiNode.Text(
                id,
                key(messageKey),
                style,
                true,
                style == TextStyle.CODE
        );
    }

    static DesktopUiNode.Text secondary(String id, String messageKey) {
        return text(id, messageKey, TextStyle.SECONDARY);
    }

    static DesktopUiNode.Text bullet(String id, String messageKey) {
        return text(id, messageKey, TextStyle.BULLET);
    }

    static DesktopUiNode.Text raw(String id, String value, TextStyle style) {
        return new DesktopUiNode.Text(
                id,
                TextToken.raw(value == null || value.isBlank() ? "--" : value),
                style,
                true,
                style == TextStyle.CODE
        );
    }

    static DesktopUiNode.Text status(String id, String value) {
        return raw(id, value, TextStyle.CAPTION);
    }

    static DesktopUiNode.TextInput input(
            String id,
            String binding,
            String label,
            String help,
            InputKind kind,
            String value,
            boolean enabled
    ) {
        return new DesktopUiNode.TextInput(
                id,
                binding,
                key(label),
                help == null ? null : key(help),
                kind,
                kind == InputKind.PASSWORD ? "" : nullToEmpty(value),
                32,
                kind == InputKind.MULTILINE ? 5 : 1,
                enabled
        );
    }

    static DesktopUiNode.Toggle toggle(
            String id,
            String binding,
            String label,
            boolean selected,
            boolean enabled
    ) {
        return new DesktopUiNode.Toggle(
                id,
                binding,
                key(label),
                null,
                ToggleStyle.CHECKBOX,
                selected,
                enabled
        );
    }

    static DesktopUiNode.NumberInput number(
            String id,
            String binding,
            String label,
            String help,
            int value,
            int minimum,
            int maximum,
            boolean enabled
    ) {
        return new DesktopUiNode.NumberInput(
                id,
                binding,
                key(label),
                help == null ? null : key(help),
                NumberStyle.SPINNER,
                Math.max(minimum, Math.min(maximum, value)),
                minimum,
                maximum,
                1,
                enabled
        );
    }

    static DesktopUiNode.Button button(
            String id,
            String actionId,
            String label,
            boolean enabled,
            Map<String, Runnable> nextActions,
            Runnable action
    ) {
        nextActions.put(actionId, action);
        return new DesktopUiNode.Button(
                id,
                actionId,
                key(label),
                null,
                ButtonStyle.NORMAL,
                enabled
        );
    }

    static DesktopUiNode.Button rawButton(
            String id,
            String actionId,
            String label,
            boolean enabled,
            Map<String, Runnable> nextActions,
            Runnable action
    ) {
        nextActions.put(actionId, action);
        return new DesktopUiNode.Button(
                id,
                actionId,
                TextToken.raw(label),
                null,
                ButtonStyle.NORMAL,
                enabled
        );
    }

    static TextToken key(String key) {
        return TextToken.key(key);
    }

    static TextToken token(String namespace, String key, String fallback) {
        if (key == null || key.isBlank()) return TextToken.raw(fallback == null ? "" : fallback);
        if (!validId(key) || (namespace != null && !validId(namespace))) {
            return TextToken.raw(fallback == null ? key : fallback);
        }
        return new TextToken(
                namespace,
                key,
                fallback == null ? key : fallback,
                List.of()
        );
    }

    static TextToken token(
            String namespace,
            String key,
            String fallback,
            List<String> arguments
    ) {
        if (key == null || key.isBlank()) return TextToken.raw(fallback == null ? "" : fallback);
        if (!validId(key) || (namespace != null && !validId(namespace))) {
            return TextToken.raw(fallback == null ? key : fallback);
        }
        return new TextToken(
                namespace,
                key,
                fallback == null ? key : fallback,
                arguments
        );
    }

    static TextToken appToken(String key, Object... arguments) {
        return new TextToken(
                null,
                key,
                key,
                Arrays.stream(arguments).map(String::valueOf).toList()
        );
    }

    static TextToken guiToken(DesktopUiHost.GuiValue value) {
        if (value == null || !value.isObject()) return TextToken.raw("--");
        List<String> arguments = new ArrayList<>();
        for (DesktopUiHost.GuiValue argument : value.path("arguments")) {
            if (argument != null && argument.isValueNode() && arguments.size() < 8) {
                arguments.add(sanitizeActionText(argument.asText("")));
            }
        }
        return token(
                nullableText(value, "namespace"),
                value.path("key").asText(""),
                sanitizeActionText(value.path("fallback").asText("")),
                arguments
        );
    }

    private static String nullableText(DesktopUiHost.GuiValue value, String field) {
        DesktopUiHost.GuiValue child = value == null ? null : value.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    static List<DesktopUiHost.GuiValue> values(DesktopUiHost.GuiValue value) {
        if (value == null || !value.isArray()) return List.of();
        List<DesktopUiHost.GuiValue> values = new ArrayList<>();
        for (DesktopUiHost.GuiValue item : value) values.add(item);
        return List.copyOf(values);
    }

    static DesktopUiIcon icon(String value) {
        try {
            return DesktopUiIcon.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return DesktopUiIcon.INFO;
        }
    }

    static DesktopUiTone tone(String value) {
        try {
            return DesktopUiTone.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return DesktopUiTone.DEFAULT;
        }
    }

    static DesktopControlCenterAvailability availability(String value) {
        try {
            return DesktopControlCenterAvailability.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return DesktopControlCenterAvailability.UNAVAILABLE;
        }
    }

    static String safeId(String value) {
        String safe = nullToEmpty(value).trim().replaceAll("[^A-Za-z0-9._:-]", "-");
        if (safe.isBlank() || !Character.isLetterOrDigit(safe.charAt(0))) safe = "id-" + safe;
        return safe.length() <= 120 ? safe : safe.substring(
                0,
                104
        ) + "-" + Integer.toHexString(
                value.hashCode());
    }

    static boolean safeHref(String href) {
        return href != null && href.startsWith("/") && !href.startsWith("//") && !href.contains("..") && href.indexOf(
                '\r') < 0 && href.indexOf('\n') < 0;
    }

    static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static double parseDouble(String value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static String safeMessage(Throwable failure) {
        if (failure == null) return "unknown";
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    static String formatSize(long bytes) {
        if (bytes <= 0L) return "--";
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB"};
        int unit = 0;
        while (value >= 1024d && unit < units.length - 1) {
            value /= 1024d;
            unit++;
        }
        return unit == 0 ? Long.toString(bytes) + " " + units[unit] : String.format(
                Locale.ROOT,
                "%.1f %s",
                value,
                units[unit]
        );
    }

    static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
