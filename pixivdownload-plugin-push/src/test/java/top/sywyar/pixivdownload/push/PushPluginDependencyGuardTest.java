package top.sywyar.pixivdownload.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("push 插件描述符依赖")
class PushPluginDependencyGuardTest {

    @Test
    @DisplayName("push 依赖 notification 基础插件")
    void pushDependsOnNotificationPlugin() throws IOException {
        Properties properties = new Properties();
        try (var in = PushPluginDependencyGuardTest.class.getResourceAsStream("/plugin.properties")) {
            assertThat(in).as("plugin.properties 应存在于插件根部").isNotNull();
            properties.load(in);
        }

        assertThat(properties.getProperty("plugin.dependencies"))
                .isEqualTo("notification@1.0");
    }

    @Test
    @DisplayName("推送介质生产实现与资源不得解释具体计划来源或作品类型")
    void productionPushSurfaceIsScheduleSourceNeutral() throws IOException {
        Path moduleRoot = Files.isDirectory(Path.of("pixivdownload-plugin-push"))
                ? Path.of("pixivdownload-plugin-push") : Path.of(".");
        List<String> forbidden = List.of(
                "www.pixiv.net/artworks/",
                "sample.task-type.user-new",
                "sample.work-kind.illust");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(moduleRoot.resolve("src/main"))) {
            for (Path source : sources.filter(Files::isRegularFile).sorted().toList()) {
                String name = source.getFileName().toString();
                if (!(name.endsWith(".java") || name.endsWith(".properties"))) {
                    continue;
                }
                String content = Files.readString(source);
                for (String token : forbidden) {
                    if (content.contains(token)) {
                        violations.add(moduleRoot.relativize(source) + " -> " + token);
                    }
                }
            }
        }
        assertThat(violations).as("Push 介质中的来源私有计划语义").isEmpty();
    }
}
