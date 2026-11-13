package acceptance;

import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import top.sywyar.pixivdownload.PixivDownloadApplication;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.config.http.LocalGuiWebServerCustomizer;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.hash.ArtworkHashService;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/** 经 PropertiesLauncher 只从发行 JAR 加载宿主，测试目录仅提供此入口。 */
public final class HostAotAcceptance {
    public static void main(String[] args) throws Exception {
        Utf8ConsoleStreams.install();
        boolean headless = Boolean.parseBoolean(args[0]);
        Path root = Path.of("").toAbsolutePath();
        for (String name : java.util.List.of("config", "state", "data", "instance", "plugins"))
            System.setProperty("pixivdownload." + name + "-dir", root.resolve(name).toString());
        System.setProperty("pixivdownload.plugin-dev.enabled", "false");
        System.setProperty("pixivdownload.headless", Boolean.toString(headless));
        Path config = root.resolve("config/config.yaml");
        Files.createDirectories(config.getParent());
        String downloadTemplate = root.resolve(DownloadConfig.DEFAULT_TEMPLATE).toString();
        new ConfigFileEditor(config).write("download.root-folder", downloadTemplate);
        try (var context = PixivDownloadApplication.start(new String[]{
                "--server.address=127.0.0.1", "--server.port=0", "--setup.browser.auto-open=false", "--update.auto-check=false",
                "--spring.config.import=optional:" + config.toUri(), "--download.root-folder=" + downloadTemplate})) {
            if (!context.isActive() || context.getBean(PluginRegistry.class).find("core").isEmpty())
                throw new AssertionError("Host initialization did not complete");
            String beanName = context.getBeanNamesForType(ArtworkHashService.class)[0];
            var definition = (AbstractBeanDefinition) context.getBeanFactory().getBeanDefinition(beanName);
            if (definition.getInstanceSupplier() == null)
                throw new AssertionError("Host component construction did not use generated code");
            if (context.getBeansOfType(LocalGuiWebServerCustomizer.class).isEmpty() != headless)
                throw new AssertionError("GUI condition did not follow the current environment");
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/plugins/status"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 503 || !response.body().contains("auth.setup-required"))
                    throw new AssertionError("Packaged HTTP setup protection changed: " + response.statusCode());
            }
        }
        System.out.println("HOST_AOT_ACCEPTANCE_OK headless=" + headless);
    }
}
