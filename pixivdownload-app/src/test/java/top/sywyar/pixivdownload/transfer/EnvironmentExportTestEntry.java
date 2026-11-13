package top.sywyar.pixivdownload.transfer;

import org.springframework.context.support.GenericApplicationContext;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 子进程验证导出工作者跨越真实 context 关闭边界，不请求真实应用重启。 */
public final class EnvironmentExportTestEntry {
    public static void main(String[] args) throws Exception {
        top.sywyar.pixivdownload.common.Utf8ConsoleStreams.install();
        Path root = Path.of(args[0]);
        Path config = Files.createDirectories(root.resolve("config")).resolve("config.yaml");
        Files.writeString(config, "server.port: 8123\n", java.nio.charset.StandardCharsets.UTF_8);
        boolean web = args.length > 1 && args[1].equals("web");
        org.springframework.context.ConfigurableApplicationContext context;
        if (web) context = new org.springframework.boot.SpringApplication(WebFixture.class).run(
                "--server.port=0", "--spring.config.import=", "--spring.main.banner-mode=off", "--spring.jmx.enabled=false");
        else {
            var generic = new GenericApplicationContext(); generic.refresh(); context = generic;
        }
        try (context) {
        String configurationBeforeExport = Files.readString(config);
        var restarted = new AtomicBoolean();
        var host = mock(DesktopUiHost.class);
        when(host.applicationVersion()).thenReturn("fixture");
        when(host.coreConfigFields()).thenReturn(List.of(new GuiConfigFieldContribution("server.port", "server", "server.port",
                GuiConfigFieldType.PORT, "8080", 0)));
        var service = new EnvironmentExportService(context, new String[0], session -> {
            assertThat(context.isActive()).isFalse();
            assertThat(session.isStarted()).isTrue();
            return new EnvironmentTransferService(host, List::of, () -> true, session, new String[0]);
        }, () -> {
            restarted.set(true);
            if (args[1].equals("restart-throws")) throw new IllegalStateException("private-restart-detail");
            return !args[1].equals("restart-false");
        });
        Path destination = root.resolve("archive.pdbackup");
        var request = new EnvironmentExportService.Request(destination.toString(), "archive-fixture".toCharArray(),
                false, false, false, false, true);
        EnvironmentExportService.View accepted;
        if (web) {
            var messages = top.sywyar.pixivdownload.i18n.TestI18nBeans.appMessages();
            var controller = new EnvironmentTransferController(mock(DesktopEnvironmentTransfer.class),
                    new top.sywyar.pixivdownload.gui.ToolApiRequests(messages, mock(top.sywyar.pixivdownload.i18n.WebI18nService.class)), messages, service);
            var handler = context.getBean("requestMappingHandlerMapping", org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
            var method = EnvironmentTransferController.class.getMethod("export", jakarta.servlet.http.HttpServletRequest.class);
            var mapping = org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/api/settings/environment/exports")
                    .methods(org.springframework.web.bind.annotation.RequestMethod.POST).options(handler.getBuilderConfiguration()).build();
            handler.registerMapping(mapping, controller, method);
            int port = ((org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext) context).getWebServer().getPort();
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            try (var client = java.net.http.HttpClient.newHttpClient()) {
                var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/settings/environment/exports"))
                        .timeout(java.time.Duration.ofSeconds(20)).header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(request))).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
                accepted = json.readValue(response.body(), EnvironmentExportService.View.class);
            }
            Arrays.fill(request.password(), '\0');
        } else accepted = service.start(request);
        assertThat(accepted.state()).isEqualTo(EnvironmentExportService.State.ACCEPTED);
        assertThat(request.password()).containsOnly('\0');
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(45).toNanos();
        while (!restarted.get() && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(restarted).isTrue();
        var result = service.current();
        if (args[1].startsWith("restart-")) {
            while (!"transfer.restart-failed".equals(result.failureCode()) && System.nanoTime() < deadline) {
                Thread.sleep(20);
                result = service.current();
            }
            assertThat(result.failureCode()).isEqualTo("transfer.restart-failed");
        }
        assertThat(result.state()).isEqualTo(EnvironmentExportService.State.SUCCEEDED);
        assertThat(result.downloadUrl()).endsWith(accepted.id() + "/archive");
        assertThat(service.archive(accepted.id())).isEqualTo(destination);
        assertThat(new TransferArchive().inspect(destination, "archive-fixture".toCharArray()).encrypted()).isTrue();
        assertThat(Files.readString(config)).isEqualTo(configurationBeforeExport);
        assertThat(Files.readString(RuntimeFiles.environmentTransferDirectory().resolve("api-export.json")))
                .doesNotContain("archive-fixture");
        Files.write(destination, new byte[]{0}, StandardOpenOption.APPEND);
        assertThatThrownBy(() -> service.archive(accepted.id())).isInstanceOf(java.io.IOException.class)
                .hasMessage("transfer.target-changed");
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(exclude = org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
    static class WebFixture {}
}
