package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务插件源码边界守卫（<b>文本级</b>），与 {@link PluginApiDependencyGuardTest} 的字节码级 ArchUnit 守卫互补。
 *
 * <p>ArchUnit 只能拦编译期符号——「依赖 sidecar 实现类」「调用 {@code java.nio.file.Files} 读取方法」，
 * 拦不到业务插件把 sidecar 文件名实现细节以<b>字符串 / 自定义符号</b>形式硬编码回潮，例如
 * 自行拼 {@code dir.resolve(id + ".meta.json")}、复制 {@code SIDECAR_SUFFIX} 后缀常量、或绕道
 * {@code WorkSidecarStore} 解析器。本守卫直接扫描业务插件 main 源码文本，命中即失败并报出
 * 文件路径 + 行号 + 命中 token，便于定位修复。
 *
 * <p>合法读法只有一条：sidecar 经 {@code WorkAssetService.findSidecarMeta}（产出 plugin.api JDK-only
 * 模型 {@code WorkSidecarMeta}）读、普通作品文件经 {@code WorkAssetService} 枚举 / 读取。禁用 token 精确到
 * 不会误伤合法的接口名 {@code findSidecarMeta} 与模型名 {@code WorkSidecarMeta}（两者均不含任一禁用 token）。
 *
 * <p>source 范围：app 内仍未外置的 {@code NovelGalleryService} + {@code NovelBatchService}。
 * 只扫 main 业务插件代码——测试代码自身（如本类、{@link PluginApiDependencyGuardTest}）允许出现这些 token。
 */
@DisplayName("业务插件源码边界：不得硬编码 sidecar 文件名实现细节")
class BusinessPluginSourceBoundaryTest {

    /**
     * 业务插件 source 根。相对项目根路径，surefire 工作目录即模块根，Windows / CI 同样适用
     * （{@link Path#of} 按平台分隔符拼接，不写死正斜杠）。
     */
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "top", "sywyar", "pixivdownload");

    /**
     * sidecar 实现细节 token：出现在业务插件源码即视为回潮。逐一精确，避免误伤合法名：
     * {@code WorkSidecarStore}（解析器，注意不是 {@code WorkSidecarMeta} 模型）。
     */
    private static final List<String> FORBIDDEN_TOKENS = List.of(
            ".meta.json",       // 自行拼接 sidecar 文件名
            "SIDECAR_SUFFIX",   // 复制 sidecar 后缀常量
            "WorkSidecarStore"  // 直依赖 sidecar 解析器（合法的 WorkSidecarMeta 模型不在此列）
    );

    @Test
    @DisplayName("小说画廊两服务源码不得出现 sidecar 文件名实现细节（.meta.json / SIDECAR_SUFFIX / WorkSidecarStore）")
    void businessPluginSourcesMustNotMentionSidecarImplementationDetails() {
        List<Path> sources = businessPluginSources();

        // 路径或工作目录异常会让守卫空跑成假绿，先把 source 清单的完整性钉死。
        List<Path> missing = sources.stream().filter(p -> !Files.isRegularFile(p)).toList();
        assertThat(missing)
                .as("业务插件 source 文件缺失（多半是测试工作目录不是模块根，或源码已挪位）：%s", missing)
                .isEmpty();
        assertThat(sources)
                .as("业务插件 source 扫描清单不应为空")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            List<String> lines = readLines(source);
            for (int lineNo = 0; lineNo < lines.size(); lineNo++) {
                String line = lines.get(lineNo);
                for (String token : FORBIDDEN_TOKENS) {
                    if (line.contains(token)) {
                        violations.add(source + ":" + (lineNo + 1)
                                + "  命中 \"" + token + "\"  ->  " + line.trim());
                    }
                }
            }
        }

        assertThat(violations)
                .as("业务插件不得硬编码 sidecar 文件名实现细节：sidecar 只能经 WorkAssetService.findSidecarMeta 读，"
                        + "普通作品文件枚举 / 读取也应经 WorkAssetService，不得自行拼 {workId}.meta.json 或依赖 "
                        + "sidecar 实现层。\n命中清单：\n%s", String.join("\n", violations))
                .isEmpty();
    }

    /** app 内仍未外置的两个小说画廊服务文件。 */
    private static List<Path> businessPluginSources() {
        List<Path> sources = new ArrayList<>();
        sources.add(SOURCE_ROOT.resolve("novel").resolve("NovelGalleryService.java"));
        sources.add(SOURCE_ROOT.resolve("novel").resolve("NovelBatchService.java"));
        return sources;
    }

    private static List<Path> collectJavaFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("遍历业务插件源码目录失败：" + dir, e);
        }
    }

    private static List<String> readLines(Path source) {
        try {
            return Files.readAllLines(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取业务插件源码失败：" + source, e);
        }
    }
}
