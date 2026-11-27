package top.sywyar.pixivdownload.transfer;

import org.springframework.context.support.GenericApplicationContext;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;

import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        Path destination = root.resolve("archive.pdbackup");
        boolean lockAfterPublish = args[1].equals("context") && System.getProperty("os.name").startsWith("Windows");
        var publishedLock = new AtomicReference<FileChannel>();
        var service = new EnvironmentExportService(context, new String[0], session -> {
            assertThat(context.isActive()).isFalse();
            assertThat(session.isStarted()).isTrue();
            var transfer = new EnvironmentTransferService(host, List::of, () -> true, session, new String[0]);
            if (!lockAfterPublish) return transfer;
            var delegate = org.mockito.AdditionalAnswers.delegatesTo(transfer);
            return mock(EnvironmentTransferService.class, invocation -> {
                Object result = delegate.answer(invocation);
                if (invocation.getMethod().getName().equals("exportStopped"))
                    publishedLock.set(FileChannel.open(destination, StandardOpenOption.READ,
                            com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ));
                return result;
            });
        }, () -> {
            restarted.set(true);
            if (args[1].equals("restart-throws")) throw new IllegalStateException("private-restart-detail");
            return !args[1].equals("restart-false");
        });
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
        var messages = top.sywyar.pixivdownload.i18n.TestI18nBeans.appMessages();
        var controller = new EnvironmentTransferController(mock(DesktopEnvironmentTransfer.class),
                new top.sywyar.pixivdownload.gui.ToolApiRequests(messages, mock(top.sywyar.pixivdownload.i18n.WebI18nService.class)), messages, service);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        var download = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(result.downloadUrl());
        if (lockAfterPublish) {
            try (var busy = publishedLock.get()) {
                assertThat(busy).isNotNull();
                assertThat(busy.isOpen()).isTrue();
                var unavailable = mvc.perform(download).andReturn().getResponse();
                assertThat(unavailable.getStatus()).isEqualTo(500);
                assertThat(tools.jackson.databind.json.JsonMapper.builder().build().readTree(unavailable.getContentAsByteArray())
                        .required("code").asString()).isEqualTo("transfer.failed");
            }
        }
        var response = mvc.perform(download).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/octet-stream");
        assertThat(response.getContentAsByteArray()).containsExactly(Files.readAllBytes(destination));
        assertThat(result.bytes()).isEqualTo(response.getContentAsByteArray().length);
        var digest = top.sywyar.pixivdownload.plugin.api.database.migration.MigrationValues.newDigest();
        assertThat(HexFormat.of().formatHex(digest.digest(response.getContentAsByteArray()))).isEqualTo(result.sha256());
        assertThat(new TransferArchive().inspect(destination, "archive-fixture".toCharArray()).encrypted()).isTrue();
        assertThat(Files.readString(config)).isEqualTo(configurationBeforeExport);
        assertThat(Files.readString(RuntimeFiles.environmentTransferDirectory().resolve("api-export.json")))
                .doesNotContain("archive-fixture");
        try (var verified = service.archive(accepted.id())) {
            assertThat(verified.read()).isEqualTo(Byte.toUnsignedInt(response.getContentAsByteArray()[0]));
            assertThat(mvc.perform(download).andReturn().getResponse().getContentAsByteArray())
                    .containsExactly(response.getContentAsByteArray());
            try (var overlapping = service.archive(accepted.id());
                 var entries = Files.list(RuntimeFiles.environmentTransferDirectory())) {
                assertThat(entries.filter(p -> p.getFileName().toString().startsWith(".download-")).count())
                        .isEqualTo(System.getProperty("os.name").startsWith("Windows") ? 1 : 0);
                assertThat(overlapping.read()).isEqualTo(Byte.toUnsignedInt(response.getContentAsByteArray()[0]));
            }
            Files.move(destination, root.resolve("original"));
            Files.write(destination, new byte[Math.toIntExact(result.bytes())]);
            var changedWhileOpen = mvc.perform(download).andReturn().getResponse();
            assertThat(changedWhileOpen.getStatus()).isEqualTo(409);
            assertThat(changedWhileOpen.getContentAsString()).contains("transfer.target-changed");
            assertThat(verified.readAllBytes()).containsExactly(Arrays.copyOfRange(response.getContentAsByteArray(),
                    1, response.getContentAsByteArray().length));
        }
        for (boolean missing : List.of(false, true)) {
            if (missing) Files.delete(destination);
            var conflict = mvc.perform(download).andReturn().getResponse();
            assertThat(conflict.getStatus()).isEqualTo(409);
            assertThat(tools.jackson.databind.json.JsonMapper.builder().build().readTree(conflict.getContentAsByteArray())
                    .required("code").asString()).isEqualTo("transfer.target-changed");
        }
        try (var entries = Files.list(RuntimeFiles.environmentTransferDirectory())) {
            assertThat(entries.noneMatch(path -> path.getFileName().toString().startsWith(".download-"))).isTrue();
        }
        Path next = root.resolve("second.pdbackup");
        var retry = new EnvironmentExportService.Request(next.toString(), null, false, false, false, false, true);
        if (args[1].startsWith("restart-")) {
            try (var recovered = new GenericApplicationContext()) {
                recovered.refresh();
                var finished = new java.util.concurrent.CountDownLatch(1);
                var second = new EnvironmentExportService(recovered, new String[0],
                        session -> new EnvironmentTransferService(host, List::of, () -> true, session, new String[0]),
                        () -> { finished.countDown(); return true; });
                var acceptedAgain = second.start(retry);
                assertThat(acceptedAgain.id()).isNotEqualTo(accepted.id());
                assertThat(finished.await(45, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                try (var verified = second.archive(acceptedAgain.id())) {
                    assertThat(verified.readAllBytes()).containsExactly(Files.readAllBytes(next));
                }
            }
        } else {
            assertThatThrownBy(() -> service.start(retry)).isInstanceOf(java.io.IOException.class).hasMessage("transfer.busy");
        }
        if (args[1].equals("context")) checkConcurrentDownloads(service, controller, mvc, accepted.id(), destination);
        }
    }

    private static void checkConcurrentDownloads(EnvironmentExportService service, EnvironmentTransferController controller,
            org.springframework.test.web.servlet.MockMvc mvc, UUID id, Path destination) throws Exception {
        byte[] bytes = new byte[1024 * 1024];
        new Random(42).nextBytes(bytes);
        Files.write(destination, bytes);
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        Path state = RuntimeFiles.environmentTransferDirectory();
        Path journal = state.resolve("api-export.json");
        var job = (tools.jackson.databind.node.ObjectNode) json.readTree(Files.readAllBytes(journal));
        job.put("bytes", bytes.length).put("sha256", HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        Files.write(journal, json.writeValueAsBytes(job));
        checkInterruptedReader(service, id, bytes);
        checkCloseDuringVerification(service, id, destination, bytes);
        try (var parts = Files.newDirectoryStream(state, ".download-*.part")) {
            assertThat(parts.iterator().hasNext()).isFalse();
        }
        var writing = new java.util.concurrent.CountDownLatch(1);
        var disconnect = new java.util.concurrent.CountDownLatch(1);
        var first = mock(jakarta.servlet.http.HttpServletResponse.class);
        when(first.getOutputStream()).thenReturn(new jakarta.servlet.ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(jakarta.servlet.WriteListener listener) {}
            @Override public void write(int b) throws java.io.IOException { throw new AssertionError(); }
            @Override public void write(byte[] buffer, int off, int count) throws java.io.IOException {
                writing.countDown();
                try { assertThat(disconnect.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { throw new java.io.IOException(interrupted); }
                throw new java.io.IOException("client disconnected");
            }
        });
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var closed = executor.submit(() -> assertThatThrownBy(() -> controller.archive(id, first)).hasMessage("client disconnected"));
            try {
                assertThat(writing.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/settings/environment/exports/" + id + "/archive");
                assertThat(mvc.perform(request).andReturn().getResponse().getContentAsByteArray()).containsExactly(bytes);
                try (var second = service.archive(id)) {
                    checkSnapshotSize(bytes.length);
                    assertThat(second.read()).isEqualTo(Byte.toUnsignedInt(bytes[0]));
                }
                Files.write(destination, new byte[0]);
                job.put("bytes", 0).put("sha256", HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(new byte[0])));
                Files.write(journal, json.writeValueAsBytes(job));
                var changed = mvc.perform(request).andReturn().getResponse();
                assertThat(changed.getStatus()).isEqualTo(409);
                assertThat(changed.getContentAsString()).contains("transfer.target-changed");
            } finally { disconnect.countDown(); }
            closed.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        try (var entries = Files.newDirectoryStream(state, ".download-*.part")) {
            assertThat(entries.iterator().hasNext()).isFalse();
        }
        // The journal now describes the empty replacement. A leaked reference would keep the old snapshot active.
        try (var replacement = service.archive(id)) {
            assertThat(replacement.read()).isEqualTo(-1);
        }
    }

    private static void checkCloseDuringVerification(EnvironmentExportService service, UUID id, Path destination,
                                                     byte[] bytes) throws Exception {
        var verifying = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var finished = service.archive(id); var other = service.archive(id);
             var workers = Executors.newFixedThreadPool(2)) {
            assertThat(finished.readAllBytes()).containsExactly(bytes);
            var pending = workers.submit(() -> {
                try (var files = mockStatic(Files.class, call -> {
                    if (call.getMethod().getName().equals("newInputStream") && destination.equals(call.getArgument(0))) {
                        verifying.countDown();
                        if (!resume.await(20, TimeUnit.SECONDS)) throw new AssertionError("Archive verification timed out");
                    }
                    return call.callRealMethod();
                }); InputStream input = service.archive(id)) {
                    return input.readAllBytes();
                }
            });
            try {
                assertThat(verifying.await(5, TimeUnit.SECONDS)).isTrue();
                workers.submit(() -> { finished.close(); return null; }).get(5, TimeUnit.SECONDS);
                Files.write(destination, new byte[bytes.length]);
                assertThat(other.readAllBytes()).containsExactly(bytes);
                other.close();
                checkSnapshotSize(bytes.length);
            } finally {
                resume.countDown();
            }
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                    .cause().isInstanceOf(java.io.IOException.class).hasMessage("transfer.target-changed");
        } finally {
            Files.write(destination, bytes);
        }
    }

    private static void checkInterruptedReader(EnvironmentExportService service, UUID id, byte[] bytes) throws Exception {
        try (var first = service.archive(id); var second = service.archive(id);
             var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertThat(second.read()).isEqualTo(Byte.toUnsignedInt(bytes[0]));
            var interrupted = executor.submit(() -> {
                Thread.currentThread().interrupt();
                assertThatThrownBy(first::read).isInstanceOf(java.io.IOException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            });
            interrupted.get(10, java.util.concurrent.TimeUnit.SECONDS);
            first.close();
            try (var third = service.archive(id)) {
                checkSnapshotSize(bytes.length);
                assertThat(third.readAllBytes()).containsExactly(bytes);
            }
            assertThat(second.readAllBytes()).containsExactly(Arrays.copyOfRange(bytes, 1, bytes.length));
        }
    }

    private static void checkSnapshotSize(long bytes) throws java.io.IOException {
        try (var entries = Files.newDirectoryStream(RuntimeFiles.environmentTransferDirectory(), ".download-*.part")) {
            var parts = new ArrayList<Path>();
            entries.forEach(parts::add);
            assertThat(parts).hasSize(System.getProperty("os.name").startsWith("Windows") ? 1 : 0);
            for (Path part : parts) assertThat(Files.size(part)).isEqualTo(bytes);
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(exclude = org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
    static class WebFixture {}
}
