package example.pages.plugin;

import example.pages.contract.Checklist;
import pixivdownload.generated.remote.example.pages.contract.ChecklistRemoteAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.pf4j.*;
import org.springframework.context.annotation.*;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.api.web.*;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;

/** 独立测试插件；所有清单语义只在本插件和它的公开业务契约中。 */
public final class ChecklistPlugin extends Plugin implements PixivPluginProvider {
    public static final AtomicInteger calls = new AtomicInteger();
    public static final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    private static final ServiceKey<Checklist> KEY = new ServiceKey<>("page-checklist-contract", Checklist.class);
    public ChecklistPlugin(PluginWrapper wrapper) { super(wrapper); }
    public List<Class<?>> configurationClasses() { return List.of(Configuration.class); }
    public PixivFeaturePlugin featurePlugin() {
        return new PixivFeaturePlugin() {
            public String id() { return "page-checklist"; }
            public String displayName() { return "plugin.name"; }
            public String description() { return "plugin.summary"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
            public List<ServiceKey<?>> serviceImports() { return List.of(KEY); }
            public List<ServiceExport<?>> serviceExports() { return List.of(new ServiceExport<>(KEY, "checklist")); }
            public List<WebRouteContribution> routes() {
                var routes = new ArrayList<>(ChecklistRemoteAdapter.routes());
                routes.add(WebRouteContribution.publicRoute("/page-checklist/render/**"));
                routes.add(WebRouteContribution.admin("/page-checklist/manage/**")); return routes;
            }
            public List<StaticResourceContribution> staticResources() {
                return List.of(new StaticResourceContribution("classpath:/web/", "/page-checklist/"));
            }
            public List<WebUiSlotContribution> uiSlots() {
                return List.of(new WebUiSlotContribution("page-checklist.ordinary", "acceptance.slot",
                    "/page-checklist/render/slot-fixture.js", 0));
            }
            public List<I18nContribution> i18n() {
                return List.of(new I18nContribution("page-checklist", "i18n.web.page-checklist"));
            }
        };
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    public static class Configuration {
        @Bean Checklist checklist() {
            return config -> {
                calls.incrementAndGet();
                if (config.title().equals("__fail")) throw new IllegalArgumentException("example failure");
                if (config.title().equals("__block")) {
                    entered.countDown();
                    boolean done = false;
                    while (!done) try { release.await(); done = true; } catch (InterruptedException ignored) { }
                }
                return new Checklist.Result((int) config.items().stream().filter(Checklist.Item::checked).count(), config.items().size());
            };
        }
        @Bean ChecklistRemoteAdapter remote(RemoteOperations operations) { return new ChecklistRemoteAdapter(operations); }
        @Bean PageContribution pages() {
            return new PageContribution() {
                public List<PageComponent> components() {
                    return List.of(new PageComponent("page-checklist:list", 1, "component", "page-checklist",
                            "/page-checklist/render/component.mjs", "/page-checklist/manage/properties.mjs",
                            schema(), "{\"title\":\"Checklist\",\"items\":[{\"label\":\"First item\",\"checked\":false}]}",
                            true, List.of(new PagePort("selection", "example.pages.checklist-result/v1", resultSchema())),
                            List.of(new PagePort("result", "example.pages.checklist-result/v1", resultSchema()))));
                }
                public void validate(String type, String json) {
                    try {
                        var config = new ObjectMapper().readValue(json, Checklist.Config.class);
                        if (config.items().stream().map(Checklist.Item::label).distinct().count() != config.items().size())
                            throw new IllegalArgumentException("duplicate labels");
                    } catch (Exception failure) { throw new IllegalArgumentException("PAGE_CONFIGURATION_INVALID"); }
                }
            };
        }
        private static String schema() {
            return schema("Config");
        }
        private static String resultSchema() {
            return schema("Result");
        }
        private static String schema(String type) {
            try (var input = Checklist.class.getResourceAsStream("/META-INF/pixivdownload/schema/example.pages.contract.Checklist." + type + ".schema.json")) {
                if (input == null) throw new IllegalStateException("missing generated schema");
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        }
    }
}
