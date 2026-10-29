package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.schedule.ScheduleExecutor;
import top.sywyar.pixivdownload.schedule.ScheduleRunQueue;
import top.sywyar.pixivdownload.schedule.ScheduleCredentialService;
import top.sywyar.pixivdownload.schedule.ScheduleHostLifecycle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.i18n.LocaleCatalog;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.schedule.ScheduleConfig;
import top.sywyar.pixivdownload.schedule.ScheduleRunState;
import top.sywyar.pixivdownload.schedule.ScheduleService;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.text.MessageFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuiLauncherDesktopTextTest {
    @TempDir
    private Path tempDir;
    private final Locale originalLocale = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    @Test
    @DisplayName("无工作台时宿主计划摘要在所有正式语言中解析并随语言切换")
    void resolvesHostAutomationWithoutWorkbenchForEverySupportedLocale() {
        ScheduledTaskStore store = mock(ScheduledTaskStore.class);
        ScheduledTask interval = automationTask(1L, ScheduledTask.TRIGGER_INTERVAL);
        when(interval.intervalMinutes()).thenReturn(30);
        ScheduledTask cron = automationTask(2L, ScheduledTask.TRIGGER_CRON);
        when(cron.cronExpr()).thenReturn("0 0 * * * *");
        ScheduledTask unknown = automationTask(3L, "fixture-unknown");
        when(store.findAll()).thenReturn(List.of(interval, cron, unknown));
        ScheduleService service = new ScheduleService(
                store, mock(ScheduleExecutor.class),
                new ScheduleConfig(), new ScheduleRunState(),
                mock(ScheduleRunQueue.class),
                new ObjectMapper(),
                mock(ScheduleCredentialService.class),
                mock(TransactionTemplate.class),
                mock(ScheduleCapabilityAccess.class),
                mock(ScheduleHostLifecycle.class), () -> true);
        var tasks = service.snapshot().tasks();
        List<DesktopUiText> tokens = List.of(tasks.get(0).title(), tasks.get(0).triggerSummary(),
                tasks.get(1).triggerSummary(), tasks.get(2).triggerSummary());
        WebI18nBundleRegistry registry = new WebI18nBundleRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        assertThat(registry.supportedNamespaces()).doesNotContain("batch");
        assertThat(tokens).extracting(DesktopUiText::namespace).containsOnly("common");
        assertThat(tokens).extracting(DesktopUiText::key).containsExactly(
                "desktop.control-center.automation.task", "desktop.control-center.automation.interval",
                "desktop.control-center.automation.cron", "desktop.control-center.automation.unknown-trigger");
        for (var language : LocaleCatalog.defaultCatalog().visibleLocales()) {
            Locale locale = language.toLocale();
            Locale.setDefault(locale);
            Map<String, String> exact = registry.resolve("common").loadExact(locale);
            for (DesktopUiText token : tokens) {
                assertThat(exact).containsKey(token.key());
                assertThat(GuiLauncher.resolveDesktopText(token, () -> registry))
                        .as("%s / %s", language.tag(), token.key())
                        .isEqualTo(new MessageFormat(exact.get(token.key()), locale)
                                .format(token.arguments().toArray()));
            }
        }
        Locale.setDefault(Locale.US);
        String english = GuiLauncher.resolveDesktopText(tokens.get(1), () -> registry);
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
        assertThat(GuiLauncher.resolveDesktopText(tokens.get(1), () -> registry))
                .contains("30").isNotEqualTo(english);
    }

    private static ScheduledTask automationTask(long id, String triggerKind) {
        ScheduledTask task = mock(ScheduledTask.class);
        when(task.id()).thenReturn(id);
        when(task.enabled()).thenReturn(true);
        when(task.triggerKind()).thenReturn(triggerKind);
        return task;
    }

    @Test
    void resolvesTargetFallbackSourceAndArgumentsThroughOneChain() throws Exception {
        WebI18nBundleRegistry registry = registry(Map.of(
                "fixture.properties", "source.only=Source\n",
                "fixture_en.properties", "fallback.only=English\nformat=Hello, {0}!\n",
                "fixture_zh-Hant.properties", "target.only=繁體\nformat=你好，{0}！\n"));

        Locale.setDefault(Locale.forLanguageTag("zh-Hant"));
        assertThat(resolve(registry, "target.only", "fallback")).isEqualTo("繁體");
        assertThat(resolve(registry, "format", "fallback", "世界")).isEqualTo("你好，世界！");

        Locale.setDefault(Locale.JAPAN);
        assertThat(resolve(registry, "fallback.only", "fallback")).isEqualTo("English");
        assertThat(resolve(registry, "source.only", "fallback")).isEqualTo("Source");
    }

    @Test
    void normalizesBomAndUsesTokenFallbackForMissingKeys() throws Exception {
        WebI18nBundleRegistry registry = registry(Map.of(
                "fixture.properties", "\uFEFFbom.key=BOM value\n"));

        assertThat(resolve(registry, "bom.key", "fallback")).isEqualTo("BOM value");
        assertThat(resolve(registry, "missing.key", "Missing {0}", "value"))
                .isEqualTo("Missing value");
    }

    @Test
    void readsTheCurrentLocaleForEveryResolution() throws Exception {
        WebI18nBundleRegistry registry = registry(Map.of(
                "fixture.properties", "value=来源\n",
                "fixture_en.properties", "value=English\n",
                "fixture_zh-Hant.properties", "value=繁體\n"));

        Locale.setDefault(Locale.US);
        assertThat(resolve(registry, "value", "fallback")).isEqualTo("English");
        Locale.setDefault(Locale.forLanguageTag("zh-Hant"));
        assertThat(resolve(registry, "value", "fallback")).isEqualTo("繁體");
    }

    @Test
    void keepsLiteralNamedPlaceholderExamplesWhenNoArgumentsWereSupplied() throws Exception {
        WebI18nBundleRegistry registry = registry(Map.of(
                "fixture.properties", "literal=JSON {title} and {voice_id}\n"));

        assertThat(resolve(registry, "literal", "fallback"))
                .isEqualTo("JSON {title} and {voice_id}");
    }

    private WebI18nBundleRegistry registry(Map<String, String> resources) throws Exception {
        Path directory = tempDir.resolve("i18n/web");
        Files.createDirectories(directory);
        for (Map.Entry<String, String> resource : resources.entrySet()) {
            Files.writeString(directory.resolve(resource.getKey()), resource.getValue(), StandardCharsets.UTF_8);
        }
        WebI18nBundleRegistry registry = new WebI18nBundleRegistry(new PluginRegistry(List.of()));
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            registry.register("fixture", loader,
                    List.of(new I18nContribution("fixture", "i18n.web.fixture")));
        }
        return registry;
    }

    private static String resolve(WebI18nBundleRegistry registry, String key,
                                  String fallback, String... arguments) {
        return GuiLauncher.resolveDesktopText(new DesktopUiText(
                "fixture", key, fallback, List.of(arguments)), () -> registry);
    }
}
