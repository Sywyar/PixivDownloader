package top.sywyar.pixivdownload.sdk.development;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("独立契约分别验证源码、旧二进制链接与新调用行为")
class ContractEvolutionTest {
    @TempDir Path temporary;

    @Test @DisplayName("新增抽象方法允许旧调用链接，但新宿主调用旧实现得到 AbstractMethodError")
    void abstractAdditionHasThreeDistinctResults() throws Exception {
        Path baseline = contract("old", "int ping();");
        Path implementation = compile("old-plugin", "Legacy", "public class Legacy implements Api { public int ping(){return 7;} }", baseline);
        byte[] original = Files.readAllBytes(implementation);
        Path candidate = contract("new", "int ping(); int added();");
        var report = ContractEvolution.compare(baseline, candidate);
        assertThat(report.binaryLinkage()).isEqualTo("NO_DETECTED_BREAK");
        assertThat(report.sourceCompatibility()).isEqualTo("BREAKING_FOR_EXISTING_SOURCE");
        assertThat(report.newHostOldImplementation()).isEqualTo("NEW_ABSTRACT_METHOD_UNIMPLEMENTED");
        try (var loader = new URLClassLoader(new URL[]{candidate.toUri().toURL(), implementation.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Object provider = loader.loadClass("fixture.Legacy").getConstructor().newInstance();
            Class<?> api = loader.loadClass("fixture.Api");
            assertThat(api.getMethod("ping").invoke(provider)).isEqualTo(7);
            assertThatThrownBy(() -> api.getMethod("added").invoke(provider)).hasCauseInstanceOf(AbstractMethodError.class);
        }
        assertThatThrownBy(() -> compile("recompiled-plugin", "Legacy", "public class Legacy implements Api { public int ping(){return 7;} }", candidate))
                .hasMessage("FIXTURE_COMPILE_FAILED");
        assertThat(Files.readAllBytes(implementation)).isEqualTo(original);
        Path output = temporary.resolve("report.json");
        assertThat(ContractEvolution.write(baseline, candidate, output)).isFalse();
        assertThat(Files.readString(output)).contains("ABSTRACT_METHOD_ADDED", "NOT_ASSESSED");
        assertThatThrownBy(() -> ContractEvolution.write(baseline, candidate, output)).hasMessage("SDK_EVOLUTION_OUTPUT_EXISTS");
    }

    @Test @DisplayName("默认方法由旧实现实际执行，删除旧方法由旧消费者真实触发链接错误")
    void defaultAndRemoval() throws Exception {
        Path baseline = contract("old", "int ping();");
        Path implementation = compile("legacy", "Legacy", """
                public class Legacy implements Api {
                  public int ping(){return 7;}
                  public static int call(Api service){return service.ping();}
                }
                """, baseline);
        byte[] original = Files.readAllBytes(implementation);
        Path defaults = contract("default", "int ping(); default int added(){return 9;}");
        assertThat(ContractEvolution.compare(baseline, defaults).binaryLinkage()).isEqualTo("NO_DETECTED_BREAK");
        try (var loader = new URLClassLoader(new URL[]{defaults.toUri().toURL(), implementation.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Object provider = loader.loadClass("fixture.Legacy").getConstructor().newInstance();
            assertThat(loader.loadClass("fixture.Api").getMethod("added").invoke(provider)).isEqualTo(9);
        }
        Path removed = contract("removed", "int replacement();");
        assertThat(ContractEvolution.compare(baseline, removed).binaryLinkage()).isEqualTo("POTENTIALLY_BREAKING");
        try (var loader = new URLClassLoader(new URL[]{removed.toUri().toURL(), implementation.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> type = loader.loadClass("fixture.Legacy");
            Object provider = type.getConstructor().newInstance();
            assertThatThrownBy(() -> type.getMethod("call", loader.loadClass("fixture.Api")).invoke(null, provider))
                    .hasCauseInstanceOf(NoSuchMethodError.class);
        }
        assertThat(Files.readAllBytes(implementation)).isEqualTo(original);
    }

    @Test @DisplayName("静态分派、内联常量、泛型与受检异常变化均在各自维度提示")
    void otherSurfaceChanges() throws Exception {
        Path baseline = contract("baseline", "int VALUE=3; java.util.List<String> values() throws java.io.IOException; static int ping(){return 1;}");
        Path candidate = contract("candidate", "int VALUE=4; java.util.List<Integer> values(); int ping();");
        var report = ContractEvolution.compare(baseline, candidate);
        assertThat(report.binaryLinkage()).isEqualTo("POTENTIALLY_BREAKING");
        assertThat(report.findings()).extracting(ContractEvolution.Finding::code)
                .contains("MEMBER_DISPATCH_CHANGED", "INLINED_CONSTANT_CHANGED", "GENERIC_SIGNATURE_CHANGED", "THROWS_CHANGED");
        assertThat(ContractEvolution.compare(baseline, baseline).sourceCompatibility()).isEqualTo("NO_STRUCTURAL_CHANGE");
    }

    private Path contract(String folder, String methods) throws Exception {
        return compile(folder, "Api", "public interface Api {" + methods + "}", null);
    }
    private Path compile(String folder, String name, String body, Path dependency) throws Exception {
        Path directory = Files.createDirectories(temporary.resolve(folder));
        Path source = directory.resolve(name + ".java"), classes = Files.createDirectories(directory.resolve("classes"));
        Files.writeString(source, "package fixture;\n" + body, StandardCharsets.UTF_8);
        var args = new ArrayList<>(List.of("--release", "17", "-proc:none", "-encoding", "UTF-8", "-d", classes.toString()));
        if (dependency != null) args.addAll(List.of("-cp", dependency.toString()));
        args.add(source.toString());
        if (ToolProvider.getSystemJavaCompiler().run(null, new ByteArrayOutputStream(), new ByteArrayOutputStream(), args.toArray(String[]::new)) != 0)
            throw new IllegalStateException("FIXTURE_COMPILE_FAILED");
        Path jar = directory.resolve(name + ".jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file)); out.closeEntry();
            }
        }
        return jar;
    }
}
