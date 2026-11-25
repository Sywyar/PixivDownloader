package top.sywyar.pixivdownload.repairprobe;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import org.springframework.context.annotation.Bean;
import top.sywyar.pixivdownload.plugin.api.stream.PluginStreamRegistrar;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRejectedException;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import top.sywyar.pixivdownload.push.PushChannel;
import top.sywyar.pixivdownload.push.PushChannelId;
import top.sywyar.pixivdownload.push.PushChannelSettings;
import top.sywyar.pixivdownload.push.PushFormat;
import top.sywyar.pixivdownload.push.PushResult;
import top.sywyar.pixivdownload.push.RenderedMessage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 真实 PF4J 启动失败包；构造版本 7 失败，版本 8 可供管理入口替换修复。 */
public class StartupRepairProbePlugin extends Plugin implements PixivPluginProvider {
    public StartupRepairProbePlugin(PluginWrapper wrapper) { super(wrapper); }

    @Override
    public void start() {
        String version = getWrapper().getDescriptor().getVersion();
        if (version.startsWith("7.") || (getWrapper().getPluginId() + "@" + version).equals(System.getProperty("test.plugin.fail-start"))) {
            throw new IllegalStateException("fixture startup failure");
        }
    }

    @Override
    public PixivFeaturePlugin featurePlugin() {
        String generation = getWrapper().getPluginId() + "@" + getWrapper().getDescriptor().getVersion();
        if (generation.equals(System.getProperty("test.plugin.fail-init"))) {
            throw new IllegalStateException("fixture initialization failure");
        }
        return new Feature(getWrapper().getPluginId());
    }

    public static class ContextProbe {
        @Bean(destroyMethod = "close")
        public AutoCloseable probeContext() {
            if (Boolean.getBoolean("test.plugin.fail-context")) throw new IllegalStateException("fixture context failure");
            System.getProperties().put("test.graph.ready", (Runnable) () -> System.setProperty("test.graph.used", "true"));
            return () -> System.getProperties().remove("test.graph.ready");
        }
    }

    public static class StartupTaskConfig {
        @Bean
        public AtomicInteger startupStreamCloses(PluginStreamRegistrar streams) {
            var closes = new AtomicInteger();
            streams.register("startup-probe", closes::incrementAndGet);
            streams.unregister("startup-probe");
            return closes;
        }

        @Bean
        public AtomicInteger startupTaskRuns(PluginRuntimeTaskRegistrar tasks) {
            var runs = new AtomicInteger();
            try {
                tasks.registerOneShot(runs::incrementAndGet).run();
            } catch (PluginRuntimeTaskRejectedException quiesced) {
                // 即使任务准入已关闭，恢复后的子 context 仍会初始化。
            }
            return runs;
        }
    }

    public static class ShutdownConfig {
        private static BufferedWriter writer;

        @Bean(destroyMethod = "close")
        public BufferedWriter stopLog() throws IOException {
            writer = Files.newBufferedWriter(Path.of(System.getProperty("test.stop.output", "stop.log")), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return writer;
        }
    }

    public static class PushConfig {
        @Bean public PushChannel probeChannel() { return new PushProbe(); }
    }

    public static class PushProbe implements PushChannel {

        @Override public PushChannelId type() { return new PushChannelId("restore-probe"); }
        @Override public boolean isConfigured() { return true; }
        @Override public List<PushFormat> supportedFormats() { return List.of(PushFormat.PLAIN_TEXT); }
        @Override public PushResult send(RenderedMessage message) {
            System.setProperty("test.push.sends", Integer.toString(Integer.getInteger("test.push.sends", 0) + 1));
            return PushResult.ok(type());
        }
        @Override public PushResult sendTest(PushChannelSettings settings, RenderedMessage message) { return send(message); }
    }

    public record Feature(String id) implements PixivFeaturePlugin {
        @Override public void start() {
            if (!id.startsWith("graph-")) return;
            String counter = "test.starts." + id;
            System.setProperty(counter, Integer.toString(Integer.getInteger(counter, 0) + 1));
            if (id.equals("graph-mid") && !Boolean.getBoolean("test.plugin.fail-context")) {
                ((Runnable) System.getProperties().get("test.graph.ready")).run();
            }
        }

        @Override public void stop() {
            if (ShutdownConfig.writer == null) return;
            System.out.println("writer stop entered " + id);
            String failures = "test.stop.failures." + id;
            int remaining = Integer.getInteger(failures, 0);
            if (remaining != 0) {
                String attempts = "test.stop.attempts." + id;
                System.setProperty(attempts, Integer.toString(Integer.getInteger(attempts, 0) + 1));
                if (remaining > 0) System.setProperty(failures, Integer.toString(remaining - 1));
                throw new IllegalStateException("stop failure for " + id);
            }
            if (Boolean.getBoolean("test.stop.buffered-only")) return;
            try {
                ShutdownConfig.writer.write(id + "\n");
                ShutdownConfig.writer.flush();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override public List<WebRouteContribution> routes() {
            return List.of(WebRouteContribution.admin("/repair-probe/" + id + "/version.txt"));
        }
        @Override public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution("classpath:/repair-probe/" + id + "/", "/repair-probe/" + id + "/"));
        }
        @Override public String displayName() { return "plugin.name"; }
        @Override public String description() { return "plugin.summary"; }
        @Override public top.sywyar.pixivdownload.plugin.api.plugin.PluginKind kind() {
            return top.sywyar.pixivdownload.plugin.api.plugin.PluginKind.FEATURE;
        }
    }
}
