package top.sywyar.pixivdownload.gui.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 字面量调用形态的低成本 lint；locale 完整性由统一 i18n checker 负责。 */
@DisplayName("Swing GUI 文案归属启发式 lint")
class GuiMessageUsageGuardTest {
    private static final Pattern MESSAGE_CALL = Pattern.compile(
            "((?<![A-Za-z.])message|GuiMessages\\.get)\\(\\s*\"([^\"]+)\"\\s*(?:,|\\))");
    private static final Pattern GUI_MESSAGE_WRAPPER = Pattern.compile(
            "\\bString\\s+message\\([^{}]*\\)\\s*\\{\\s*return\\s+GuiMessages\\.get\\(");

    @Test
    @DisplayName("所有字面量 GUI key 都可由 Swing 解析链解析")
    void literalGuiMessageKeysExistInGuiBundle() throws IOException {
        installHost();
        GuiMessages.clearLocaleOverride();

        int calls = 0;
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String key : guiMessageKeys(Files.readString(source, StandardCharsets.UTF_8))) {
                    calls++;
                    assertThat(GuiMessages.get(key)).as(source + " 无法解析文案 key: " + key)
                            .isNotEqualTo(key);
                }
            }
        }
        assertThat(calls).isPositive();
    }

    @Test
    @DisplayName("文案 lint 区分 Swing 词典转发与宿主命名空间转发")
    void onlyGuiOwnedMessagesUseGuiBundle() {
        assertThat(guiMessageKeys("""
                private static String message(String key, Object... args) {
                    return GuiMessages.get(key, args);
                }
                message("gui.first");
                GuiMessages.get("gui.second", 2);
                """)).containsExactly("gui.first", "gui.second");
        assertThat(guiMessageKeys("""
                private String message(String key, Object... args) {
                    return context.host().message("local-import." + key, args);
                }
                message("title");
                context.host().message("local-import.help");
                GuiMessages.get("gui.direct");
                """)).containsExactly("gui.direct");
    }

    private static List<String> guiMessageKeys(String source) {
        boolean guiWrapper = GUI_MESSAGE_WRAPPER.matcher(source).find();
        List<String> keys = new ArrayList<>();
        Matcher matcher = MESSAGE_CALL.matcher(source);
        while (matcher.find()) {
            if (matcher.group(1).equals("GuiMessages.get") || guiWrapper) keys.add(matcher.group(2));
        }
        return keys;
    }

    private static void installHost() {
        DesktopUiHost.UiLocale source = new DesktopUiHost.UiLocale("zh-CN", "简体中文", "");
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> {
                    if ("resolveLocale".equals(method.getName())) {
                        return new DesktopUiHost.UiLocaleResolution(source, List.of(source));
                    }
                    throw new AssertionError("unexpected DesktopUiHost call: " + method.getName());
                });
        SwingHost.install(new DesktopUiContext(
                false, 6999, ".", Path.of("config.yaml"), host,
                List.of(), List::of, text -> text.fallback(), () -> "system"));
    }
}
